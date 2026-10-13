# 29a. user/auth + 문자(SMS) 인증 — 백엔드 변경분 대조

- 범위: `user/**` 패키지 전체(auth·local·profile·verification) + SMS 인증
- 백엔드 기준: `/Users/sungyoon/Desktop/moyeota/backend` HEAD `e729801` (2026-10-13), 직전 동기화 지점 `b9a921d`
- 클라이언트 기준: `/Users/sungyoon/Desktop/moyeota/frontend-driver` (feature/app-icon)
- 작성: 읽기 전용 분석. **코드 변경 없음.**

## 표기 규칙

| 표기 | 의미 |
|---|---|
| **[확인]** | 소스를 직접 읽었거나 실제 HTTP 호출로 확인 — 확인 방법을 함께 적음 |
| **[추정]** | 소스에서 논리적으로 따라오지만 실측하지 못함 — 확인 방법을 함께 적음 |

---

## ⚠️ 먼저 읽을 것 — 두 개의 "현재"가 다르다

이 보고서는 **두 기준**을 구분한다. 섞으면 판정이 뒤집힌다.

| 기준 | 상태 |
|---|---|
| **백엔드 소스 HEAD (`e729801`)** | 가입에 문자 인증 **필수**. 클라이언트는 `verificationToken` 을 보내지 않음 → **가입 깨짐** |
| **배포 서버 (`https://api.moyeota.p-e.kr`)** | 아직 **구버전**. 문자 인증 엔드포인트 없음(404), register 가 `verificationToken` 을 요구하지 않음 → auth 단계는 **지금 성공함** |

**[확인]** 배포 서버가 구버전이라는 증거 (2026-10-13 03:36 KST 실측):

```
POST /api/v1/auth/sms/send    → 404 {"status":404,"error":"Not Found","path":"/api/v1/auth/sms/send"}
POST /api/v1/auth/sms/verify  → 404 (동일)
POST /api/v1/auth/register {} → 400 {"code":"INVALID_REQUEST","message":
    "email: 이메일은 필수입니다., gender: ..., name: ..., birthDate: ..., loginId: ...,
     nickname: ..., password: ..., phoneNumber: 전화번호는 필수입니다."}
    ← 필수 필드 목록에 verificationToken 이 없다
POST /api/v1/auth/nickname/check → 200 {"exists":false}   ← fec46d5(09-29)는 들어가 있음
GET  /api/v1/config              → 200 {"taxiEnabled":false}
```

→ **[추정]** 배포본은 `fec46d5`(2026-09-29) 이후 ~ `94bd54d`(2026-10-11) 이전 구간.
확인 방법: EC2 에 배포된 `app.jar` 의 build-info(`4381a75` 이후 포함) 또는 CodeDeploy 배포 이력 확인.

**[확인]** 배포 서버는 `taxiEnabled=false` 이고, `DriverController`·`DispatchCallController`·`RideController`·`DriverLocationController` 는 전부
`@ConditionalOnProperty(prefix="moyeota.taxi", name="enabled", havingValue="true", matchIfMissing=true)` 다
(`backend/src/main/java/.../driver/DriverController.java:27` 등).
→ 배포 서버에서 기사·dispatch API 전체가 **빈 등록조차 안 됨**. 실측:

```
POST /api/v1/drivers         (유효 토큰) → 404 Spring 기본 바디(핸들러 없음)
POST /api/v1/drivers/verify  (유효 토큰) → 404
GET  /api/v1/drivers/me      (유효 토큰) → 404
```

→ 즉 **auth 범위를 고쳐도 배포 서버에서는 기사 가입이 완결되지 않는다**. 이건 29b(driver) 범위이지만
`login()`·`signUp()` 이 직접 호출하는 경로라 여기 명시한다. (`TAXI_ENABLED=true` 가 켜져야 함)

---

## 1. 현재 백엔드 API 표 (소스 HEAD `e729801` 기준)

인증 주체 판정 근거 **[확인]**: `SecurityConfig.java:48`
```java
.requestMatchers("/api/v1/auth/**", "/api/v1/config", "/ws-chat/**", "/health", "/prometheus", "/info").permitAll()
.anyRequest().authenticated()
```
`/api/v1/auth/sms/**` 는 `/api/v1/auth/**` 접두 매칭에 포함 → **permitAll**.

### 1-1. AuthController — `/api/v1/auth` (permitAll)

`backend/.../user/auth/presentation/AuthController.java`

| 메서드·경로 | 인증 | 요청 | 응답 | 에러코드 |
|---|---|---|---|---|
| `POST /register` | permitAll | `UserRegisterRequest` (아래) | **201** `UserResponse{uuid:UUID, name:String}` | 400 `INVALID_REQUEST`, `USER105`, `USER107`, **`USER207`(신규)** / 409 `USER101`,`USER104`,`USER108` |
| `POST /login` | permitAll | `{loginId, password}` (둘 다 `@NotBlank`) | **200** `{accessToken, refreshToken}` | 401 `USER102` |
| `POST /reissue` | permitAll | `{refreshToken}` `@NotBlank` | **200** `{accessToken, refreshToken}` | 401 `USER001`,`USER002`,`USER004` |
| `POST /logout` | permitAll | `{refreshToken}` `@NotBlank` | **204** (멱등) | — |
| `POST /phone/check` | permitAll | `{phoneNumber}` `@NotBlank` | **200** `{exists:boolean}` | 400 `USER105`, `INVALID_REQUEST` |
| `POST /nickname/check` | permitAll | `{nickname}` `@NotBlank` | **200** `{exists:boolean}` | 400 `USER107` |

`UserRegisterRequest` 전 필드 **[확인]** (`user/local/dto/UserRegisterRequest.java`):

| 필드 | 타입 | 검증 |
|---|---|---|
| `loginId` | String | `@NotBlank` + `@Pattern ^[a-z][a-z0-9_]{3,19}$` |
| `password` | String | `@NotBlank` + `@Size(8,64)` + 영문·숫자·특수문자 각 1개 이상 |
| `nickname` | String | `@NotBlank` + `@Size(2,10)` · 서버 `Nickname` record 가 `^[가-힣a-zA-Z0-9]{2,10}$` 재검증(strip 후) |
| `name` | String | `@NotBlank` + `@Size(max=20)` |
| `birthDate` | Instant | `@NotNull` + `@Past` (ISO-8601 문자열) |
| `phoneNumber` | String | `@NotBlank` + `@Pattern ^01[016-9]-?\d{3,4}-?\d{4}$` · 서버 `PhoneNumber` record 가 하이픈 제거 후 `^01[016-9]\d{7,8}$` 재검증 |
| `gender` | enum | `@NotNull` · `MALE`\|`FEMALE` |
| `email` | String | `@NotBlank` + `@Email` + `@Size(max=100)` |
| **`verificationToken`** | String | **`@NotBlank("휴대폰 인증이 필요합니다.")` — `db285f9`(2026-10-11)에서 신규 추가.** `POST /api/v1/auth/sms/verify` 응답값 |

### 1-2. SmsVerificationController — `/api/v1/auth/sms` (permitAll) · **신규 (`94bd54d`)**

`backend/.../user/verification/presentation/SmsVerificationController.java`

| 메서드·경로 | 요청 | 응답 | 에러코드 |
|---|---|---|---|
| `POST /send` | `{phoneNumber}` — `@NotBlank` + `@Pattern ^01[016-9]-?\d{3,4}-?\d{4}$` | **204 No Content** (바디 없음) | 400 `USER105`/`INVALID_REQUEST` · **409 `USER104`(이미 가입된 전화번호 — 문자 비용 쓰기 전 거절)** · 429 `USER202`(1분 쿨다운), `USER203`(1일 5회 초과) · 502 `USER201`(발송 실패) · 503 `USER208`(Redis 장애) |
| `POST /verify` | `{phoneNumber, code}` — `phoneNumber @NotBlank`(패턴 검증 **없음**, 도메인에서 `USER105`), `code @NotBlank @Pattern ^\d{6}$` | **200** `{verificationToken: String}` (UUID 문자열) | 400 `USER204`(만료/미발송), `USER205`(불일치), `INVALID_REQUEST` · 429 `USER206`(5회 초과 — 코드 폐기) · 503 `USER208` |

SMS 서비스 정책 **[확인]** (`SmsVerificationService.java` 상수):

| 항목 | 값 | 클라이언트 영향 |
|---|---|---|
`CODE_TTL` | **3분** | 기존 D02 화면의 `OTP_VALID_SECONDS = 180` 과 일치 ✔
`COOLDOWN` | **60초** | 재전송 버튼 60초 비활성 필요
`DAILY_LIMIT` | **5회/24h** | 초과 시 `USER203`
`MAX_ATTEMPTS` | **5회** | 5회째 불일치면 코드 폐기 + `USER206` → 재발송 유도
**`VERIFIED_TTL`** | **10분** | **`verify` → `register` 사이가 10분 넘으면 `USER207`** ← 설계에 직결
`consume` 시점 | register **성공 후** | 닉네임 중복 등으로 register 가 실패하면 토큰이 살아 있어 **재인증 없이 재시도 가능**

dev 고정 인증번호 **[확인]** (`143ca4e`):
- `application-dev.yaml:38-40` → `moyeota.sms.fixed-code: ${SMS_FIXED_CODE:000000}`
- `application.yaml:103` → `moyeota.sms.provider: ${SMS_PROVIDER:log}`
- `SmsVerificationService` 생성자 `Assert`: `fixed-code` 는 **`provider=log` 에서만** 허용, 숫자 6자리만 허용 (아니면 기동 실패)
- `application-prod.yaml:54-55` → `provider: ${SMS_PROVIDER:solapi}`, `fixed-code` **미설정(빈 값)**
- ⇒ **dev 프로필 = 인증번호 `000000` 고정 / prod 프로필 = SOLAPI 실제 문자, 고정값 없음**

**[추정] 배포 서버(`api.moyeota.p-e.kr`)는 prod 프로필일 가능성이 높다.**
근거: `/api/v1/config` 가 `taxiEnabled=false` 를 반환하는데, 이 기본값 `false` 는 `application-prod.yaml:48`(`${TAXI_ENABLED:false}`)에만 있고 dev 는 `application.yaml:96`(`${TAXI_ENABLED:true}`)을 그대로 쓴다.
→ **그렇다면 배포 서버에서는 `000000` 이 통하지 않고 실제 문자를 받아야 한다.**
확인 방법: EC2 인스턴스 태그 `Env`(`infra/ec2/load-env.sh` 가 이 태그로 `SPRING_PROFILES_ACTIVE` 를 정한다) 또는 SSM `/moyeota/{dev|prod}/SMS_PROVIDER`·`TAXI_ENABLED` 파라미터 조회. 배포 후에는 `sms/send` → `sms/verify {code:"000000"}` 가 200 인지로도 판별 가능.

### 1-3. LocalUserController — `/api/v1/local`

| 메서드·경로 | 인증 | 응답 | 에러 |
|---|---|---|---|
| `GET /users/info` | `@CurrentUser Long userId` | **200** `UserResponse{uuid, name}` | 401 `USER005` / 404 `USER103` |

**[확인]** `LocalUserService.getProfile`: 닉네임이 null 이면 `Masking.name(실명)`(홍길동 → 홍*동)으로 폴백. `908abb6` 에서 추가.
**[확인]** `dab0947` 이 `POST /api/v1/local/users`(옛 회원가입)를 삭제. 삭제 전에도 시큐리티 화이트리스트에 없어 호출 불가였음 (실측: 배포 서버에서 `401 USER005`).

### 1-4. UserController — `/api/v1/users` (승객 앱용, **기사 앱 미사용**)

`PATCH /me`(닉네임·이미지, 204 / 400 `USER107` / 409 `USER108`), `PUT /me/fcm-token`(204 / 400 `USER106`), `DELETE /me/fcm-token`(204). 전부 `@CurrentUser`.
기사 앱 FCM 은 `PUT /api/v1/drivers/fcm-token` 이 별개 — 혼용 금지.

### 1-5. 에러 바디 shape **[확인]**

`GlobalExceptionHandler` + `ErrorResponse(String code, String message)`:
- 비즈니스 예외 → `{"code":"USER105","message":"전화번호 형식이 올바르지 않습니다."}`
- Bean Validation 실패 → `{"code":"INVALID_REQUEST","message":"필드1: 메시지, 필드2: 메시지"}` (필드별 메시지 콤마 연결 — 사용자에게 그대로 노출하면 지저분함)
- **핸들러 없음(404)** → Spring 기본 바디 `{"timestamp","status","error","path"}` — `code`·`message` **없음**

전체 에러코드 **[확인]** (`UserErrorCode.java`):

| 코드 | HTTP | 메시지 |
|---|---|---|
`USER001`/`002`/`003`/`004`/`005` | 401 | 토큰 무효/만료/용도불일치/재사용/로그인 필요 |
`USER101` | 409 | 이미 존재하는 아이디입니다. |
`USER102` | 401 | 아이디나 비밀번호가 다릅니다. |
`USER103` | 404 | 존재하지 않는 사용자입니다. |
`USER104` | 409 | 이미 가입된 전화번호입니다. |
`USER105` | 400 | 전화번호 형식이 올바르지 않습니다. |
`USER106` | 400 | FCM 토큰이 비어 있습니다. |
`USER107` | 400 | 닉네임은 2~10자의 한글, 영문, 숫자만 가능합니다. |
`USER108` | 409 | 이미 사용 중인 닉네임입니다. |
**`USER201`** | 502 | 인증번호 발송에 실패했습니다. 잠시 후 다시 시도해 주세요. |
**`USER202`** | 429 | 인증번호는 1분에 한 번만 요청할 수 있습니다. |
**`USER203`** | 429 | 오늘 인증번호 요청 횟수를 초과했습니다. |
**`USER204`** | 400 | 인증번호가 만료되었습니다. 다시 요청해 주세요. |
**`USER205`** | 400 | 인증번호가 일치하지 않습니다. |
**`USER206`** | 429 | 입력 횟수를 초과했습니다. 인증번호를 다시 요청해 주세요. |
**`USER207`** | 400 | 휴대폰 인증이 필요합니다. |
**`USER208`** | 503 | 지금은 인증을 진행할 수 없습니다. 잠시 후 다시 시도해 주세요. |

(`USER2xx` 전체가 `449066a` 신규)

### 1-6. 배포 서버 실측 응답 (auth 범위) **[확인]**

테스트 계정 1개 생성 — 허용 범위 내. `loginId=drvapichk01`, `phoneNumber=010-0000-0001`, `nickname=apichk0001`, `uuid=01a13168-3d90-7ab1-bffb-3ad7b2a8a747`.
(삭제 엔드포인트가 없어 잔존함. 정리 필요하면 DB 수동 삭제.)

```
POST /auth/phone/check {"phoneNumber":"010-7777-6531"}  → 200 {"exists":false}
POST /auth/phone/check {"phoneNumber":"12345"}          → 400 {"code":"USER105","message":"전화번호 형식이 올바르지 않습니다."}
POST /auth/phone/check {"phoneNumber":""}               → 400 {"code":"INVALID_REQUEST","message":"phoneNumber: must not be blank"}
POST /auth/phone/check {"phoneNumber":"01000000001"}    → 200 {"exists":true}       ← 하이픈 없이도 OK
POST /auth/nickname/check {"nickname":"apichk0001"}     → 200 {"exists":true}
POST /auth/register  (클라 payload 그대로)               → 201 {"uuid":"01a13168-...","name":"apichk0001"}
POST /auth/register  (loginId 중복)                      → 409 {"code":"USER101",...}
POST /auth/register  (phoneNumber 중복)                  → 409 {"code":"USER104",...}
POST /auth/login                                        → 200 {"accessToken","refreshToken"}  (키 2개뿐)
POST /auth/login     (비밀번호 틀림)                      → 401 {"code":"USER102",...}
GET  /local/users/info (Bearer)                         → 200 {"uuid":"01a13168-...","name":"apichk0001"}
GET  /local/users/info (토큰 없음)                        → 401 {"code":"USER005",...}
```

→ **배포 서버의 auth 응답 shape 은 클라이언트 DTO 와 100% 일치.** 불일치는 "필수 필드가 하나 더 생겼다"는 **스펙 변경**에서만 온다.

---

## 2. 기사 앱 클라이언트 현재 상태

| 파일 | 라인 | 내용 | 평가 |
|---|---|---|---|
`data/.../remote/AuthApi.kt` | 29-30 | `POST api/v1/auth/register` → `UserResponseDto` | 경로 ✔ / **요청 바디에 `verificationToken` 없음** |
| | 33-34 | `POST api/v1/auth/login` | ✔ |
| | 40-41 | `POST api/v1/auth/reissue` (`Call<T>`, Authenticator 용) | ✔ |
| | 44-45 | `POST api/v1/auth/logout` | ✔ |
| | 51-52 | `POST api/v1/auth/phone/check` | ✔ (실측 200) |
| | 59-62 | `GET api/v1/local/users/info` + 명시 `Authorization` 헤더 | ✔ |
| | — | **`/auth/sms/send`·`/auth/sms/verify`·`/auth/nickname/check` 메서드 없음** | 누락 |
| | 18-19 | KDoc 이 `POST /api/v1/local/users` 를 "시큐리티 화이트리스트"로 적음 | **사실 아님** + 해당 엔드포인트 `dab0947`로 삭제됨 (문서 오류) |
`data/.../remote/dto/AuthDtos.kt` | 22-31 | `UserRegisterRequestDto(loginId, password, nickname, name, birthDate, phoneNumber, gender, email)` — 8필드 | **`verificationToken` 누락 → HEAD 에서 400** |
| | 39-42 | `UserResponseDto(uuid?, name?)` | ✔ |
| | 46-49 / 53-55 / 59-62 | `UserLoginRequestDto` / `TokenRequestDto` / `TokenResponseDto` | ✔ |
| | 70-72 / 76-78 | `PhoneCheckRequestDto` / `PhoneCheckResponseDto(exists=false)` | ✔ |
| | — | `SmsSend*`·`SmsVerify*`·`NicknameCheck*` DTO 없음 | 누락 |
`data/.../remote/AuthMappers.kt` | 23-30 | `normalizePhoneNumber` — 11자리 `010-1234-5678` / 10자리 `010-123-4567` | ✔ 서버 패턴 통과 |
| | 37-47 | `DriverSignUpForm.toRegisterRequest()` | **`verificationToken` 을 넣을 자리가 없음** |
| | 50-62 | `MvpProfileDefaults` (`BIRTH_DATE=1990-01-01T00:00:00Z`, `GENDER=MALE`, `EMAIL_DOMAIN=moyeota.local`) | ✔ 실측 201 통과 |
| | 66-69, 77-83 | `ErrorResponseDto(code?, message?)` + `HttpException.serverMessage()` | **`message` 만 쓰고 `code` 를 안 읽음** → `USER2xx` 분기 불가 |
`data/.../repository/RemoteDriverRepository.kt` | 326-350 | `login()` — `/auth/login` → `tokenStore.update` → `driverApi.getMe()` → 폴백 `authApi.getMyInfo()` | auth 부분 ✔ / `getMe()` 404 는 29b |
| | 379-388 | `isPhoneRegistered()` — `/auth/phone/check`, 실패는 예외 전파 | ✔ |
| | 400-450 | `signUp()` 4단계: register → login → `POST /drivers` → `/drivers/verify` | **1단계가 HEAD 에서 400** |
| | 402-408 | register 의 **409 를 전부 무시**하고 로그인으로 진행 | 아이디 중복(USER101)/전화 중복(USER104)/닉네임 중복(USER108) 구분 없음 → 다음 단계에서 401 로 터짐 |
| | 456-468 | `logout()` — 로컬 세션 먼저 정리 후 서버 호출, 실패 무시 | ✔ |
| | 486-489 | `requestOtp`/`verifyOtp` → **`fallback`(더미) 위임**, "백엔드 미구현" 주석 | **백엔드 구현됨 → 주석·구현 모두 낡음** |
| | 472-483 | `step(name){}` — 실패를 `"회원가입 실패 — {단계}: {사유}"` 로 변환 | ✔ 구조 재사용 가능 |
`domain/.../repository/DriverRepository.kt` | 37 | `suspend fun login(loginId, password): LoginResult` | ✔ |
| | 44 | `suspend fun isPhoneRegistered(phoneNumber): Boolean` | ✔ (KDoc 의 "GET /users/exists 미구현" 문구는 낡음) |
| | 47 | `suspend fun signUp(form: DriverSignUpForm): LoginResult` | 시그니처 유지 가능, `form` 확장 필요 |
| | 50-51 | `suspend fun requestOtp(phone: String)` / `verifyOtp(phone, code): Boolean` | **`verifyOtp` 반환형이 `Boolean` — 서버는 `verificationToken:String` 을 준다. 경계면 변경 필요** |
`domain/.../model/DriverModels.kt` | 16-26 | `DriverSignUpForm(phoneNumber, name, nickname, loginId, password, vehicleType, seats, plateNumber, qualificationNumber)` | **`verificationToken` 필드 없음** |
`presentation/.../auth/SignUpScreen.kt` | 50-56 | 3단계 흐름 주석: ①휴대폰 확인 ②프로필 ③택시 정보 | 인증 단계 **없음** |
| | 55 | `// 휴대폰 OTP 인증은 이번 범위 아님` | 이제 범위임 |
| | 58-82 | 로컬 검증 정규식 — `PHONE_REGEX`,`LOGIN_ID_REGEX`,`NICKNAME_REGEX`,`isValidPassword` | ✔ 서버 규칙과 일치 |
| | 85-89 | `formatPhoneNumber` — `AuthMappers.normalizePhoneNumber` 와 중복 구현(동일 로직) | 무해하지만 이중 관리 |
| | 104-120 | `checkPhone()` → `repository.isPhoneRegistered` | ✔ |
| | 126-141 | `signUp()` → `repository.signUp(form)` | 토큰 전달 경로 없음 |
| | 179, 211-249, 251-280 | `step` 1..3 내부 상태 머신 + `StepProgressBar(step, total=3)` | **4단계로 확장 필요** |
`presentation/.../auth/PhoneVerifyScreen.kt` | 56-57 | D02 화면 (피그마 2511:452) | **이미 완성된 OTP UI 가 있다** |
| | 73-91 | `requestOtp` → `repository.requestOtp` | 더미 연결 |
| | 101-119 | `verify` → `repository.verifyOtp(phone, code): Boolean` | **토큰을 못 받는 구조** |
| | 121-123 | `OTP_VALID_SECONDS = 180` | ✔ 서버 `CODE_TTL = 3분` 과 일치 |
`presentation/.../core/Routes.kt` | 8 | `AUTH_PHONE = "auth/phone" // D02 (MVP 플로우에서 미사용, 화면 보존)` | **어디서도 navigate 하지 않음 — 고아 라우트** (grep 결과 선언+composable 2곳뿐) |
`data/.../remote/NetworkModule.kt` | 28 | `BASE_URL = "https://api.moyeota.p-e.kr/"` | 배포 서버 고정 |
`data/.../repository/DummyDriverRepository.kt` | 98-105 | `requestOtp{delay}` / `verifyOtp{code.length==6}` | 유지 필요(인터페이스 변경 시 함께 수정) |

---

## 3. 불일치 목록

| # | 심각도 | 항목 | 증상 | 고칠 지점 |
|---|---|---|---|---|
**A1** | 🔴 **깨짐** | `register` 에 `verificationToken` 필수 (`db285f9`) | HEAD 배포 즉시 `POST /auth/register` → **400 `INVALID_REQUEST` "verificationToken: 휴대폰 인증이 필요합니다."** → `signUp()` 1단계에서 `step("계정 생성")` 가 예외 → D05 가입 화면에 "회원가입 실패 — 계정 생성: …" 배너. **가입 전면 불가** | `AuthDtos.kt:22-31`, `AuthMappers.kt:37-47`, `DriverModels.kt:16-26` |
**A2** | 🔴 **깨짐** | 문자 인증 2단계 엔드포인트 미구현 (`94bd54d`) | `verificationToken` 을 받을 방법이 클라이언트에 없음. A1 과 한 묶음 | `AuthApi.kt`(+2 메서드), `AuthDtos.kt`(+3 DTO), `DriverRepository`, `RemoteDriverRepository:486-489` |
**A3** | 🔴 **깨짐** | `verifyOtp(phone, code): Boolean` 반환형 | 서버는 `{verificationToken}` 을 주는데 도메인 계약이 `Boolean` → 토큰이 버려진다 | `DriverRepository.kt:51`, `RemoteDriverRepository.kt:489`, `DummyDriverRepository.kt:102`, `PhoneVerifyScreen.kt:101-119` |
**A4** | 🔴 **깨짐** | 가입 화면에 인증 단계 부재 | 3단계 흐름(①번호 ②프로필 ③택시)에 인증번호 입력 지점이 없다. D02 화면은 만들어져 있으나 **고아 라우트** | `SignUpScreen.kt:179·203·211-280`, `Routes.kt:8`, `AuthGraph.kt:44-50` |
**A5** | 🟠 **동작변경** | `VERIFIED_TTL = 10분` | 인증을 ②단계에서 하고 ③④단계 입력이 10분을 넘기면 제출 시 **400 `USER207`** → 사용자는 영문도 모르고 실패. 순서 설계 이슈 | 흐름 설계 (아래 4-5) |
**A6** | 🟠 **동작변경** | `sms/send` 가 기가입 번호를 **409 `USER104`** 로 선차단 | `phone/check` 와 **중복 판정**. 둘의 결과가 어긋나면(경합) 화면이 모순된 안내를 함 | `SignUpScreen` 1단계 설계 |
**A7** | 🟠 **동작변경** | `register` 409 를 무조건 무시 (`RemoteDriverRepository.kt:402-408`) | 아이디 중복(`USER101`)인데 남의 `loginId` + 내 비밀번호로 로그인 시도 → **401 `USER102`** → "회원가입 실패 — 자동 로그인: 아이디나 비밀번호가 다릅니다." 라는 **엉뚱한 메시지**. 전화번호 중복(`USER104`)·닉네임 중복(`USER108`)도 같은 처리 | `RemoteDriverRepository.kt:402-408` |
**A8** | 🟠 **동작변경** | `serverMessage()` 가 `code` 를 안 읽음 (`AuthMappers.kt:77-83`) | `USER202`(쿨다운)·`USER204`(만료)·`USER206`(횟수초과)를 구분할 수 없어 재발송 버튼·타이머 재설정 같은 **복구 UX 를 못 만듦**. `INVALID_REQUEST` 의 `"필드: 메시지, 필드: 메시지"` 가 그대로 배너에 노출됨 | `AuthMappers.kt:66-83` |
**A9** | 🟡 무해 | `/auth/nickname/check` 미연동 | 닉네임 중복을 제출 시점에야 알게 됨(409 `USER108`) → ③단계까지 입력한 뒤 실패. 서버엔 실시간 조회 API 가 있음 | `AuthApi.kt`, `DriverRepository` |
**A10** | 🟡 무해 | `AuthApi.kt:18-19` KDoc | 삭제된 `POST /api/v1/local/users`(`dab0947`)를 화이트리스트로 기술. 삭제 전에도 호출 불가였음(실측 401). 클라이언트는 호출하지 않으므로 **문서만 틀림** | `AuthApi.kt:18-19` 주석 |
**A11** | 🟡 무해 | `DriverRepository.kt:42` KDoc | "백엔드 조회 API(GET /users/exists) 미구현" — 실제로는 `POST /auth/phone/check` 로 연동 완료됨 | 주석 |
**A12** | 🟡 무해 | `formatPhoneNumber` 중복 (`SignUpScreen.kt:85-89` vs `AuthMappers.kt:23-30`) | 현재 로직 동일해 증상 없음. 한쪽만 바뀌면 "조회는 미등록·가입은 409" 오탐 재발 | presentation → domain/data 로 일원화 |
**A13** | 🟡 무해 | `getMyInfo().name` 의 마스킹 폴백 (`908abb6`) | 닉네임이 null 인 계정은 `홍*동` 형태가 내려온다. 기사 앱은 가입 시 닉네임을 항상 넣으므로 실제 영향 없음 | — |
**A14** | ⚫ 범위외(29b) | `TAXI_ENABLED=false` → `/api/v1/drivers/**` 전부 404 | auth 를 고쳐도 `signUp()` 3·4단계와 `login()` 의 `getMe()` 가 404. **배포 서버에서 가입·로그인 완결 불가** | 백엔드 설정(`TAXI_ENABLED=true`) — 클라이언트로 해결 불가 |

**불일치 아닌 것(확인 완료)**: `login`/`reissue`/`logout`/`phone/check` 경로·요청·응답, `TokenResponse` 2필드, `UserResponse(uuid,name)`, `MvpProfileDefaults` 값, 에러 바디 `{code,message}`, `normalizePhoneNumber` 결과 — 전부 실측 일치.

---

## 4. 권장 수정안 (코드 스케치)

> 전부 **auth 범위**. A14(기사 엔드포인트 404)는 백엔드 설정이라 클라이언트에서 못 고친다.

### 4-1. DTO 추가 — `data/src/main/kotlin/.../remote/dto/AuthDtos.kt`

```kotlin
/** POST /api/v1/auth/sms/send 요청 — SmsSendRequest. 204 No Content(바디 없음) */
@Serializable
data class SmsSendRequestDto(val phoneNumber: String)

/** POST /api/v1/auth/sms/verify 요청 — SmsVerifyRequest. code 는 숫자 6자리(@Pattern ^\d{6}$) */
@Serializable
data class SmsVerifyRequestDto(val phoneNumber: String, val code: String)

/** POST /api/v1/auth/sms/verify 응답 — SmsVerifyResponse. UUID 문자열, 유효 10분 */
@Serializable
data class SmsVerifyResponseDto(val verificationToken: String = "")

/** POST /api/v1/auth/nickname/check — NicknameCheckRequest/Response */
@Serializable
data class NicknameCheckRequestDto(val nickname: String)

@Serializable
data class NicknameCheckResponseDto(val exists: Boolean = false)
```

그리고 **기존** `UserRegisterRequestDto` 에 필드 1개 추가 (9번째, 서버 record 순서와 동일):

```kotlin
    val email: String,
    val verificationToken: String,   // ← 추가. POST /auth/sms/verify 응답값. @NotBlank
```

### 4-2. API 메서드 추가 — `AuthApi.kt`

```kotlin
    /** 204 No Content. 409 USER104(이미 가입된 번호) / 429 USER202(1분 쿨다운)·USER203(1일 5회) / 502 USER201 / 503 USER208 */
    @POST("api/v1/auth/sms/send")
    suspend fun sendSmsCode(@Body body: SmsSendRequestDto)

    /** 200 {verificationToken} (유효 10분). 400 USER204(만료)·USER205(불일치) / 429 USER206(5회 초과) / 503 USER208 */
    @POST("api/v1/auth/sms/verify")
    suspend fun verifySmsCode(@Body body: SmsVerifyRequestDto): SmsVerifyResponseDto

    /** 200 {exists}. 400 USER107(형식 위반) */
    @POST("api/v1/auth/nickname/check")
    suspend fun checkNickname(@Body body: NicknameCheckRequestDto): NicknameCheckResponseDto
```

`AuthApi.kt:18-19` 주석을 실제 `SecurityConfig` 기준으로 교체 (**A10**):
`/api/v1/auth/**`(sms 포함), `/api/v1/config`, `/ws-chat/**`, `/health`, `/prometheus`, `/info` 만 permitAll.

### 4-3. 에러코드 분기 — `AuthMappers.kt`

`ErrorResponseDto` 는 이미 `code` 를 갖고 있으니 **읽기만 추가** (**A8**):

```kotlin
/** 서버 ErrorResponse 를 code+message 로 꺼낸다. errorBody 는 1회성 스트림이라 호출당 1회만 유효하다 */
fun retrofit2.HttpException.serverError(): ErrorResponseDto? =
    runCatching {
        response()?.errorBody()?.string()
            ?.let { errorJson.decodeFromString(ErrorResponseDto.serializer(), it) }
    }.getOrNull()

/** UserErrorCode → 화면에 쓸 한국어. INVALID_REQUEST 의 "필드: 메시지" 나열은 가린다 */
fun smsErrorMessage(code: String?, serverMessage: String?): String = when (code) {
    "USER104" -> "이미 가입된 번호예요 · 로그인해 주세요"
    "USER202" -> "인증번호는 1분에 한 번만 요청할 수 있어요"
    "USER203" -> "오늘 인증 요청 횟수를 모두 썼어요 · 내일 다시 시도해 주세요"
    "USER204" -> "인증번호가 만료됐어요 · 다시 받아 주세요"
    "USER205" -> "인증번호가 달라요 · 다시 확인해 주세요"
    "USER206" -> "입력 횟수를 초과했어요 · 인증번호를 다시 받아 주세요"
    "USER207" -> "휴대폰 인증이 만료됐어요 · 번호 인증을 다시 해 주세요"
    "USER208", "USER201" -> "지금은 인증할 수 없어요 · 잠시 후 다시 시도해 주세요"
    "USER101" -> "이미 사용 중인 아이디예요"
    "USER108" -> "이미 사용 중인 닉네임이에요"
    "INVALID_REQUEST", null -> "입력값을 다시 확인해 주세요"
    else -> serverMessage ?: "요청을 처리하지 못했어요"
}
```

`serverMessage()` 는 다른 호출부가 쓰므로 **남겨 두고** `serverError()` 를 병행 추가한다(중복 정의 아님 — 역할 분리).
주의: `errorBody()` 는 1회성 스트림이라 한 예외에서 두 함수를 **같이 호출하면 안 된다**.

### 4-4. 도메인 계약 변경 — compose-builder 통지 대상

`domain/.../model/DriverModels.kt`:
```kotlin
data class DriverSignUpForm(
    val phoneNumber: String,
    val verificationToken: String,   // ← 추가: POST /auth/sms/verify 응답 (유효 10분)
    val name: String,
    // ... 이하 동일
)
```

`domain/.../repository/DriverRepository.kt` — **기존 2개 교체**:
```kotlin
-   suspend fun requestOtp(phone: String)
-   suspend fun verifyOtp(phone: String, code: String): Boolean
+   /** 인증번호 발송. 204. 실패는 한국어 메시지 예외(IllegalStateException) — 쿨다운·기가입·한도 포함 */
+   suspend fun requestPhoneVerification(phoneNumber: String)
+
+   /** 인증번호 확인 → verificationToken(유효 10분). 불일치·만료·횟수초과는 한국어 메시지 예외 */
+   suspend fun verifyPhoneCode(phoneNumber: String, code: String): String
+
+   /** 닉네임 중복 실시간 조회 — true 면 사용 중 */
+   suspend fun isNicknameTaken(nickname: String): Boolean
```

`RemoteDriverRepository`:
```kotlin
override suspend fun requestPhoneVerification(phoneNumber: String) =
    try {
        authApi.sendSmsCode(SmsSendRequestDto(normalizePhoneNumber(phoneNumber)))
    } catch (e: CancellationException) { throw e }
    catch (e: retrofit2.HttpException) {
        val err = e.serverError()
        throw IllegalStateException(smsErrorMessage(err?.code, err?.message), e)
    } catch (e: Exception) {
        throw IllegalStateException("인증번호를 보내지 못했어요 · 다시 시도해 주세요", e)
    }

override suspend fun verifyPhoneCode(phoneNumber: String, code: String): String =
    try {
        authApi.verifySmsCode(SmsVerifyRequestDto(normalizePhoneNumber(phoneNumber), code))
            .verificationToken
            .ifBlank { throw IllegalStateException("인증 토큰을 받지 못했어요 · 다시 시도해 주세요") }
    } catch (e: CancellationException) { throw e }
    catch (e: retrofit2.HttpException) {
        val err = e.serverError()
        throw IllegalStateException(smsErrorMessage(err?.code, err?.message), e)
    }
```

`signUp()` 1단계를 **409 무시 → 코드별 분기**로 교체 (**A7**) + `USER207` 복구 신호:
```kotlin
step("계정 생성") {
    try {
        authApi.register(form.toRegisterRequest())   // verificationToken 포함
    } catch (e: retrofit2.HttpException) {
        val err = e.serverError()
        when (err?.code) {
            // 전화번호 중복만 "기존 계정" 으로 보고 로그인으로 진행한다 (phone-first 전제)
            "USER104" -> Unit
            // 인증 만료 — 화면이 인증 단계로 되돌릴 수 있게 전용 예외로 올린다
            "USER207" -> throw PhoneVerificationExpiredException()
            else -> throw IllegalStateException(smsErrorMessage(err?.code, err?.message), e)
        }
    }
}
```
> `USER101`(아이디 중복)·`USER108`(닉네임 중복)을 더는 삼키지 않는다 — 현재는 남의 아이디로 로그인 시도해 401 이 나고 메시지가 엉뚱하다.
> `PhoneVerificationExpiredException` 은 `domain/model` 에 두어 presentation 이 "인증 단계로 복귀"를 판단하게 한다 (`DriverLocationUnavailableException` 과 같은 패턴).

`DummyDriverRepository` 도 **같은 인터페이스**를 구현해야 컴파일이 깨지지 않는다:
```kotlin
override suspend fun requestPhoneVerification(phoneNumber: String) { delay(300) }
override suspend fun verifyPhoneCode(phoneNumber: String, code: String): String {
    delay(300)
    if (code.length != 6) throw IllegalStateException("인증번호가 달라요 · 다시 확인해 주세요")
    return "dummy-verification-token"
}
override suspend fun isNicknameTaken(nickname: String): Boolean { delay(200); return false }
```

### 4-5. 화면 흐름 — `VERIFIED_TTL 10분` 때문에 순서가 중요하다 (**A5**)

인증 토큰은 **10분**만 산다. 기존 D02 위치(번호 입력 직후)에 인증을 두면 ③프로필·④택시 입력이 10분을 넘기는 순간 제출이 `USER207` 로 실패한다.

**권장안 (1순위) — 인증을 제출 직전으로**
```
① 번호 입력      → phone/check  (기가입이면 로그인 유도)
② 회원 프로필     → (선택) nickname/check 실시간 중복 확인
③ 택시 정보
④ 휴대폰 인증     → sms/send → sms/verify → 토큰 확보 → 즉시 signUp() 제출
```
- 토큰 발급 ~ register 사이가 수초 → `USER207` 사실상 불가
- D02 `PhoneVerifyScreen`(피그마 2511:452)을 **내부 step 4** 로 재사용 — `Routes.AUTH_PHONE` 고아 라우트 해소
- `StepProgressBar(step, total = 3)` → `total = 4`

**대안 (2순위) — 피그마 순서(인증을 번호 직후) 유지**
그대로 두되 `PhoneVerificationExpiredException` 을 받아 **step 2(인증)로 되돌리고** "인증이 만료됐어요 · 다시 인증해 주세요" 배너를 띄운다. 제출 성공 전까지 입력값은 모두 보존.

**어느 쪽이든 공통으로 필요한 것**
- `sms/send` 재전송 버튼: **60초 쿨다운** 비활성 (`USER202` 방어). `PhoneVerifyScreen` 의 180초 타이머는 `CODE_TTL` 용이라 **별개 타이머**가 필요하다
- 인증번호 입력은 **숫자 6자리 고정** (`@Pattern ^\d{6}$` — 클라이언트에서 선검증해 왕복 절약)
- `USER206`(5회 초과) → 입력 비활성 + "다시 받기" 만 활성
- ①단계는 `phone/check` 를 유지(즉시·무과금). `sms/send` 의 409 `USER104` 도 같은 "이미 가입된 번호" 안내로 수렴시켜 **A6** 의 모순 안내를 막는다

### 4-6. 매퍼 수정 — `AuthMappers.kt:37-47`

```kotlin
fun DriverSignUpForm.toRegisterRequest(): UserRegisterRequestDto =
    UserRegisterRequestDto(
        loginId = loginId,
        password = password,
        nickname = nickname.trim(),
        name = name,
        birthDate = MvpProfileDefaults.BIRTH_DATE,
        phoneNumber = normalizePhoneNumber(phoneNumber),
        gender = MvpProfileDefaults.GENDER,
        email = "$loginId@${MvpProfileDefaults.EMAIL_DOMAIN}",
        verificationToken = verificationToken,   // ← 추가
    )
```

### 4-7. 테스트 — `data/src/test/`

1. `toRegisterRequest` 가 `verificationToken` 을 그대로 싣는지
2. `normalizePhoneNumber` 10/11/비정상 자리수 (기존 테스트 있으면 확장)
3. `smsErrorMessage` 매핑: `USER202`/`USER204`/`USER205`/`USER206`/`USER207`/`USER104`/`INVALID_REQUEST`/`null`
4. `SmsVerifyResponseDto` 의 `verificationToken` 누락 시 기본값 `""` → `verifyPhoneCode` 가 예외로 승격하는지

`./gradlew :data:testDebugUnitTest --console=plain` + `./gradlew :app:assembleDebug`

### 4-8. 연동 검증 순서 (배포 후)

1. `GET /api/v1/config` 의 `taxiEnabled` 가 `true` 인지 — `false` 면 기사 API 가 전부 404 (**A14**)
2. `POST /auth/sms/send` 가 404 를 벗어났는지 (= HEAD 배포 완료)
3. `POST /auth/sms/verify {code:"000000"}` 가 200 인지 → **dev 프로필 여부 판정**. 400 `USER205` 면 prod(SOLAPI 실제 문자)이므로 실기 테스트에 **실제 수신 가능한 번호**가 필요하다
4. `POST /auth/register` 에 `verificationToken` 을 실어 201 확인

---

## 부록 — 커밋별 변경 요약 (`b9a921d..HEAD`, user 범위)

| 커밋 | 날짜 | 클라이언트 영향 |
|---|---|---|
`65b8de3` 문자 발송 추가 (운영:SOLAPI, 로컬:log) | 10-10 | 없음 (인프라) |
`119301f` 문자 인증 상태를 Redis 에 저장 | 10-10 | 없음 |
`449066a` 문자 인증 에러 코드 추가 | 10-10 | `USER201`~`USER208` 신규 — 메시지 매핑 필요 |
`b8e04ab` 문자 인증 발송/확인 서비스 추가 | 10-10 | 없음 (서비스층) |
**`94bd54d` 문자 인증 API 추가** | 10-11 | **`POST /auth/sms/send`(204), `/auth/sms/verify`(200 `{verificationToken}`) 신규 — 연동 필요** |
**`dab0947` 쓰지 않는 옛 회원가입 API 삭제** | 10-11 | `POST /api/v1/local/users` 삭제. **클라이언트는 호출 안 함** → 실영향 없음. `AuthApi.kt:18-19` 주석만 틀림 |
**`db285f9` 회원가입에 문자 인증 확인 추가** | 10-11 | **`UserRegisterRequest.verificationToken` `@NotBlank` 추가 + `LocalUserService.register` 선두에 `checkVerified()`. 가입 깨짐** |
**`143ca4e` dev 고정 인증번호** | 10-11 | **dev: `000000`. prod: 고정값 없음(SOLAPI 실문자)** |
`4433dc7` 내 정보 컨트롤러 AuthService 의존 제거 | 10-11 | 없음 (API 동일) |
`908abb6` 개인정보 마스킹 추가 | 10-08 | `/local/users/info` 의 `name` 이 닉네임 없을 때 `홍*동`. 기사 앱은 닉네임 필수라 영향 없음 |
`c8f1160`+`f2f54e4` user_profile 암호화 | 10-08 | API shape 불변. 전화번호 조회는 hash 비교 — `phone/check` 실측 정상 |
`c414f12` 인증 조회 Redis 캐시 | 10-02 | 없음 |
`fec46d5` user 패키지 기능별 재구성 | 09-29 | 서버 내부 경로만 변경. **클라이언트 DTO 주석의 원천 경로(`user/interfaces`, `user/application/dto`)가 낡음** → 실제는 `user/{auth,local,profile,verification}/{presentation,dto}` |
`59c79fc` 앱 설정 조회 토큰 없이 허용 | 09-19 | `GET /api/v1/config` permitAll (기사 앱 미사용) |

---

## 산출물 메타

- 실서버 검증: **✔ 일부** — 배포 서버(`api.moyeota.p-e.kr`, 구버전)의 auth 응답 shape 은 실측 확인. **HEAD 소스의 SMS 엔드포인트는 미배포라 실측 불가**(컨트롤러·서비스 소스 기준)
- 테스트 계정 1개 생성: `drvapichk01` / `010-0000-0001` / `uuid=01a13168-3d90-7ab1-bffb-3ad7b2a8a747` (삭제 API 없어 잔존)
- 백엔드 수정: **없음** (읽기 전용)
- 클라이언트 수정: **없음** (분석 전용 작업)
- 29b(driver) 전달 사항: `TAXI_ENABLED=false` 로 배포 서버의 `/api/v1/drivers/**`·`/api/v1/rides/**`·`/api/v1/calls/**` 가 전부 404 (실측). `DriverController.java:27` 의 `@ConditionalOnProperty` 참조
