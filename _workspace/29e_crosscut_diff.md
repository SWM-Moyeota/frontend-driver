# 29e. 공통 규약 диff — 보안 설정 · 에러 응답 · 토큰/인증 · 공통 래퍼 · 배포 환경

> 범위: crosscut (SecurityConfig / GlobalExceptionHandler / 토큰 재발급 / 응답 래핑 / 프로필 차이 / 기사 앱 네트워크 계층)
> 기준: 기사 앱 동기화 지점 `b9a921d`(2026-09-09) ↔ 백엔드 HEAD `e729801`(2026-10-13)
> 실서버 검증: **완료** (`https://api.moyeota.p-e.kr` 에 읽기 전용 GET/OPTIONS 프로브. 서버 기동·종료 없음)

---

## 결론 먼저

**공통 규약(응답 포맷·토큰 규약·보안 필터)은 깨지지 않았다.** 전역 `ApiResponse<T>` 래퍼 도입 없음, `ErrorResponse{code,message}` 그대로, `/auth/reissue` 요청·응답 필드 그대로, permitAll 에서 빠진 경로 없음.

**그런데 앱 전체가 안 돈다.** 원인은 포맷이 아니라 **배포 프로필 플래그**다: 배포 서버는 `moyeota.taxi.enabled=false` 로 떠 있고, 기사/배차 컨트롤러 4개가 전부 `@ConditionalOnProperty(havingValue="true")` 로 묶여 있어 **빈 자체가 등록되지 않는다 → 기사 앱 엔드포인트 16개 중 13개가 404**. 실서버 프로브로 확인했다.

---

## 1. 현재 백엔드 공통 규약 (HEAD 기준 + 실서버 확인)

### 1-1. SecurityConfig — `user/auth/presentation/security/SecurityConfig.java`

> b9a921d 의 `user/interfaces/auth/SecurityConfig.java` 가 패키지 이동(rename). 클래스 구조·필터 순서 동일.

| 항목 | b9a921d | HEAD | 클라이언트 영향 |
|---|---|---|---|
| permitAll 경로 | `/api/v1/auth/**`, `/ws-chat/**`, `/health` | `/api/v1/auth/**`, **`/api/v1/config`**, `/ws-chat/**`, `/health`, **`/prometheus`**, **`/info`**, **`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`** | **추가만 됨. 빠진 경로 없음** → 401 로 새로 떨어지는 기사 앱 호출 **없음** |
| DispatcherType permitAll | `ERROR` | `ERROR`, **`ASYNC`** | SSE emitter complete 시 필터 재진입 방지. 기사 앱 영향 없음 |
| OPTIONS | `permitAll` | `permitAll` (동일) | 프로브 가능(아래 1-5에서 활용) |
| 나머지 | `anyRequest().authenticated()` | 동일 | — |
| 필터 | `JwtAuthenticationFilter` before `AuthorizationFilter` | 동일 | — |
| `@CurrentUser` 리졸버 | `CurrentUserArgumentResolver` (SecurityConfig 등록) | 동일 + `SpringDocUtils.addAnnotationsToIgnore` | 동작 동일 |
| `@CurrentDriver` 리졸버 | `driver/interfaces/auth/CurrentDriverArgumentResolver` | `driver/auth/CurrentDriverArgumentResolver` + 신규 `DriverWebConfig` 로 등록 | **동작 동일**: 토큰 userId → `drivers.findByUserId` → 없으면 `DRIVER_NOT_REGISTERED`(404) |
| CORS | 설정 없음 | **설정 없음** (`CorsConfigurationSource`·`addCorsMappings` 전무) | 네이티브 앱이라 무관 |

**JWT 추출 방식(변경 없음)** — `JwtAuthenticationFilter:36-49`
- 헤더: `Authorization: Bearer <access>` (접두사 `"Bearer "`, `substring` 후 `trim()`)
- 헤더 없음 → 익명 통과(보호는 URL 규칙이 담당)
- 헤더 있고 토큰 불량 → **필터 단계에서 즉시 401**, body 는 `JsonAuthenticationEntryPoint` 가 `{code,message}` 로 직접 작성
- access 토큰 claim: `sub = publicId(UUID)`, `tokenType = "ACCESS"`, jti 없음 / refresh 는 `tokenType="REFRESH"` + `jti` 필수

### 1-2. 공통 에러 응답 — 변경 없음

`common/exception/ErrorResponse.java`
```java
public record ErrorResponse(String code, String message) {}
```
**b9a921d 와 바이트 단위 동일.** 필드 추가/삭제/개명 전무.

`GlobalExceptionHandler` 변경점 (+28/-5):
| 변경 | 내용 | 영향 |
|---|---|---|
| 로그 레벨 분기 | 5xx→`warn`, 그 외→`info` | 없음(서버 내부) |
| **SSE 분기 신설** | `Accept` 가 `text/event-stream` **만** 이면(`application/json`·`*/*` 불포함) **body 없이** 상태코드 + `X-Error-Code` 헤더만 반환 | 기사 앱은 SSE 미사용(`PartySseController` 호출 없음) → **영향 없음**. 향후 SSE 붙이면 `{code,message}` 파싱 불가, `X-Error-Code` 헤더를 읽어야 함 |

HTTP 상태 매핑 (`ErrorCode` 인터페이스 `getCode/getMessage/getHttpStatus` — 변경 없음):
| 예외 | 상태 | code |
|---|---|---|
| `BusinessException` | `ErrorCode.getHttpStatus()` | `ErrorCode.getCode()` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |
| `MethodArgumentNotValidException` | 400 | `INVALID_REQUEST` (message = `"field: 사유, field2: 사유"`) |
| 핸들러 없음 / 필터 밖 | **Spring 기본** → `{timestamp,status,error,path}` | **`{code,message}` 아님** ⚠ |

code 체계: `user` 는 번호형(`USER001`…), `driver`/`common` 은 **enum 이름**(`DRIVER_NOT_REGISTERED`, `PARTY_NOT_FOUND`). `DriverErrorCode.getCode() { return name(); }` — b9a921d 와 동일(패키지만 `driver/domain/exception` → `driver/exception`).

`UserErrorCode` 변경: **기존 코드 전부 유지**, SMS 코드 9개 추가(`USER201`~`USER208`).

### 1-3. 토큰 재발급 규약 — 변경 없음

`POST /api/v1/auth/reissue` (permitAll, `@SecurityRequirements` 로 Bearer 요구 해제)

| 항목 | 값 | b9a921d 대비 |
|---|---|---|
| 요청 | `{ "refreshToken": "..." }` (`@NotBlank`) | 동일 |
| 응답 200 | `{ "accessToken": "...", "refreshToken": "..." }` | 동일 |
| 회전 방식 | `refreshTokens.consume(jti, now)` — **1회용 jti 소비**, 성공 시 새 jti 로 쌍 재발급 | **동일 로직, 라인 단위 일치** |
| 재사용 시 | 401 `USER001 TOKEN_INVALID` | 동일 |
| 만료 정책 | access `30m`, refresh `14d` (`JWT_ACCESS_VALIDITY`/`JWT_REFRESH_VALIDITY`) | 동일 |
| 로그아웃 | `POST /auth/logout` 204, 멱등(이미 무효여도 성공) | 동일 |

실서버 OpenAPI(`/v3/api-docs`) 로 스키마 확인:
`TokenRequest{refreshToken}` / `TokenResponse{accessToken,refreshToken}` / `UserLoginRequest{loginId,password}` / `UserResponse{uuid,name}` / `PhoneCheckRequest{phoneNumber}` / `PhoneCheckResponse{exists}` — **기사 앱 DTO 와 전부 일치**.

⚠ **문서-코드 불일치**: Swagger 설명은 refresh 재사용 시 `TOKEN_REUSED(USER004)` 라고 적었지만, `RefreshTokenService:39-45` 는 `TOKEN_INVALID(USER001)` 를 던진다. **실제 응답 우선** → 클라이언트는 `USER004` 로 분기하면 안 된다(기사 앱은 코드 분기 안 하므로 현재 무해).

### 1-4. 공통 응답 래퍼 — **도입되지 않음** (최우선 확인 항목)

- `grep -rn "ResponseBodyAdvice|class ApiResponse|record ApiResponse|CommonResponse|ApiResult|ResponseWrapper" src/main` → **0건**
- `@ControllerAdvice` 보유 클래스 4개: `GlobalExceptionHandler`, `JsonAuthenticationEntryPoint`, `chat/ChatExceptionHandler`, `chat/StompExceptionHandler` — 모두 **에러 전용**, 성공 응답 가공 없음
- 실서버 OpenAPI 의 성공 응답 스키마가 `TokenResponse`·`UserResponse` 등 **알몸 DTO** 로 노출 → 래핑 없음 확정
- 실호출 확인: `POST /auth/login` 실패 body = `{"code":"USER102","message":"아이디나 비밀번호가 다릅니다."}`

→ **기사 앱 전 DTO 안전. 이 축에서 "앱 전체가 깨지는" 변경은 없다.**

### 1-5. dev/prod 프로필 차이 — 여기서 앱이 멈춘다

`application-dev.yaml` 은 **신규 파일**(b9a921d 엔 없었음). `application-prod.yaml` 은 +55/-20.

| 설정 | base(`application.yaml`) | dev | prod | 클라이언트 영향 |
|---|---|---|---|---|
| **`moyeota.taxi.enabled`** | `${TAXI_ENABLED:true}` | (base 상속 → true) | **`${TAXI_ENABLED:false}`** | 🔴 **치명. 아래 상세** |
| `moyeota.sms.provider` | `${SMS_PROVIDER:log}` | (base → `log`) | `${SMS_PROVIDER:solapi}` | dev/로컬은 실제 문자 미발송(로그 출력), prod 는 Solapi 실발송 |
| `moyeota.sms.fixed-code` | 없음 | **`${SMS_FIXED_CODE:000000}`** | 없음 | dev 서버·로컬은 **인증번호 `000000` 고정** → QA 시 활용 가능 |
| `fcm.service-account-path` | `${FCM_SERVICE_ACCOUNT_PATH:}` | (base 상속) | **prod yaml 에서 블록 삭제** → base 상속 | base 가 항상 로드되므로 실질 변화 없음 |
| `management.server.port` | — | `9091` | `9091` | `/health`·`/prometheus`·`/info` 가 **8080 에 없음**(실측 404). 기사 앱은 미호출 → 무해 |
| `spring.data.redis.timeout` | **`1s`** (기존 60s) | 상속 | 상속 | Redis 지연 시 인증 캐시가 1초에 끊기고 DB 폴백(`PrincipalRedisCache` try/catch) → 인증 매달림 완화 |
| `naver.api` | base 에 존재 | 상속 | **prod 에서 삭제** | 기사 앱 미사용 |
| swagger/api-docs | 신규 노출 | 노출 | **노출(실측 200)** | 스펙 확인 경로 확보(좋은 소식) |

#### 🔴 `TAXI_ENABLED=false` — 기사 앱이 서버에 존재하지 않게 되는 메커니즘

`@ConditionalOnProperty(prefix="moyeota.taxi", name="enabled", havingValue="true", matchIfMissing=true)` 가 붙은 **컨트롤러**:

| 컨트롤러 | 담당 경로 |
|---|---|
| `driver/DriverController` | `/api/v1/drivers/**` 전부 |
| `dispatch/location/DriverLocationController` | `/api/v1/dispatch/online`, `/api/v1/dispatch/location` |
| `dispatch/call/DispatchCallController` | `/api/v1/dispatch/calls/**` |
| `dispatch/ride/RideController` | `/api/v1/dispatch/rides/**` |

추가로 `DispatchListener`, `MatchingSweeper`, `FcmCallNotifier` 도 같은 조건으로 비활성. 반대로 `havingValue="false"` 인 `ChatOnlyCompletionPolicy`·`CompletedPartySweeper` 가 활성 — 즉 **"택시 없이 매칭+채팅만" 이 의도된 1차 배포 모드**다.

근거 3종:
1. `loadtest/README.md:4` — *"D0~D6 — 1차 배포(택시 꺼짐, `TAXI_ENABLED=false`): 매칭 + 채팅만. **지금 쓰는 것**"*
2. `src/integrationTest/.../TaxiDisabledContextTest.java` — `taxi.enabled=false` 에서 위 4개 컨트롤러 빈이 **비어 있어야 한다**고 명시적으로 단정
3. **실서버 실측** (아래)

```
$ curl https://api.moyeota.p-e.kr/api/v1/config
{"taxiEnabled":false}                              ← 배포 서버 확정
```

OPTIONS(permitAll 구간이라 토큰 없이 핸들러 존재 여부를 판별) 프로브:
```
/api/v1/drivers                       404      /api/v1/dispatch/calls/1/accept    404
/api/v1/drivers/me                    404      /api/v1/dispatch/calls/1/status    404
/api/v1/drivers/verify                404      /api/v1/dispatch/calls/1           404
/api/v1/drivers/vehicle               404      /api/v1/dispatch/rides/1/arrive    404
/api/v1/drivers/call                  404      /api/v1/dispatch/rides/1/complete  404
/api/v1/drivers/fcm-token             404      ─────────────────────────────────────
/api/v1/dispatch/online               404      /api/v1/auth/phone/check   200 allow: POST
/api/v1/dispatch/location             404      /api/v1/local/users/info   200 allow: GET
                                               /api/v1/matching/rooms/1   200 allow: GET
```

배포 서버 OpenAPI 전체 경로 목록(37개)에 **`/drivers`·`/dispatch`·`/calls`·`/rides` 가 단 하나도 없다.**

#### 배포 서버 FCM 서비스 계정 — **설정되어 있다**(강한 간접 근거)

추론 사슬:
1. `infra/ec2/load-env.sh:33` — SSM 파라미터 로드 후 **무조건** `FCM_SERVICE_ACCOUNT_PATH=/etc/moyeota/fcm-account.json` 을 env 파일에 append (파라미터 존재 여부와 무관)
2. `fcm.service-account-path` 는 base `application.yaml:56` 에 있고 base 는 항상 로드 → 배포 서버에서 이 값은 **항상 비어 있지 않다**
3. `FirebaseConfig` 는 `@ConditionalOnExpression("!'${fcm.service-account-path:}'.isEmpty()")` → 배포 서버에서 **항상 활성**
4. 활성 상태에서 `new FileInputStream(path)` 가 파일을 못 찾으면 `FileNotFoundException` → **빈 생성 실패 → 컨텍스트 기동 실패**
5. 그런데 배포 서버는 정상 응답 중(`/api/v1/config` 200, `/v3/api-docs` 200)

⇒ `/etc/moyeota/fcm-account.json` 이 실재한다 = **SSM `/moyeota/prod/FCM_SERVICE_ACCOUNT_JSON` 이 설정돼 있고 `FirebaseApp`/`FirebaseMessaging` 빈이 살아 있다.**

단, **`FcmCallNotifier` 는 이중 조건**(`taxi.enabled=true` **AND** fcm 경로 비어있지 않음)이라 **prod 에서 비활성**. 대신 `@Primary` 없는 `LoggingCallNotifier` 가 `CallNotifier` 로 주입되어 서버 로그만 찍는다.
→ **설령 기사 앱 엔드포인트가 살아나도, `TAXI_ENABLED=true` 가 아니면 기사 단말로 `CALL_OPENED`/`CALL_CLOSED` 푸시가 나가지 않는다.**
(`FcmChatNotifier`·`FcmPassengerNotifier` 는 taxi 조건 없음 → prod 활성. 즉 "FCM 자체는 살아 있고 콜 알림만 꺼져 있다".)

---

## 2. 기사 앱 클라이언트 현재 상태

| 파일 | 라인 | 내용 | 규약 대비 |
|---|---|---|---|
| `data/.../remote/NetworkModule.kt` | 28 | `BASE_URL = "https://api.moyeota.p-e.kr/"` | 배포 서버 직결 |
| | 30-33 | `Json { ignoreUnknownKeys=true; coerceInputValues=true }` | 서버 필드 추가에 내성 ✅ |
| | 41-53 | 클라이언트 2종: `baseRetrofit`(AuthApi 전용, 인터셉터 없음) / `authedRetrofit`(Driver·Dispatch·PartyMembers) | reissue 재귀 차단 ✅ |
| | 56-63 | `reissueBlocking` — `POST /auth/reissue`, 빈 문자열 방어 | 서버 규약 일치 ✅ |
| | 69-71 | `connect 10s / read 20s / write 20s` | 콜드 스타트 7~8초 대응. 적절 |
| | 72-74 | `HttpLoggingInterceptor(Level.BODY)` **무조건** | ⚠ 릴리즈에서도 토큰·전화번호 평문 로깅 |
| `data/.../remote/auth/AuthHeaderInterceptor.kt` | 10, 28 | `/api/v1/auth/` 프리픽스는 Bearer 미부착 | permitAll + 필터 조기 401 회피 ✅ (신설 `/auth/sms/**` 도 자동 커버) |
| | 29 | 기존 `Authorization` 헤더 보존 | ✅ |
| | 31 | 토큰 없으면 헤더 없이 전송 → 서버 401 `USER005` | ✅ |
| `data/.../remote/auth/TokenAuthenticator.kt` | 28 | Bearer 안 실린 요청의 401 은 그대로 올림 | reissue 재귀 차단 ✅ |
| | 29, 74 | `MAX_RETRY = 1`, `priorResponse` 카운트 | ✅ |
| | 31-49 | `synchronized` + "다른 스레드가 이미 갱신" 분기 | **회전(1회용 jti) 방식과 정확히 맞물림** ✅ |
| | 40-42 | reissue 실패 → `tokens.clear()` | 서버 "그 외 401 은 재로그인" 지침과 일치 ✅ |
| `data/.../remote/AuthMappers.kt` | 64-69 | `ErrorResponseDto(code, message)` 둘 다 nullable + 기본 null | **서버 `ErrorResponse` 와 일치** ✅ |
| | 71-83 | `serverMessage()` — 파싱 실패 시 null, 1회성 스트림 명시 | ✅ |
| `data/.../remote/AuthApi.kt` | 18-19 | 주석: *"permitAll … `POST /api/v1/local/users`"* | ⚠ **주석 오류**. 현 SecurityConfig permitAll 에 `/api/v1/local/**` 없음 |
| | 49 | 주석: *"phone/check 실서버 미검증(구동 중인 서버는 구빌드 — 404)"* | ⚠ **주석 낡음**. 실측 `OPTIONS /auth/phone/check` → **200**. 이제 살아 있음 |
| | 59-62 | `getMyInfo` 가 `baseRetrofit`(=Authenticator 없음)에서 명시 헤더로 호출 | ⚠ 401 자동 재발급 없음(로그인 직후 1회만 쓰므로 현재 무해) |
| `data/.../repository/RemoteDriverRepository.kt` | 337-341 | `driverApi.getMe()` 의 **404 → "기사로 가입되지 않은 계정입니다"** | 🔴 404 를 `DRIVER_NOT_REGISTERED` 로 단정 — taxiEnabled=false 404 와 구분 불가 |
| | 812-822 | `callFailure`: 409/401/403만 분기, 나머지는 `"…서버 오류(404)"` | 🔴 원인 불명 메시지 |
| | — | `/api/v1/config` 호출 **없음** | 🔴 서버가 알려주는 "택시 꺼짐"을 앱이 모른다 |

---

## 3. 불일치 목록

| # | 심각도 | 증상 | 원인 | 고칠 지점 |
|---|---|---|---|---|
| **C1** | 🔴 **깨짐 (앱 전체)** | 로그인은 성공하나 직후 `GET /drivers/me` 가 404 → **"기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요."** 로 전원 차단. 회원가입(`POST /drivers`)도 404 → `"회원가입 실패 — …: 서버 오류(404)"`. 영업 시작/콜/운행 전 기능 404 | 배포 서버 `moyeota.taxi.enabled=false` → 기사·배차 컨트롤러 빈 미등록. **클라이언트 코드 문제 아님** | 서버 운영(`/moyeota/prod/TAXI_ENABLED=true`) 또는 dev 서버 전환. 앱 쪽은 진단성 확보 + 404 해석 분리 |
| **C2** | 🔴 **깨짐(오진)** | C1 상황에서 사용자에게 "가입되지 않은 계정" 이라고 **거짓 안내**. 재가입 시도 → 또 404 → 무한 루프 | `RemoteDriverRepository.kt:338-340` 이 404 를 `DRIVER_NOT_REGISTERED` 로만 해석. 컨테이너 404 는 body 가 `{timestamp,status,error,path}` 라 `serverMessage()`=null 인데 그 구분을 안 함 | `RemoteDriverRepository.kt:337-341` |
| **C3** | 🟡 동작변경 | `TAXI_ENABLED=true` 로 켠 뒤에도 콜 푸시가 단말에 안 올 수 있음 | `FcmCallNotifier` 가 taxi 조건 + fcm 경로 조건 **둘 다** 필요. 서버 설정 문제이므로 앱은 폴링 보강 필요 | 서버 운영 확인 + 앱: `callFeed`/상태 폴링 폴백 유지 |
| **C4** | 🟡 동작변경 | 서버가 "택시 꺼짐"을 `GET /api/v1/config` 로 공개하는데 앱이 안 읽어 원인 불명 에러만 노출 | `/api/v1/config` 미연동 (permitAll, 토큰 불필요, 실측 200) | `AuthApi`/신규 `ConfigApi` + 스플래시·로그인 전 1회 호출 |
| **C5** | 🟢 무해(주석) | `AuthApi.kt:49` "phone/check 실서버 미검증 — 구빌드 404" | 배포 서버가 갱신돼 **이제 200**. 주석만 낡음 | `AuthApi.kt:49` |
| **C6** | 🟢 무해(주석) | `AuthApi.kt:18-19` "permitAll 에 `POST /api/v1/local/users`" | 현 SecurityConfig permitAll 에 `/local/**` 없음 | `AuthApi.kt:18-19` |
| **C7** | 🟢 무해(위생) | 릴리즈 빌드에서 access/refresh 토큰·전화번호가 logcat 평문 | `NetworkModule.kt:72-74` 가 `Level.BODY` 무조건 | `NetworkModule.kt:72-74` |
| **C8** | 🟢 무해(선제) | 향후 SSE 연동 시 에러 body 가 비어 `serverMessage()`=null | `GlobalExceptionHandler` SSE 분기 → `X-Error-Code` 헤더만 | 지금은 조치 불필요, 메모 |
| — | ✅ 일치 | `ErrorResponse{code,message}` / 전역 래퍼 없음 / reissue 요청·응답 / 회전 방식 / access 30m·refresh 14d / permitAll 축소 없음 / `@CurrentUser`·`@CurrentDriver` 동작 / Bearer 헤더 형식 | — | 변경 불필요 |

**permitAll 에서 빠진 경로로 인해 401 이 되는 기사 앱 호출: 없음.** (permitAll 은 추가만 됨)

---

## 4. 권장 수정안

### 4-0. 선행 — 서버 운영 결정 (앱 수정보다 먼저)
기사 앱은 `TAXI_ENABLED=false` 서버에서 **구조적으로 동작 불가**. 둘 중 하나가 필요하다.
- (A) `/moyeota/prod/TAXI_ENABLED=true` 로 올리고 재배포 → prod 에서 기사 기능 개방
- (B) 기사 앱 `BASE_URL` 을 **dev 서버**로 돌림 (dev 는 base 상속으로 `taxi.enabled=true`, 게다가 `SMS_FIXED_CODE=000000` 으로 문자 인증 QA 가 쉬움)

→ **(B) 를 권한다.** prod 1차 배포는 "택시 없는 매칭+채팅" 이 의도된 릴리즈 모드이므로 기사 앱을 위해 prod 플래그를 뒤집으면 승객 앱 동작(정원 충족 → 채팅만)이 바뀐다.
단, dev 서버 URL 은 백엔드 팀 확인 필요(레포에 하드코딩 없음) — **리더 확인 필요 항목**.

### 4-1. C4 — 서버 설정 선조회 (최우선 앱 수정)
```kotlin
// data/.../remote/ConfigApi.kt (신규)
interface ConfigApi {
    /** permitAll — 토큰 없이 호출. taxiEnabled=false 면 기사 엔드포인트가 서버에 존재하지 않는다 */
    @GET("api/v1/config")
    suspend fun appConfig(): AppConfigResponseDto
}

// data/.../remote/dto/ConfigDtos.kt
@Serializable
data class AppConfigResponseDto(val taxiEnabled: Boolean = true)   // 구버전 서버(필드 없음) → true 로 낙관
```
`NetworkModule` 에는 **`baseRetrofit`** 으로 등록(토큰 불필요, Authenticator 불필요).
`DriverRepository` 에 `suspend fun isTaxiEnabled(): Boolean` 추가 → 스플래시/로그인 진입 전 1회 조회, `false` 면 "지금은 기사 서비스가 열려 있지 않습니다(서버 설정)" 전면 안내. compose-builder 에 시그니처 통지 필요.

### 4-2. C2 — 404 의미 분리
```kotlin
// RemoteDriverRepository.kt:337-341
val driver = try {
    driverApi.getMe()
} catch (e: retrofit2.HttpException) {
    if (e.code() == 404) {
        // 서버 ErrorResponse 가 있으면 진짜 기사 미등록, 없으면 핸들러 자체 부재(TAXI_ENABLED=false)
        val code = e.serverErrorCode()               // AuthMappers 에 신설
        throw if (code == "DRIVER_NOT_REGISTERED")
            IllegalStateException("기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요.", e)
        else
            IllegalStateException("서버에 기사 기능이 열려 있지 않습니다(TAXI_ENABLED). 운영팀 확인이 필요합니다.", e)
    }
    throw IllegalStateException("기사 정보 조회 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
}
```
```kotlin
// AuthMappers.kt — ErrorResponseDto 재사용, code 도 꺼낸다 (errorBody 1회성이므로 message 와 함께 한 번에)
fun retrofit2.HttpException.serverErrorCode(): String? =
    runCatching {
        response()?.errorBody()?.string()
            ?.let { errorJson.decodeFromString(ErrorResponseDto.serializer(), it) }
            ?.code?.takeIf { it.isNotBlank() }
    }.getOrNull()
```
⚠ `errorBody()` 는 1회성 스트림이다. `serverMessage()` 와 `serverErrorCode()` 를 같은 예외에 **연달아 쓰면 두 번째가 null** 이 된다 → `ErrorResponseDto?` 를 한 번에 돌려주는 `serverError()` 로 합치는 편이 안전하다. 테스트는 `data/src/test/.../AuthMappersTest.kt` 에 `{timestamp,status,error,path}` 바디(=code null) 케이스와 `{code:"DRIVER_NOT_REGISTERED"}` 케이스 둘 다 추가.

### 4-3. C7 — 로깅 레벨 축소
```kotlin
// NetworkModule.kt:72-74
.addInterceptor(HttpLoggingInterceptor().apply {
    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
    redactHeader("Authorization")
})
```
`data` 모듈에 `BuildConfig` 가 없으면 `buildConfig = true` 활성 또는 `NetworkModule.enableBodyLogging` 플래그를 `AppContainer` 에서 주입.

### 4-4. C5·C6 — 주석 정정 (코드 변경 없음)
- `AuthApi.kt:49` → `"실서버 확인 완료(2026-10-13): OPTIONS 200. permitAll"`
- `AuthApi.kt:18-19` → permitAll 목록을 현행으로: `/api/v1/auth/**`, `/api/v1/config`, `/ws-chat/**`, swagger 경로. `/api/v1/local/**` 은 **인증 필요**

### 4-5. 변경 불필요(명시적 확인 완료)
`ErrorResponseDto` · `TokenAuthenticator` · `AuthHeaderInterceptor` · `TokenRequestDto`/`TokenResponseDto` · 타임아웃 값 · `Json` 설정 — 서버 규약과 일치. **손대지 말 것.**

---

## 부록 — 실서버 프로브 기록 (2026-10-13, `https://api.moyeota.p-e.kr`)

| 요청 | 상태 | 본문 |
|---|---|---|
| `GET /api/v1/config` | 200 | `{"taxiEnabled":false}` |
| `GET /api/v1/drivers/me` (토큰 없음) | 401 | `{"code":"USER005","message":"로그인이 필요합니다."}` |
| `GET /api/v1/drivers/me` (불량 토큰) | 401 | `{"code":"USER001","message":"유효하지 않은 토큰입니다."}` |
| `POST /api/v1/auth/login` (틀린 자격) | 401 | `{"code":"USER102","message":"아이디나 비밀번호가 다릅니다."}` |
| `POST /api/v1/auth/sms/send` | 404 | `{"timestamp":…,"status":404,"error":"Not Found","path":…}` ← 배포 빌드가 HEAD 보다 구버전 |
| `GET /health`, `/prometheus`, `/info` | 404 | 관리 포트 9091 로 이동 |
| `GET /v3/api-docs` | 200 | 경로 37개, 기사/배차 **0개** |
| `OPTIONS` × 16 | — | §1-5 표 |

**배포 빌드 시점 추정**: `/api/v1/config` 존재(최초 추가 `98cf2cd`, 2026-09-29) ∧ `/auth/sms/**` 부재(최초 추가 `94bd54d`, 2026-10-11) → **2026-09-29 ~ 2026-10-10 사이 빌드**. 즉 백엔드 HEAD(10-13) 가 아직 배포되지 않았다.
따라서 **문자 인증 가입 흐름(29a 범위)은 현재 배포 서버에 존재하지 않는다** — 앱에 구현해도 재배포 전까지 404. 29a 에 전달 필요.

**플래그**
- ✅ **실서버 검증 완료** (읽기 전용 GET/OPTIONS 만. 서버 기동·종료·쓰기 요청 없음)
- ⚠ **배포 서버는 백엔드 HEAD 가 아님** — 재배포 후 표면이 바뀔 수 있음
- ⚠ **`TAXI_ENABLED` 는 SSM 파라미터 스토어 값이라 레포에서 확정 불가** — `{"taxiEnabled":false}` 실측으로 확정
- ⚠ **FCM 서비스 계정 설정 여부는 "그렇다" 쪽 간접 추론** (기동 성공 = 파일 존재). SSM 직접 조회로는 미확인
- ⚠ **dev 서버 URL 미확인** — 레포에 하드코딩 없음, 백엔드 팀 확인 필요
