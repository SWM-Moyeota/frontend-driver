# 11 api-integrator — 인가(JWT) 연동 + MVP 간소 가입

**검증 상태**: 스펙은 전부 백엔드 소스 기준 확정. 실서버(로컬 기동 중)는 **읽기 전용으로만** 확인 —
보호 GET 무토큰 401 `{"code":"USER005","message":"로그인이 필요합니다."}` 실측, `/api/v1/auth/login` 라우트 존재(405 on GET) 실측.
상태 변경 POST(register/login 포함)는 프로브하지 않았으므로 **가입·로그인 end-to-end 는 실서버 미검증** — qa-verifier 실기 검증 필요.

빌드/테스트: `./gradlew :app:assembleDebug :data:testDebugUnitTest` 통과 (테스트 24 = 기존 dispatch 11 + auth 매퍼 10 + TokenStore 3).
**이전에 깨져 있던 :data 컴파일(RemoteDriverRepository 의 login/signUp/logout 미구현) 복구 완료.**

## 1. 연동 엔드포인트 표

| 메서드 + 경로 | 인증 | Repository 메서드 | 비고 |
|---|---|---|---|
| POST `/api/v1/auth/register` | permitAll | `signUp` 1단계 | 201 `UserResponse{uuid,name}` — **토큰·userId 없음**. 409 USER101(아이디 중복)이면 로그인 단계로 진행 |
| POST `/api/v1/auth/login` | permitAll | `login`, `signUp` 2단계 | 200 `{accessToken,refreshToken}` / 401 USER102 |
| POST `/api/v1/auth/reissue` | permitAll | (TokenAuthenticator 내부) | 401 응답 시 1회 자동 재발급. 리프레시 회전 — 쌍으로 교체 |
| POST `/api/v1/auth/logout` | permitAll | `logout` | body `{refreshToken}` → 204, 멱등 |
| GET `/api/v1/local/users/info` | Bearer | `login` (이름 조회) | 200 `UserResponse{uuid,name}` — 실패해도 이름 "기사" 폴백으로 진행 |
| POST `/api/v1/drivers` | Bearer | `signUp` 3단계 | body `{userId, qualificationNumber, bankName, accountNumber}` → `DriverResult`. 409 DRIVER_ALREADY_REGISTERED 면 GET 회수 |
| POST `/api/v1/drivers/verify` | Bearer **(토큰 기반 @CurrentDriver — 경로 변경됨)** | `signUp` 4단계, `checkQualifications` | 204 / 404 DRIVER_NOT_REGISTERED / 409 DRIVER_NOT_PENDING(=이미 검증, 성공 취급) |
| POST `/api/v1/drivers/vehicle` | Bearer **(토큰 기반 — 경로 변경됨)** | `signUp` 5단계, `registerVehicle` | body `{seats, plateNumber, type}` → 204 |
| GET `/api/v1/drivers/users/{userId}` | Bearer | `login` (상태·driverId), resolveDriverId | 404 DRIVER_NOT_REGISTERED |

기존 dispatch·driver 콜/영업 API 는 변경 없음 — 단 이제 전부 Bearer 필수가 됐고, 인증 클라이언트(자동 부착)로 나가므로 코드 변경 불요.

### ⚠ 백엔드 경로 변경 반영 (09 산출물에서 달라진 것)
- `POST /drivers/{driverId}/verify` → **`POST /drivers/verify`** (토큰에서 driverId 해석)
- `POST /drivers/{driverId}/vehicle` → **`POST /drivers/vehicle`**
- `DriverStatus` enum 은 여전히 `PENDING | VERIFIED` (변경 없음)

## 2. 서버 DTO 실측 shape (소스 기준)

- `UserRegisterRequest` (register 요청): `{loginId, password, name, birthDate: Instant(ISO-8601 문자열), phoneNumber, gender: "MALE"|"FEMALE", email}`
  - 검증: loginId `^[a-z][a-z0-9_]{3,19}$` / password 8~64 + 영문·숫자·특수문자 각 1+ / phone `^01[016-9]-?\d{3,4}-?\d{4}$` / birthDate @Past / email @Email
- `UserResponse` (register 201 · users/info 200): `{uuid: UUID(publicId), name: String|null}` — **내부 Long userId 는 없음**. 가입 직후엔 nickname 미설정이라 register 응답 name=null
- `TokenResponse` (login·reissue 200): `{accessToken, refreshToken}`
- `TokenRequest` (reissue·logout 요청): `{refreshToken}`
- JWT 클레임 (JwtProvider): `sub` = **publicId UUID** (Long userId 아님), `tokenType` = ACCESS|REFRESH, refresh 에만 `jti`
- 4xx 공통: `{code, message}` (한국어 message) — 401 무토큰 실측 `{"code":"USER005","message":"로그인이 필요합니다."}`
- `DriverResult`: `{id, userId, status: "PENDING"|"VERIFIED", callEnabled}` (변경 없음)

## 3. 토큰 플로우

```
[로그인/가입]                       [보호 API 요청]                    [401 발생 시]
authApi.login ──토큰쌍──▶ TokenStore   AuthHeaderInterceptor          TokenAuthenticator
 (기본 클라이언트,        (인메모리,     ├ /api/v1/auth/* → 미부착       ├ Bearer 없던 요청 → 포기(전파)
  인터셉터 없음)          synchronized)  ├ Authorization 있음 → 통과     ├ 재시도 1회 초과 → 포기
                                       └ 그 외 → Bearer 부착           ├ (잠금) 토큰이 이미 갱신됨 → 그걸로 재시도
                                                                      └ reissue 동기 호출(기본 클라이언트)
                                                                         ├ 성공 → TokenStore.update → 재시도
                                                                         └ 실패 → TokenStore.clear (재로그인 필요)
```

- 클라이언트 2종: **기본**(logging만 — AuthApi 전용, reissue 재귀 원천 차단) / **인증**(driverApi·dispatchApi — 인터셉터+Authenticator)
- `getMyInfo` 는 AuthApi(기본 클라이언트)에 있지만 Authorization 헤더를 명시 파라미터로 싣는다
- 재발급 경합: Authenticator 전체 synchronized + "잠금 획득 시 토큰이 이미 바뀌었으면 재발급 생략" (리프레시 회전 대응, 승객 앱과 동일 패턴)
- MVP: 토큰은 인메모리(TokenStore) — 앱 재실행 시 재로그인

## 4. Repository 동작 (compose-builder 경계면)

시그니처는 리더 확정 그대로 (변경 없음):
- `login(loginId, password): LoginResult` — 401 → `IllegalStateException("아이디 또는 비밀번호가 올바르지 않습니다.")`, 기사 미등록(404) → `IllegalStateException("기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요.")`. 성공 시 driverId 캐시 + `PENDING→PENDING_REVIEW / VERIFIED→APPROVED`
- `signUp(form): LoginResult` — register→login→기사등록→verify→차량등록. 각 단계 실패는 `"회원가입 실패 — {단계명}: {서버 한국어 message}"` 예외. 성공 시 `LoginResult(APPROVED, "기사")`
  - 관용: register 409(기존 계정)→로그인 진행 / 기사등록 409→기존 id 회수 / verify 409(이미 검증)→성공 취급
- `logout()` — 로컬 클리어(토큰·driverId 캐시·remoteTrip) **먼저**, 서버 logout 은 실패해도 무시

## 5. MVP 기본값 (RemoteDriverRepository / AuthMappers.MvpProfileDefaults)

| 필드 | 값 | 서버 검증 대응 |
|---|---|---|
| name | `"기사"` | @NotBlank, ≤20자 |
| birthDate | `"1990-01-01T00:00:00Z"` | Instant @Past |
| phoneNumber | `"010-0000-0000"` | `^01[016-9]-?\d{3,4}-?\d{4}$` |
| gender | `"MALE"` | Gender enum |
| email | `"{loginId}@moyeota.local"` | @Email (loginId 가 영소문자·숫자·_ 뿐이라 안전, 계정별 유일) |
| bankName / accountNumber | `"국민"` / `"000000000000"` | @NotBlank (화면 입력 없음) |

기본값이 패턴을 통과하는지 단위 테스트로 고정(`AuthMappersTest`).

## 6. ⚠ 미해결 — 백엔드 갭: 내부 Long userId 미노출 (리더 에스컬레이션 권장)

**클라이언트가 자기 userId(Long)를 알 방법이 전혀 없다.** login 응답은 토큰 쌍뿐, JWT sub 는 publicId(UUID), register·users/info 응답도 uuid·name 뿐. 그런데 `POST /drivers` 는 body 로 Long userId 를 요구하고 `GET /drivers/users/{userId}` 도 Long 경로 변수다. (승객 앱도 동일 한계로 `FIXED_MEMBER_ID` 고정값 사용 — frontend/data SessionManager 참조.)

**채택한 임시 대응**: `DEFAULT_USER_ID = 1L` 고정 (기사 앱 계정이 백엔드의 **첫 유저**인 개발 환경 전제).
- 어긋나면: signUp 은 verify(토큰 기반) 단계가 404 로 어긋남을 드러내고 명시 예외를 던진다. login 은 "가입되지 않은 계정" 예외가 된다.
- 부작용: 고정값이 타 유저의 id 면 `POST /drivers` 가 **그 유저를 기사로 등록**해 버린다(서버가 body userId 와 토큰 유저의 일치를 검증하지 않음). 개발 DB 한정 허용.

**백엔드 요청 사항**: `POST /drivers` 를 verify/vehicle 처럼 토큰 기반(@CurrentUser)으로 바꾸거나, users/info 류 응답에 내부 userId 를 포함해 달라. (DriverController 상단 TODO "토큰을 통해 유저 아이디 가져오기"가 그 작업.)

기타 주의:
- register 응답의 name 은 null(닉네임 미설정) — 표시명은 users/info 조회 or "기사" 폴백
- `checkQualifications`/`registerVehicle`(D02~D04 레거시 플로우)도 새 verify/vehicle 경로로 갱신됨 — 단 이 플로우는 로그인(토큰) 없이 타면 이제 401 로 실패하고 더미로 강등된다 (MVP 플로우는 signUp 사용이므로 영향 없음)
- 앱 재실행 시 토큰 소실(인메모리) → 재로그인 필요. login 이 driverId 를 다시 캐시한다

## 7. 변경/추가 파일

- `data/src/main/kotlin/com/moyeota/driver/data/remote/dto/AuthDtos.kt` (신규 — 서버 shape 5종)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/AuthApi.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/AuthMappers.kt` (신규 — 상태 매핑·MVP 기본값·에러 body 파서)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/auth/TokenStore.kt` (신규 — 인메모리)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/auth/AuthHeaderInterceptor.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/auth/TokenAuthenticator.kt` (신규 — 401 → reissue 1회)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/NetworkModule.kt` (수정 — 클라이언트 2종, tokenStore 보유, authApi() 추가)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/DriverApi.kt` (수정 — verify/vehicle 토큰 기반 경로로)
- `data/src/main/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepository.kt` (수정 — login/signUp/logout 구현, 캐시 정리)
- `app/src/main/kotlin/com/moyeota/driver/app/AppContainer.kt` (수정 — authApi·tokenStore 주입)
- `data/src/test/kotlin/com/moyeota/driver/data/remote/AuthMappersTest.kt` (신규 10)
- `data/src/test/kotlin/com/moyeota/driver/data/remote/TokenStoreTest.kt` (신규 3)

**domain·presentation·Routes 무변경.**
