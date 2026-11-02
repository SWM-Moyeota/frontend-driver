# 29b. driver 도메인 백엔드 동기화 분석 (읽기 전용)

- 작성: api-integrator (driver 범위)
- 백엔드 기준: `e729801` (2026-10-13, HEAD) / 비교 기준선 `b9a921d` (2026-09-09)
- 기사 앱 기준: `frontend-driver` 작업트리 (feature/app-icon)
- **코드 수정 없음** — 분석·보고만

## 요약 (먼저 읽을 것)

| # | 심각도 | 내용 |
|---|---|---|
| A | **깨짐 (최우선, 환경)** | 배포 서버가 `taxiEnabled=false` → `/api/v1/drivers/**` **전체가 등록되지 않음**. 기사 앱 로그인부터 불가. **라이브로 확인됨** |
| B | **깨짐 (교차 의존)** | `POST /api/v1/auth/register` 가 `verificationToken` 필수화 → driver `signUp()` 1단계에서 400. auth 에이전트 소관이나 driver 가입 체인이 여기서 끊김 |
| C | 동작변경 | `removeFcmToken()` 이 선언만 있고 `logout()` 에서 호출되지 않음 → 기기 계정 교체 시 이전 기사에게 콜 푸시 |
| D | 무해 | driver 패키지 경로 이동 (`application/dto` → `dto`, `interfaces/` → 루트). 클라이언트 KDoc 주석만 낡음 |
| E | **깨짐 아님 (확정)** | **FCM 토큰 등록 API 는 바뀌지 않았다.** `PUT /api/v1/drivers/fcm-token` + `{token}` 그대로 |
| F | **깨짐 아님 (전수 대조 완료)** | **기사 등록 `POST /api/v1/drivers` 요청 바디 7개 필드 전부 일치.** 좌석수·번호판·차종·자격번호·은행 전부 동일 |
| G | 무해 | `DriverResult.name` 필드 유지 + 닉네임 주입 경로 정상 |
| H | 무해 | 서버 Swagger 문서의 에러코드 오기(`DRIVER_ALREADY_VERIFIED` ← 실제 `DRIVER_NOT_PENDING`). 클라이언트는 HTTP 409 만 보므로 영향 없음 |
| I | **미검증** | QA 계정 `mapqb3481` 배포 서버 로그인 실패(401 USER102) → 실응답 shape 직접 확인 불가 |

> **배포 서버는 HEAD 가 아니다.** 라이브 OpenAPI 에 `/api/v1/auth/sms/**` 가 없고 `/api/v1/config` 는 있다
> → 배포 빌드는 **2026-09-19 이후 ~ 2026-10-11 이전**. 즉 클라이언트를 HEAD 에 맞추면 현재 배포 서버와는
> 또 안 맞는다. 수정 전에 **서버 재배포(+`TAXI_ENABLED=true`)가 선행 조건**임을 리더가 판단해야 한다.

---

## 1. 현재 백엔드 API 표 (driver 도메인, HEAD `e729801`)

원천: `backend/src/main/java/team/codingforest/moyeota/driver/DriverController.java` (전체 `@RequestMapping("/api/v1")`)
**전 엔드포인트가 `@ConditionalOnProperty(prefix="moyeota.taxi", name="enabled", havingValue="true", matchIfMissing=true)` 로 감싸져 있다** (DriverController.java:28).

| # | 메서드 | 경로 | 인증 주체 | 요청 바디 | 성공 | 응답 | 주요 에러 |
|---|---|---|---|---|---|---|---|
| 1 | POST | `/api/v1/drivers` | `@CurrentUser Long userId` | `RegisterDriverRequest` | 200 | `DriverResult` | 400 입력누락·`INVALID_VEHICLE_SEATS` / 409 `DRIVER_ALREADY_REGISTERED` |
| 2 | POST | `/api/v1/drivers/verify` | `@CurrentDriver Long driverId` | 없음 | 204 | 없음 | 404 `DRIVER_NOT_REGISTERED`(리졸버) / 409 `DRIVER_NOT_PENDING` |
| 3 | POST | `/api/v1/drivers/vehicle` | `@CurrentDriver` | `RegisterVehicleRequest` | 204 | 없음 | 400 `INVALID_VEHICLE_SEATS`·`INVALID_PLATE_NUMBER` / 404 |
| 4 | POST | `/api/v1/drivers/call` | `@CurrentDriver` | 없음 | 204 | 없음 | 404 |
| 5 | DELETE | `/api/v1/drivers/call` | `@CurrentDriver` | 없음 | 204 | 없음 | 404 |
| 6 | GET | `/api/v1/drivers/me` | `@CurrentUser Long userId` | 없음 | 200 | `DriverResult` | 404 `DRIVER_NOT_REGISTERED` |
| 7 | PUT | `/api/v1/drivers/fcm-token` | `@CurrentDriver` | `RegisterFcmTokenRequest` | 204 | 없음 | 400 `DRIVER_EMPTY_FCM_TOKEN` / 404 |
| 8 | DELETE | `/api/v1/drivers/fcm-token` | `@CurrentDriver` | 없음 | 204 | 없음 | 404 |

### 요청/응답 필드 전수

**`RegisterDriverRequest`** (`dto/RegisterDriverRequest.java`)
| 필드 | 타입 | 검증 |
|---|---|---|
| `qualificationNumber` | String | `@NotBlank` (예 `서울-1234-5678`) |
| `bankName` | String | `@NotBlank` (예 `국민은행`) |
| `accountNumber` | String | `@NotBlank` (예 `123-456-789012`) |
| `vehicle` | 중첩 객체 `VehicleInfo` | `@Valid @NotNull` |
| `vehicle.seats` | Integer | `@NotNull @Min(2)` |
| `vehicle.plateNumber` | String | `@NotBlank` |
| `vehicle.type` | String | `@NotBlank` (예 `중형`) |

- `userId` 는 **바디에 없다** — 토큰(`@CurrentUser`)에서 해석.
- 도메인 2차 검증: `BankAccount`(bankName/accountNumber 공백 → 400 `INVALID_BANK_NAME`/`INVALID_ACCOUNT_NUMBER`), `Vehicle`(plateNumber 공백 → `INVALID_PLATE_NUMBER`, `seats < 1` → `INVALID_VEHICLE_SEATS`). DTO `@Min(2)` 가 먼저 걸러 실효 하한은 **2**.
- 등록 직후 상태: `DriverStatus.PENDING`, `DriverSetting.defaults()` → **`callEnabled = true`**.

**`DriverResult`** (응답 — #1, #6 공통)
| 필드 | 타입 | 비고 |
|---|---|---|
| `id` | Long | 내부 driverId. dispatch 쪽 path 파라미터로 쓰임 |
| `userId` | Long | |
| `name` | String \| **null** | user 모듈 닉네임. `DriverService:112` → `userAccess.findNickname(userId)` → `User.getNickname()`. 미설정이면 null |
| `status` | enum `PENDING` \| `VERIFIED` | |
| `callEnabled` | boolean(primitive) | |

**`RegisterFcmTokenRequest`**: `{ "token": String }` — `@NotBlank` 하나뿐.
**`RegisterVehicleRequest`**: `{ "seats": Integer(@Min 2), "plateNumber": String, "type": String }`.

**`DriverFcmToken`** (`dto/DriverFcmToken.java`, 0586b96 신규) — `record DriverFcmToken(Long driverId, String fcmToken)`.
→ **API DTO 가 아니다.** `DriverJpaRepository.findFcmTokens()` 의 JPQL `select new ...DriverFcmToken(d.id, d.fcmToken)` 프로젝션 전용 내부 타입. HTTP 표면에 노출되지 않는다(라이브 OpenAPI `components.schemas` 에도 없음). 클라이언트 작업 불필요.

### 에러코드 전체 (`exception/DriverErrorCode.java`)
`DRIVER_NOT_FOUND`(404) · `UNAUTHORIZED`(401) · `DRIVER_NOT_REGISTERED`(404) · `DRIVER_ALREADY_REGISTERED`(409) · `DRIVER_NOT_PENDING`(409) · `DRIVER_EMPTY_FCM_TOKEN`(400) · `INVALID_BANK_NAME`(400) · `INVALID_ACCOUNT_NUMBER`(400) · `INVALID_PLATE_NUMBER`(400) · `INVALID_VEHICLE_SEATS`(400)
에러 바디는 공통 `{code, message}`. `code` 는 **enum 이름 그대로**(`getCode() { return name(); }`) — user 모듈의 `USER1xx` 식 접두 코드와 체계가 다르다.

### 인증 주체 해석 (`auth/CurrentDriverArgumentResolver.java`)
`@CurrentDriver` → SecurityContext principal 없으면 401 `UNAUTHORIZED`, 있으면 `drivers.findByUserId(principal.userId())` → 없으면 **404 `DRIVER_NOT_REGISTERED`**, 있으면 내부 `driverId` 주입. 즉 `@CurrentDriver` 엔드포인트는 전부 "로그인 + 기사 등록 완료" 를 요구한다.

### b9a921d → HEAD 변경 이력 (`git log b9a921d..HEAD -- "*driver*"`)
| 커밋 | 내용 | API 표면 영향 |
|---|---|---|
| `fc8f6f3` 기사 fcm 토큰 수정 | `DriverAccessService.findFcmTokens()` 를 루프 N+1 → `drivers.findFcmTokens()` 단일 쿼리로 교체 (1 insert / 8 delete, 파일 1개) | **없음.** 모듈 내부 서비스. HTTP 엔드포인트 아님 |
| `0586b96` 배차 중복 방지/신고 롤백/FCM 일괄 조회 | `DriverFcmToken` record + `DriverJpaRepository.findFcmTokens` JPQL + `Drivers.findFcmTokens` 추가 | **없음.** 영속 계층 프로젝션 |
| `cec0303` 테스트 폴더 분리 | 테스트 전용 | 없음 |
| `9c66992` DDD 폴더 재구성 | `application/dto/*` → `dto/*`, `interfaces/DriverController` → `DriverController`, `domain/enums/DriverStatus` → `domain/DriverStatus`, `domain/exception/` → `exception/` | **경로·시그니처 불변.** 자바 패키지만 이동 |
| `a0dacc1` 부하테스트 세팅 | — | 없음 |
| `b172498` 기사 닉네임 호출 API 수정 | (기준선 포함분) | — |
| `8bdcd1e` Swagger 추가 | `@Operation`/`@ApiResponses`/`@Schema` 애노테이션만 | 없음 |
| `4f70f52` 택시 기능 플래그 | `@ConditionalOnProperty(moyeota.taxi.enabled)` 를 DriverController 등에 부착 | **런타임 전체 on/off** — 아래 A 참조 |
| `0ff35bd`,`fcbb3fe` import·패키지명 정리 | — | 없음 |

**결론 (지시사항 2 에 대한 확정 답):** 기사 FCM 토큰 등록 API 의 **경로·메서드·요청 바디는 하나도 바뀌지 않았다.**
`PUT /api/v1/drivers/fcm-token`, 바디 `{"token": "..."}`, 204 — b9a921d 와 HEAD 가 동일. 두 커밋은 서버 내부 조회 최적화였다.

### 라이브 배포 서버 실측 (https://api.moyeota.p-e.kr, 2026-10-13)
```
GET /api/v1/config          → 200  {"taxiEnabled":false}
GET /v3/api-docs            → 200  paths 36개 — /api/v1/drivers/* 0건
                                   components.schemas 에 DriverResult / RegisterDriverRequest 없음
POST /api/v1/auth/login     → 401  {"code":"USER102","message":"아이디나 비밀번호가 다릅니다."}
                                   (QA 계정 mapqb3481 / test123!@ — 배포 서버에 없음)
GET /actuator/health        → 401  (management 포트 분리, 외부 미개방)
```
라이브 OpenAPI 에 `/api/v1/auth/sms/send|verify` 가 **없다** → 배포 빌드는 문자인증(`94bd54d`, 10-11) 이전.
`/api/v1/config`(`6996256`, 09-19) 는 있다 → 배포 빌드 범위 **09-19 ~ 10-11**.

---

## 2. 기사 앱 클라이언트 현재 상태

| 파일 | 라인 | 내용 |
|---|---|---|
| `data/.../remote/DriverApi.kt` | 27-28 | `@POST("api/v1/drivers") register(RegisterDriverRequestDto): DriverResultDto` |
| | 31-32 | `@POST("api/v1/drivers/verify") verify()` |
| | 35-36 | `@POST("api/v1/drivers/vehicle") registerVehicle(RegisterVehicleRequestDto)` |
| | 39-44 | `@POST`/`@DELETE("api/v1/drivers/call") enableCall()/disableCall()` |
| | 50-51 | `@GET("api/v1/drivers/me") getMe(): DriverResultDto` |
| | 53-54 | `@PUT("api/v1/drivers/fcm-token") registerFcmToken(RegisterFcmTokenRequestDto)` |
| | 56-57 | `@DELETE("api/v1/drivers/fcm-token") removeFcmToken()` — **프로덕션 코드에서 호출 없음** |
| | 15 | KDoc 경로 주석 `driver/interfaces/DriverController.java` (현재는 `driver/DriverController.java`) |
| `data/.../remote/dto/DriverDtos.kt` | 16-29 | `RegisterDriverRequestDto(qualificationNumber, bankName, accountNumber, vehicle)` + 중첩 `VehicleInfoDto(seats, plateNumber, type)` |
| | 33-39 | `DriverResultDto(id, userId, name, status, callEnabled=false)` 전 필드 nullable 기본값 |
| | 43-47 | `RegisterVehicleRequestDto(seats, plateNumber, type)` |
| | 51-53 | `RegisterFcmTokenRequestDto(token)` |
| | 7 | KDoc 경로 주석 `.../driver/application/dto` (현재는 `.../driver/dto`) |
| `data/.../repository/RemoteDriverRepository.kt` | 182-215 | `restoreSession()` — `getMe()` 로 세션 검증. **404 → `clearStoredSession()`**(토큰+활성파티 전삭제), 401/403 → `clearExpiredTokens()`, 5xx/네트워크 → 저장 보존 |
| | 203-204 | `driver.name` → `cachedDriverName` 캐시 |
| | 326-350 | `login()` — `/auth/login` → `getMe()`. **404 → `IllegalStateException("기사로 가입되지 않은 계정입니다...")`**, name 은 `getMe().name` 우선, null 이면 `/local/users/info` 폴백 |
| | 400-450 | `signUp()` — ①`authApi.register` ②`authApi.login` ③`driverApi.register`(409→`getMe()`) ④`driverApi.verify`(409 허용) |
| | 419-429 | 등록 바디: `bankName=DEFAULT_BANK_NAME("국민")`, `accountNumber=DEFAULT_ACCOUNT_NUMBER("000000000000")`, `vehicle=(form.seats, form.plateNumber, form.vehicleType)` |
| | 456-… | `logout()` — `/auth/logout` 만 호출. `driverApi.removeFcmToken()` **없음** |
| | 508-526 | `registerVehicle()` — 레거시 D04. 실패 시 더미 폴백 |
| | 533-540 | `getHomeSummary()` — 수치는 더미, `driverName` 만 `getMe().name` 으로 덮음 |
| | 636-640 | `registerFcmToken(token)` — 미로그인 시 보관, 로그인 상태면 즉시 전송 |
| | 690-698 | `sendFcmTokenQuietly()` — `PUT /drivers/fcm-token`, **모든 예외 무시** |
| | 701-704 | `flushFcmTokenAsync()` — login/signUp 성공 직후 비동기 전송 |
| `data/.../remote/AuthMappers.kt` | 13-14 | `driverAccountStatus(status)`: `"VERIFIED"`→APPROVED, 그 외→PENDING_REVIEW |
| | 37-47 | `DriverSignUpForm.toRegisterRequest()` — **`verificationToken` 없음** |
| `data/.../remote/dto/AuthDtos.kt` | 22-31 | `UserRegisterRequestDto(loginId, password, nickname, name, birthDate, phoneNumber, gender, email)` — 8필드 |
| `data/.../remote/NetworkModule.kt` | 28 | `BASE_URL = "https://api.moyeota.p-e.kr/"` |
| | 30-33 | `Json { ignoreUnknownKeys = true; coerceInputValues = true }` |
| `domain/.../repository/DriverRepository.kt` | 34,37,47,83 | `restoreSession()`, `login()`, `signUp()`, `registerFcmToken()` |
| `data/src/test/.../DriverDtosTest.kt` | 24,46,55,69,85 | 등록 요청 직렬화·`DriverResult` 파싱·`name` 파싱·필드 누락 방어 — **서버 shape 과 일치하는 테스트 이미 존재** |

---

## 3. 불일치 목록

### A. 🔴 깨짐 — 배포 서버에 `/api/v1/drivers/**` 가 존재하지 않는다 (`taxiEnabled=false`)
- **확인된 사실**: `GET https://api.moyeota.p-e.kr/api/v1/config` → `{"taxiEnabled":false}`. 라이브 `/v3/api-docs` 36개 경로 중 `/api/v1/drivers/*` 0건, `DriverResult`·`RegisterDriverRequest` 스키마 0건.
- **원인 (소스 확인)**: `backend/src/main/resources/application-prod.yaml:47-48` → `moyeota.taxi.enabled: ${TAXI_ENABLED:false}` (**prod 기본값이 false**, 로컬 `application.yaml:95-96` 은 true). `DriverController.java:28` 의 `@ConditionalOnProperty(..., havingValue="true")` 때문에 플래그가 false 면 컨트롤러 빈 자체가 등록되지 않는다.
- **같은 플래그로 함께 사라지는 것**: `DriverController`, `dispatch/location/DriverLocationController`, `dispatch/call/DispatchCallController`, `dispatch/ride/RideController`, `DispatchListener`, `MatchingSweeper`, `FcmCallNotifier`(= 콜 푸시 자체). 즉 기사 앱 API 표면 **전부**.
- **증상 (클라이언트 코드 경로)**:
  1. 로그인: `/auth/login` 200 → `driverApi.getMe()` → 핸들러 없음 → **404** → `RemoteDriverRepository.kt:341-345` 가 `"기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요."` 로 로그인 실패. → **로그인 불가**.
  2. 앱 재실행: `restoreSession()` 의 `getMe()` 404 → `:194-196` 이 "기사 미등록(확인된 종료)" 로 판정해 **`clearStoredSession()`** — 저장 토큰과 활성 partyId 를 모두 지운다. 서버 설정 문제인데 단말 세션이 파괴된다.
  3. FCM: `sendFcmTokenQuietly()` 가 404 를 조용히 삼켜 **원인 미표시**. 콜 푸시도 `FcmCallNotifier` 부재로 애초에 안 옴.
  4. 404 응답 바디가 공통 `{code,message}` 가 **아니라** Spring 기본 에러 JSON(`{"timestamp","status","error","path"}`) → `serverMessage()` 가 null 이라 에러 메시지도 비어 보인다. (`SecurityConfig` 가 `DispatcherType.ERROR` 를 permitAll 로 열어 둬 401 이 아니라 404 로 떨어진다.)
- **심각도 판단**: 클라이언트 코드 수정으로 해결 불가. **서버 운영 설정(`TAXI_ENABLED=true`) + 재배포가 선행 조건.**

### B. 🔴 깨짐 (교차 의존) — `signUp()` 1단계가 `POST /auth/register` 400 으로 막힌다
- **확인된 사실**: HEAD `user/local/dto/UserRegisterRequest.java` 가 필드 9개 — 마지막에 `@NotBlank String verificationToken`("POST /api/v1/auth/sms/verify 응답의 verificationToken") 가 추가됐다. 클라이언트 `UserRegisterRequestDto`(AuthDtos.kt:22-31)는 8필드로 이 값을 보내지 않는다 → `@Valid` 실패 400.
- **영향**: `RemoteDriverRepository.signUp()`(:400-406) 의 ①계정 생성이 400 → `step("계정 생성")` 이 그대로 예외 전파 → **기사 가입 전체 중단**. 409 만 관용 처리하므로 400 은 삼켜지지 않는다.
- **소관**: auth 에이전트(`29a`). driver 쪽은 체인 의존성만 기록. **단, 현재 배포 서버에는 SMS 엔드포인트가 없으므로** 이 수정은 서버 재배포 후에만 유효하다.

### C. 🟡 동작변경 — 로그아웃 시 서버 FCM 토큰을 지우지 않는다
- **확인된 사실**: `DriverApi.removeFcmToken()`(:56-57) 선언 존재. `grep -rn removeFcmToken --include=*.kt` 결과 프로덕션 호출 **0건** (테스트 Fake 의 `unused()` 스텁 3건뿐). `logout()`(:456-…)은 `/auth/logout` 만 호출.
- **증상**: 같은 단말에서 기사 A 로그아웃 → 기사 B 로그인 시, 서버 `taxi_driver.fcm_token` 에 A 의 토큰이 남아 **A 에게 콜 푸시가 계속 간다**. 백엔드 Swagger 가 명시적으로 경고한 케이스("안 지우면 다음 로그인 계정으로 이전 사용자 알림이 감").
- **완화 요인**: 기기 1대 정책이라 B 로그인 후 `flushFcmTokenAsync()` 가 같은 단말 토큰을 B 레코드에 덮어쓰면 실질 중복은 짧다. 하지만 A 레코드의 stale 토큰은 남는다.

### D. 🟢 무해 — 백엔드 패키지 경로 이동 (`9c66992`)
- `driver/application/dto/*` → `driver/dto/*`, `driver/interfaces/DriverController` → `driver/DriverController`, `driver/interfaces/auth/*` → `driver/auth/*`, `domain/enums/DriverStatus` → `domain/DriverStatus`, `domain/exception/DriverErrorCode` → `exception/DriverErrorCode`.
- HTTP 경로·메서드·JSON 필드명은 전부 불변. 클라이언트 영향은 KDoc 주석 2곳(`DriverApi.kt:15`, `DriverDtos.kt:7`)이 낡은 경로를 가리키는 것뿐.

### E. 🟢 깨짐 아님 (확정) — FCM 토큰 API 불변
| 항목 | b9a921d | HEAD | 클라이언트 |
|---|---|---|---|
| 경로 | `/api/v1/drivers/fcm-token` | 동일 | `DriverApi.kt:53` 동일 |
| 메서드 | PUT | 동일 | `@PUT` 동일 |
| 인증 | `@CurrentDriver` | 동일 | Bearer (authedRetrofit) |
| 바디 | `{token}` `@NotBlank` | 동일 | `RegisterFcmTokenRequestDto(token)` 동일 |
| 응답 | 204 | 동일 | `Unit` 동일 |
| 삭제 | `DELETE` 동일 | 동일 | 선언됨(미호출 — C 참조) |

`fc8f6f3`·`0586b96` 는 **서버 내부 조회 경로만** 바꿨다. `DriverFcmToken` 은 JPQL 프로젝션 record 로 HTTP 표면 밖이다.

### F. 🟢 깨짐 아님 (전수 대조 완료) — `POST /api/v1/drivers` 요청 바디 일치
| 서버 필드 (JSON) | 서버 타입/검증 | 클라이언트 필드 | 클라이언트 값 | 판정 |
|---|---|---|---|---|
| `qualificationNumber` | String `@NotBlank` | `qualificationNumber` | `form.qualificationNumber` | ✅ |
| `bankName` | String `@NotBlank` | `bankName` | `"국민"` (상수) | ✅ 통과 (`@NotBlank` 만) |
| `accountNumber` | String `@NotBlank` | `accountNumber` | `"000000000000"` (상수) | ✅ 통과 |
| `vehicle` | 중첩 `@Valid @NotNull` | `vehicle` | 중첩 객체 | ✅ |
| `vehicle.seats` | Integer `@NotNull @Min(2)` | `vehicle.seats` | `form.seats` | ✅ (화면에서 2 이상 보장 필요) |
| `vehicle.plateNumber` | String `@NotBlank` | `vehicle.plateNumber` | `form.plateNumber` | ✅ |
| `vehicle.type` | String `@NotBlank` | `vehicle.type` | `form.vehicleType` | ✅ |

필드명 오타·대소문자·중첩 깊이 전부 일치. b9a921d→HEAD 의 차이는 `@Schema` 애노테이션 추가뿐(기본값 변화 없음). **심각도 "깨짐" 해당 없음.**
`userId` 를 바디에 보내지 않는 것도 서버와 일치(`@CurrentUser`).

### G. 🟢 무해 — `DriverResult.name` 필드 유지, 닉네임 주입 정상
- `dto/DriverResult.java` → `record DriverResult(Long id, Long userId, String name, DriverStatus status, boolean callEnabled)`. b9a921d 와 **필드 구성 동일**(패키지만 이동).
- `DriverService:112` → `userAccess.findNickname(driver.getUserId())` → `UserAccessService:41-47` → `User.getNickname()`. b172498 이후 변경 없음. 닉네임 미설정이면 null — 클라이언트가 `/local/users/info` 로 폴백하는 현재 구조가 유효.
- 클라이언트 `DriverResultDto` 는 전 필드 nullable + 기본값 + `ignoreUnknownKeys` → 신·구 서버 양쪽 파싱 안전. `DriverDtosTest.kt:55,69,85` 가 이미 커버.
- `status` 문자열은 `PENDING`/`VERIFIED` 2종 그대로 → `driverAccountStatus()`(AuthMappers.kt:13) 매핑 유효.

### H. 🟢 무해 — 서버 Swagger 문서 오기 2건
1. `verify` 의 `@ApiResponse(409, "DRIVER_ALREADY_VERIFIED")` — 실제 enum 에는 그 코드가 없고 `Driver.verify()` 는 `DRIVER_NOT_PENDING` 을 던진다. 클라이언트는 HTTP 409 만 보므로(`signUp` :439-443) 영향 없음.
2. `verify`/`fcm-token` 의 `@ApiResponse(404, "DRIVER_NOT_FOUND")` — `@CurrentDriver` 리졸버가 먼저 `DRIVER_NOT_REGISTERED` 를 던진다. 둘 다 404 라 영향 없음.
3. (서버 내부) `RegisterDriverRequest.vehicle.seats` 는 `@Min(2)` 인데 도메인 `Vehicle` 은 `seats < 1` 검사 — 기준 불일치지만 DTO 가 먼저 걸러 실효 하한 2. 클라이언트 영향 없음.

### I. ⚪ 미검증 — 실서버 응답 shape 직접 확인 불가
- QA 계정 `mapqb3481` / `test123!@` → 배포 서버 `POST /auth/login` **401 `USER102`**(아이디/비밀번호 불일치). 계정이 현재 배포 DB 에 없다.
- 새 계정 생성은 운영 DB 쓰기라 수행하지 않았다(읽기 전용 지시).
- 단, A 에서 확인했듯 **배포 서버에 driver 엔드포인트 자체가 없어** 토큰이 있어도 `GET /drivers/me` 실응답은 얻을 수 없다. 본 보고서의 driver 스펙은 **HEAD 컨트롤러·DTO 소스 기준**이며 `_실서버 미검증_` 플래그를 유지한다.
- 보강 확인: 라이브 `/v3/api-docs` 로 "driver 표면이 없다" 는 사실은 **실서버에서 확인**됐다(소스 추정 아님).

---

## 4. 권장 수정안 (클라이언트)

### 선행 조건 (클라이언트 작업 전 리더 결정 필요)
1. 백엔드 운영에 **`TAXI_ENABLED=true` 설정 + HEAD 재배포** 요청. 이게 없으면 아래 모든 수정이 검증 불가다(A).
2. 재배포 후 기사 QA 계정 재생성 요청(문자 인증 흐름 포함) — I 해소용.

### D-1. (A 완화) 서버 플래그 상태를 진입 시 확인해 원인을 명시한다 — 권장
`/api/v1/config` 는 `permitAll` 이고 `{taxiEnabled: boolean}` 를 준다(`SecurityConfig` 의 permitAll 목록에 포함, 라이브 200 확인). 이걸 읽어 "서버에서 택시 기능이 꺼져 있음" 을 사용자/QA 에게 구분해 보여주면 A 같은 환경 문제를 1초에 식별할 수 있다.

```kotlin
// data/remote/dto/AuthDtos.kt (추가)
@Serializable data class AppConfigDto(val taxiEnabled: Boolean = true)

// data/remote/AuthApi.kt (추가 — 인증 불필요, baseRetrofit 쪽)
@GET("api/v1/config") suspend fun getAppConfig(): AppConfigDto

// domain/repository/DriverRepository.kt (신규 시그니처 — compose-builder 통지 대상)
suspend fun isTaxiServiceEnabled(): Boolean
```
`login()`·`restoreSession()` 의 `getMe()` 404 분기에서 이 값을 조회해 false 면
`"서버에서 기사 기능이 비활성화되어 있습니다(TAXI_ENABLED)"` 로 메시지를 분기한다.

### D-2. (A 2차 피해 차단) `restoreSession()` 의 404 처리를 완화한다 — 권장
현재 `getMe()` 404 하나로 `clearStoredSession()`(토큰 + 활성 partyId 전삭제)까지 간다. 서버 설정/배포 문제로 404 가 나는 동안 멀쩡한 세션이 파괴된다.

```kotlin
// RemoteDriverRepository.kt:194 부근
404 -> {
    // 핸들러 부재(서버 플래그 off)와 DRIVER_NOT_REGISTERED 를 구분한다.
    // 전자는 Spring 기본 에러 바디({timestamp,status,error,path})라 code 가 없다.
    val registeredElsewhere = e.serverErrorCode() == "DRIVER_NOT_REGISTERED"
    if (registeredElsewhere) clearStoredSession() else return RestoredSession(loggedIn = true, ongoingTrip = null)
}
```
`serverErrorCode()` 는 `serverMessage()` 와 같은 자리(`data/remote/` 의 에러 파서)에 `code` 를 꺼내는 함수를 추가. **crosscut 에이전트(29e)의 공통 에러 파서와 중복되지 않게 합의 후 한 곳에만 둔다.**
`login()`(:341-345)도 같은 기준으로 메시지를 분기한다.

### D-3. (C) 로그아웃 시 FCM 토큰 삭제 — 소규모·독립
```kotlin
// RemoteDriverRepository.logout() 내부, clearStoredSession() 직전
runCatching { driverApi.removeFcmToken() }   // 토큰이 유효한 동안 호출해야 한다
stopHeartbeat()
val refreshToken = tokenStore.refreshToken()
clearStoredSession()
...
lastFcmToken = null   // 다음 로그인에서 pending 토큰이 잘못 흘러가지 않게
```
주의: `removeFcmToken()` 은 `@CurrentDriver` 라 **토큰을 비우기 전에** 호출해야 한다. 실패는 무시(로그아웃은 항상 성공해야 한다). 기존 `RemoteDriverRepositorySessionTest` 의 Fake 가 `removeFcmToken()` 을 `unusedSession()` 으로 막아 두었으니 호출 기록용으로 바꿔야 한다.

### D-4. (D) KDoc 경로 주석 갱신 — 무해, 함께 처리
- `DriverApi.kt:15` → `../backend/.../driver/DriverController.java`
- `DriverDtos.kt:7` → `../backend/src/main/java/team/codingforest/moyeota/driver/dto`

### D-5. 손대지 말 것 (확인 완료, 변경 불필요)
- `RegisterDriverRequestDto` / `VehicleInfoDto` — 필드 7개 전부 일치(F). **건드리면 오히려 깨진다.**
- `RegisterFcmTokenRequestDto`, `registerFcmToken` 경로·메서드 — 불변(E).
- `DriverResultDto` — `name` 포함 5필드 일치(G).
- `RegisterVehicleRequestDto` — 3필드 일치.
- `driverAccountStatus()` 매핑 — `PENDING`/`VERIFIED` 불변.
- `DriverDtosTest.kt` — 현 서버 shape 과 일치. 유지.

### D-6. 다른 에이전트에게 넘길 것
| 항목 | 담당 | 내용 |
|---|---|---|
| `UserRegisterRequestDto` 에 `verificationToken` 추가 + `/auth/sms/send`·`/verify` 연동 | **auth (29a)** | driver `signUp()` 1단계가 여기 막혀 있다(B). driver 쪽은 `signUp()` 의 ① 단계 호출 시그니처만 따라 바뀐다 |
| 404 응답 바디 2종(공통 `{code,message}` vs Spring 기본 에러) 파싱 규약 | **crosscut (29e)** | D-2 의 `serverErrorCode()` 를 공통으로 둘지 결정 |
| `taxiEnabled=false` 로 함께 사라지는 dispatch/ride/location 엔드포인트 | **dispatch (29c)** | 원인은 A 와 동일. 개별 경로 수정으로 해결되지 않음 |
| `FcmCallNotifier` 가 플래그 off 로 미등록 → 콜 푸시 자체가 없다 | **matching/FCM (29d)** | 페이로드 분석 전에 이 사실을 전제로 둘 것 |

### 경계면 변경 통지 (compose-builder 대상)
현 분석 범위에서 **기존 `DriverRepository` 시그니처 변경은 없다.** D-1 을 채택할 경우에만 아래 1개가 추가된다.
```
+ suspend fun isTaxiServiceEnabled(): Boolean   // 서버 택시 기능 플래그. false 면 기사 API 전체 부재
```
`login()`·`restoreSession()`·`signUp()`·`registerFcmToken()` 의 시그니처와 반환 도메인 모델(`LoginResult`, `RestoredSession`)은 그대로다.

---

## 부록 — 추측과 사실 구분

**실서버에서 확인한 사실**
- `GET /api/v1/config` → `{"taxiEnabled":false}` (200)
- 라이브 `/v3/api-docs` 에 `/api/v1/drivers/*` 경로 0건, `DriverResult`/`RegisterDriverRequest` 스키마 0건
- 라이브에 `/api/v1/auth/sms/**` 없음 → 배포 빌드가 HEAD 가 아니다
- QA 계정 `mapqb3481` 로그인 401 `USER102`

**백엔드 소스에서 확인한 사실 (실서버 미검증)**
- 1절의 엔드포인트 8개 표 전체(경로·메서드·인증 주체·필드·검증·에러코드)
- `DriverFcmToken` 이 JPQL 프로젝션 전용이라는 점
- `fc8f6f3`·`0586b96` 가 FCM API 표면을 바꾸지 않았다는 점
- `UserRegisterRequest.verificationToken` 필수화

**추측 (검증 필요)**
- 플래그 off 상태에서 인증된 요청이 `/api/v1/drivers/me` 로 갈 때 **404 + Spring 기본 에러 바디** 가 온다는 것 — `SecurityConfig` 의 `dispatcherTypeMatchers(ERROR).permitAll()` + 핸들러 부재에서 유도한 결론이며 토큰 확보 실패로 실측하지 못했다. 401 로 떨어질 가능성도 배제 못 한다. **D-2 구현 전 토큰 확보 후 1회 실측 권장.**
- 배포 빌드의 정확한 커밋 — 09-19 ~ 10-11 범위로만 좁혔다.
