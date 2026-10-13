# 31 · api-integrator — 백엔드 HEAD 동기화 (A의 domain/data + B 1~7)

계약서 `_workspace/30_sync_contract.md` 기준. **`presentation/`·`core/designsystem`·`app/`·dispatch 3파일은 건드리지 않았다.**
백엔드 기준: 소스 HEAD `e729801` 직접 독해 (컨트롤러·DTO·서비스·SecurityConfig·ErrorCode 전부).
**실서버 미검증** — 배포 서버는 구빌드 + `taxiEnabled=false` 라 문자 인증·기사 엔드포인트가 등록돼 있지 않다(29a 실측).

## 1. 변경 · 신규 파일

| 파일 | 상태 | 내용 |
|---|---|---|
| `domain/model/DriverModels.kt` | 변경 | `DriverSignUpForm` + `verificationToken`, 4단계 KDoc |
| `domain/repository/DriverRepository.kt` | 변경 | `requestOtp`/`verifyOtp` **제거** → 신규 4개 추가 |
| `data/remote/dto/AuthDtos.kt` | 변경 | `UserRegisterRequestDto` + `verificationToken`, SMS·닉네임·config DTO 6종 신규 |
| `data/remote/AuthApi.kt` | 변경 | `sendSmsCode`·`verifySmsCode`·`checkNickname`·`getAppConfig` 추가 + 낡은 주석 2곳 정리(B5) |
| `data/remote/AuthMappers.kt` | 변경 | `serverError()`·`errorCode()`·`ServerErrorCodes`·`authFailureMessage()` 추가 |
| `data/remote/NetworkModule.kt` | 변경 | 로깅 레벨 빌드타입 분기(B4) |
| `data/build.gradle.kts` | 변경 | `buildFeatures.buildConfig = true` (B4 전제) |
| `data/repository/RemoteDriverRepository.kt` | 변경(대폭) | 신규 4메서드 + B1·B2·B3·B6 |
| `data/repository/DummyDriverRepository.kt` | 변경 | 신규 4메서드 (인증번호 `000000` 통과) |
| `data/test/.../RemoteDriverRepositoryAuthSyncTest.kt` | **신규** | 26 테스트 — B1·B2·B3·B6·B7 + SMS/닉네임 분기 |
| `data/test/.../AuthMappersTest.kt` | 변경 | +13 테스트 (SMS DTO 직렬화, verificationToken, 에러코드 판정, 문구 변환) |
| `data/test/.../RemoteDriverRepository{Session,Call,Duty}Test.kt` | 변경 | AuthApi 신규 4메서드 가짜 구현 + 404 테스트를 실제 code 로 교정 |

## 2. 최종 domain 시그니처 (계약서 글자 그대로)

```kotlin
suspend fun requestPhoneVerification(phoneNumber: String)
suspend fun verifyPhoneCode(phoneNumber: String, code: String): String   // verificationToken
suspend fun isNicknameTaken(nickname: String): Boolean
suspend fun isTaxiServiceEnabled(): Boolean
// DriverSignUpForm: + val verificationToken: String
// 제거: requestOtp(phone) / verifyOtp(phone, code)
```

compose-builder 의 `31_compose-builder_signup.md` 와 **경계면 일치 확인**. 그쪽이 지적한 KDoc 단계 번호
불일치(①휴대폰 ②프로필 ③택시 ④인증)도 `domain/` 주석을 presentation 구현에 맞춰 정정했다.

### 연동 엔드포인트 (전부 소스 기준 · permitAll 구간)
```
POST /api/v1/auth/sms/send       {"phoneNumber":"010-1234-5678"}        → 204
POST /api/v1/auth/sms/verify     {"phoneNumber":..,"code":"123456"}     → 200 {"verificationToken":"<uuid>"}
POST /api/v1/auth/nickname/check {"nickname":"길동이"}                   → 200 {"exists":bool}
POST /api/v1/auth/register       {기존 8필드 + "verificationToken"}      → 201 {"uuid","name"}
GET  /api/v1/config                                                     → 200 {"taxiEnabled":bool}
```
**별도 Retrofit 인스턴스 불필요** — `SecurityConfig` 가 `/api/v1/config` 를 `/api/v1/auth/` 하위와
함께 permitAll 로 열어 두므로(`SecurityConfig.java:48`) 토큰 없는 기본 클라이언트(`AuthApi`)에 얹었다.
인증 클라이언트에 얹으면 미로그인 시작 화면의 호출이 401 Authenticator 재발급을 헛되게 트리거한다.

## 3. B 항목별 처리 방식

### B1. 404 를 "기사 미등록"으로 단정하지 않기 ✔
`serverError()`/`errorCode()` 로 에러 바디 `{code,message}` 를 읽고 **`code == DRIVER_NOT_REGISTERED`
일 때만** 확인된 종료로 본다(`ServerErrorCodes.isDriverMissing`).
- `restoreSession()`: 확인된 미등록만 `clearStoredSession()`. **code 없는 404(핸들러 없음)는 저장 보존 + `loggedIn=true`**
- `login()`: 확인된 미등록만 "기사로 가입되지 않은 계정입니다". 그 밖의 404 는 `driverFeatureUnavailableMessage()` —
  `GET /api/v1/config` 를 한 번 읽어 `taxiEnabled=false` 면 "서버에서 택시 서비스가 꺼져 있어요", true 면
  "서버에서 기사 기능을 사용할 수 없어요"로 갈라 안내한다 (계약서 B1 보조 요구)
- `restoreOngoingTrip()`: 활성 partyId 삭제도 `code == PARTY_NOT_FOUND` 로 한정

> 근거: driver·dispatch·matching 의 `ErrorCode.getCode()` 는 **enum 이름**을 돌려주고(`DriverErrorCode.java`),
> user 도메인만 `USER1xx` customCode 를 쓴다. 핸들러가 없는 404 는 Spring 기본 바디라 `code` 가 **없다**.

### B2. 로그아웃 시 FCM 토큰 해제 ✔
`logout()` 이 `removeFcmTokenQuietly()` → `DELETE /api/v1/drivers/fcm-token` 을 **토큰을 비우기 전에,
비동기가 아니라 기다려서** 호출한다(인증 구간이라 Bearer 가 실려야 한다). 실패는 무시.
`lastFcmToken` 은 **일부러 남긴다** — 단말 토큰 자체는 유효하므로 다음 로그인에서 `flushFcmTokenAsync` 가 재등록한다.

### B3. register 409 무조건 무시 버그 ✔ (※ 계약서와 한 가지 다름 — 4절 참고)
`registerOrDuplicate()` 가 409 를 코드로 가른다.
- `USER101`(아이디 중복)·`USER104`(전화번호 중복) → **내 계정일 수 있으므로** 로그인으로 진행.
  이어지는 로그인이 401 이면 `duplicateAccountMessage()` 로 **"이미 사용 중인 아이디예요 / 이미 가입된
  전화번호예요"** 로 바꿔 올린다(엉뚱한 "아이디나 비밀번호가 다릅니다" 제거).
- `USER108`(닉네임 중복)·400 `USER207`(인증 만료)·`INVALID_REQUEST` 등 → **즉시 중단**, 서버 한국어 메시지 노출.
  로그인을 시도하지 않으므로 원인이 가려지지 않는다.

### B4. 릴리즈 빌드 바디 로깅 제거 ✔
`NetworkModule.LOG_LEVEL` = 디버그 `BODY` / 릴리즈 `NONE`. `data` 모듈에 `buildConfig = true` 를 켜서
`com.moyeota.driver.data.BuildConfig.DEBUG` 로 가른다. 릴리즈에서 `BASIC` 도 쓰지 않는다 —
헤더만 남겨도 `Authorization` 이 노출된다. (`:data:compileReleaseKotlin` 통과 확인)

### B5. 낡은 주석 정리 ✔
- `AuthApi` 클래스 KDoc: permitAll 목록을 현행(`/api/v1/auth/` 하위 + `/api/v1/config` + `/ws-chat/` 하위
  + `/health`·`/prometheus`·`/info` + swagger)으로 교체. 삭제된 `POST /api/v1/local/users` 를 "백엔드에서
  삭제됨"으로 명시. 패키지 경로도 `user/{auth,local,verification}/presentation` 으로 갱신
- `checkPhone` KDoc 의 "실서버 미검증(구빌드 — 404)" 삭제 (현재 200)

### B6. 콜 수락 409 오진 (소유권 확인) ✔
`claimOwnedTripOn409()` → `restoreOwnedTrip()` 이 `GET /api/v1/matching/rooms/{partyId}`(기존
`PartyMembersApi`)로 `taxiDriverId == GET /drivers/me 의 id` 인지 확인하고, 맞으면 **기존
`PartyRecoveryMappers.toRestoredTrip()`** 으로 운행을 만들어 성공 처리한다. 새 API·새 매퍼 없음.

**적용 지점이 두 곳이다** — 계약서는 accept 만 적었지만 그것만으로는 실효가 없다:
수락이 이미 반영되면 서버가 콜 후보를 비우므로 **`acceptCall` 의 첫 호출인 콜 상세 조회(`GET
/dispatch/calls/{partyId}`)부터** 409 CALL_CLOSED 로 떨어진다. 그래서 상세 409·수락 409 **양쪽**에
같은 소유권 판정을 걸었다. (상세 409 경로가 바로 `PartyRecoveryMappers` 재사용이 필요한 지점이다.)

가드: 소유권 미확인(매칭방 API 미주입·네트워크 실패·`taxiDriverId` null·내 기사 id 미상)이거나
복구 불가 상태(`FINISHED`/`CANCELED`/`MATCHING`)면 **성공으로 바꾸지 않는다**. 성공 처리 시
`settleAcceptedTrip()` 이 정상 경로와 동일하게 피드 소거 + 활성 partyId 영속 + 운행 캐시를 맞춘다.

### B7. `isTaxiServiceEnabled()` ✔
`GET /api/v1/config` → `taxiEnabled`. **실패는 `true`(낙관) 폴백** — 게이트가 네트워크 한 번 흔들렸다고
정상 기사를 앱 밖에 가두면 안 된다. DTO 기본값도 `true` 라 필드 없는 구서버 응답도 열림으로 읽는다.

### 문자 인증 에러 문구 정책
`authFailureMessage()` 는 **서버 `message` 를 최우선**으로 쓴다. 백엔드 `UserErrorCode` 의 message 가
이미 사용자 노출용 한국어(`"인증번호는 1분에 한 번만 요청할 수 있습니다."`)이고, 클라이언트에 같은 문구를
복제하면 서버 정책 변경 시 두 벌이 어긋난다. **코드는 분기에만, 문구는 서버를 따른다.**
바디가 빈 경우(프록시 502 등)에만 상태코드별 폴백 문구를 쓴다.

## 4. 리더 확인 필요 (계약서와 다르게 구현한 1건)

**B3 의 USER101 처리를 계약서와 반대로 뒀다.** 계약서는 "`USER104` 만 통과시키고 `USER101` 은 중단"인데,
`USER101` 을 하드 중단하면 **가입 재시도가 막힌다**:

백엔드 검사 순서는 `verificationToken → loginId → phoneNumber → nickname`
(`LocalUserService.register()` 직접 확인)이다. `signUp()` 은 `register → login → POST /drivers → verify`
4단계이므로, 기사 등록만 네트워크 실패해 사용자가 다시 제출하면 **같은 loginId 라 반드시 `USER101`** 이다.
여기서 중단하면 "이미 사용 중인 아이디"만 반복되고, 로그인 화면으로 가도 `GET /drivers/me` 404 →
"기사로 가입되지 않은 계정" 이라 **빠져나갈 길이 없다**.

그래서 **계약서의 의도("엉뚱한 401 메시지를 보여주지 말 것")는 지키면서** 재시도 경로를 살렸다:
`USER101`·`USER104` 는 로그인으로 진행하고, 로그인이 401 이면 그때 중복 항목을 집어 안내한다.
사용자가 보는 결과는 계약서와 같고(코드별 메시지), 내 계정 재시도만 추가로 성공한다.
→ **계약서대로 하드 중단을 원하면 `registerOrDuplicate()` 의 `mayBeMyAccount` 조건만 바꾸면 된다.**

## 5. 테스트 결과

```
./gradlew :data:testDebugUnitTest   →  BUILD SUCCESSFUL
155 tests, 0 failed, 0 skipped
```
| 클래스 | 수 |
|---|---|
| `RemoteDriverRepositoryAuthSyncTest` (신규) | 26 |
| `AuthMappersTest` | 32 (+13) |
| `RemoteDriverRepositorySessionTest` | 19 |
| `RemoteDriverRepositoryCallTest` | 16 |
| 그 외(Dispatch·Duty·Recovery·TokenStore·CallFeed·DTO) | 62 |

계약서 "검증" 절 대응: SMS DTO 직렬화 ✔ / register 에 verificationToken 포함 ✔ / 404 분기 ✔ /
409 소유권 확인 ✔ / 로그아웃 FCM 해제 ✔ (+ taxiEnabled 폴백, 닉네임 400 합치기, USER101 재시도 경로)

`:data:compileDebugKotlin`·`:data:compileReleaseKotlin` 통과. **`:app:assembleDebug` 는 지시대로 돌리지 않았다**(리더 담당).

### 기존 테스트 2건을 교정했다 (의도된 동작 변경)
`RemoteDriverRepositorySessionTest` 의 `기사 미등록(404)...` · `서버에 없는 파티(404)...` 는 범용 바디
`{"code":"ERROR"}` 로 404 를 던져 "저장 삭제"를 기대했다 — B1 이 바꾸려는 바로 그 동작이다.
실제 서버 코드(`DRIVER_NOT_REGISTERED`·`PARTY_NOT_FOUND`)로 교체했고, 그 짝(code 없는 404 =
저장 보존)은 신규 테스트가 검증한다.

## 6. qa-verifier 참고 — 경계면 교차 검증 포인트

1. **가입 4단계**: ④ 인증 성공 → `signUp(form.copy(verificationToken = token))` 로 토큰이 실제로 실리는지.
   서버는 토큰에 묶인 전화번호 해시와 요청 `phoneNumber` 를 대조하므로 ①과 ④의 번호가 같아야 한다
   (양쪽 모두 `normalizePhoneNumber` → `010-1234-5678`).
2. **dev 서버에서는 인증번호 `000000`** (`moyeota.sms.fixed-code`, provider=log 전용). 더미 Repository 도 동일.
3. **B1 회귀 확인**: 현 배포 서버(`taxiEnabled=false`)에서 로그인하면 더 이상 매 실행 로그아웃되지 않고
   "서버에서 택시 서비스가 꺼져 있어요" 가 떠야 한다.
4. **릴리즈 로깅**: `assembleRelease` 후 logcat 에 토큰·전화번호·인증번호가 남지 않아야 한다.
5. 실기 전면 검증은 **서버 `TAXI_ENABLED=true` 재배포 후**에만 가능 (계약서 전제).
