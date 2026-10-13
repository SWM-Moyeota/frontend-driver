# 32a · L1 코드 교차검증 — 백엔드 동기화 (PR #27)

검증 범위: 계약 `30_sync_contract.md` ↔ 구현(`31_api-integrator_sync.md`·`31_compose-builder_signup.md`) ↔ **백엔드 소스 직독**
읽기 전용 수행 — 코드 수정 없음. 에뮬레이터 미사용(L2 점유).
백엔드 기준: `/Users/sungyoon/Desktop/moyeota/backend` `d04b54c` (계약서가 적은 `e729801`보다 앞선 커밋이나, auth/driver/dispatch 표면은 동일)

| 구분 | 건수 |
|---|---|
| 통과 | 17 항목 (A 10 + B 7) |
| 결함 | **11건** (Medium 5 · Low 6) — High 0 |
| 미검증 | 실기·실서버 E2E (L2/L3 담당), presentation 단위 테스트 부재 |

---

## 1. 계약 ↔ 구현 대조 (A. 문자 인증 가입)

| 계약 항목 | 판정 | 근거 (양쪽 동시 확인) |
|---|---|---|
| `POST /auth/sms/send {phoneNumber}` → 204 | ✅ | `AuthApi.kt:67-68` 반환형 없음(204) · `SmsSendRequestDto`(AuthDtos.kt:101-103) ↔ `SmsSendRequest.java:6-10` 필드명 일치 |
| `POST /auth/sms/verify {phoneNumber,code}` → `{verificationToken}` | ✅ | `AuthApi.kt:74-75` · `SmsVerifyRequestDto`/`SmsVerifyResponseDto`(AuthDtos.kt:110-123) ↔ `SmsVerifyRequest.java`/`SmsVerifyResponse.java` 완전 일치 |
| `POST /auth/register` 기존 8필드 + `verificationToken` | ✅ | `UserRegisterRequestDto`(AuthDtos.kt:28-43) ↔ `UserRegisterRequest.java:14-53` **9필드 정확히 일치**. 직렬화 키 집합을 테스트가 못 박음(`AuthMappersTest.kt:127-141`) |
| `code` = `^\d{6}$` | ✅ | `OtpCodeInput`(AuthCommon.kt:207) 숫자만 take(6) + CTA `code.length == OTP_CODE_LENGTH`(SignUpScreen.kt:521) ↔ `SmsVerifyRequest.java:11` |
| 전화번호 `^01[016-9]-?\d{3,4}-?\d{4}$` 통과 | ✅ | `normalizePhoneNumber`(AuthMappers.kt:23-30) → `010-1234-5678`. 클라 사전검증 `PHONE_REGEX ^01[016-9]\d{7,8}$`(SignUpScreen.kt:66)가 서버 `PhoneNumber.java:12`(하이픈 제거 후 정규식)과 **동일** |
| 인증을 **제출 직전**(④)에 둘 것 (VERIFIED_TTL 10분) | ✅ | 4단계 머신(SignUpScreen.kt:333,385,393-456). `verifyAndSignUp`(254-290)이 verify→signUp을 **단일 코루틴**으로 연결 — 사용자 입력 개입 없음 |
| domain 시그니처 글자 그대로 | ✅ | `DriverRepository.kt:52,61,70,80` + `DriverSignUpForm.verificationToken`(DriverModels.kt:37). `requestOtp`/`verifyOtp` 제거됨 |
| D02 입력 UI를 내부 step으로 재사용 | ✅ | `OtpCodeInput`을 `AuthCommon.kt:198-245`로 승격, D02(PhoneVerifyScreen.kt:205)와 ④(PhoneCodeStep.kt:58)가 동일 컴포저블 |
| 라우트는 리더만 — 미변경 | ✅ | `git diff develop...HEAD` 에 `Routes.kt`·`MainNavGraph.kt`·`AuthGraph.kt` **없음** |
| 재전송 쿨다운 60초 안내 | ✅ | `RESEND_COOLDOWN_SECONDS = 60`(PhoneCodeStep.kt:17) ↔ 서버 `COOLDOWN = Duration.ofSeconds(60)`(SmsVerificationService.java:27) |

## 2. 계약 ↔ 구현 대조 (B. 안전성 수정)

| 항목 | 판정 | 근거 |
|---|---|---|
| **B1** 404를 "기사 미등록"으로 단정하지 않기 | ⚠️ **부분** | 판정 로직·`login` 문구는 정확(RemoteDriverRepository.kt:204,369-390). 단 `restoreSession` 경로에 안내 채널이 없다 → **결함 D4** |
| **B2** 로그아웃 시 FCM 해제 | ✅ | `logout()`이 `removeFcmTokenQuietly()`를 `clearStoredSession()` **앞에서 await**(RemoteDriverRepository.kt:595-596). 순서를 테스트가 실제로 단언(AuthSyncTest.kt:266-285) |
| **B3** register 409 코드별 분기 | ⚠️ **의도적 이탈** | `registerOrDuplicate`(626-638). 타당성 평가는 §4 |
| **B4** 릴리즈 바디 로깅 제거 | ✅ | `NetworkModule.kt:39-40` 디버그 BODY / 릴리즈 NONE + `data/build.gradle.kts` `buildConfig = true`. BASIC도 쓰지 않음(Authorization 헤더 노출 방지) — 올바른 선택 |
| **B5** 낡은 주석 2곳 | ✅ | `AuthApi.kt:25-28` permitAll 목록이 `SecurityConfig.java:47`과 일치(검증함) · `checkPhone` KDoc(56-58)에서 "404" 문구 삭제 |
| **B6** 콜 수락 409 소유권 확인 | ✅ | `claimOwnedTripOn409`(973-978)를 **콜 상세 조회(941)와 수락(950) 양쪽**에 적용. 가드(미배선·네트워크·taxiDriverId null·FINISHED/CANCELED/MATCHING) 전부 존재(985-998) |
| **B7** `isTaxiServiceEnabled()` 낙관 폴백 | ✅ | 507-514 실패 시 `true`, DTO 기본값도 `true`(AuthDtos.kt:146) — 이중 방어 |

## 3. 백엔드 실제 스펙 재대조 — 전부 일치 확인

소스를 직접 열어 확인한 정책값과 클라 UX 대응:

| 서버 상수 | 값 | 클라 대응 | 판정 |
|---|---|---|---|
| `COOLDOWN` | **60초** (`SmsVerificationService.java:27`) | `RESEND_COOLDOWN_SECONDS = 60` | ✅ 일치 |
| `CODE_TTL` | **3분** (`:26`) | ④에 타이머 **없음** (D02만 180초 보유) | ⚠️ **D6** |
| `VERIFIED_TTL` | **10분** (`:31`) | 인증을 ④로 이동 + 즉시 제출 | ✅ |
| `DAILY_LIMIT` | **5회/24h** (`:29`) | 서버 message 노출에만 의존 | ✅ (허용) |
| `MAX_ATTEMPTS` | **5회** (`:30`) | 전용 복구 UI 없음 | ⚠️ **D7** |

추가 확인 사항 (전부 클라 구현이 **정확**):
- **에러 코드 정체**: `UserErrorCode.getCode()` → `customCode`(`USER1xx`/`USER2xx`), `DriverErrorCode.getCode()` → `name()`(`DRIVER_NOT_REGISTERED`), 검증 실패 → `INVALID_REQUEST`(`GlobalExceptionHandler.java:19,60`). 클라 `ServerErrorCodes`(AuthMappers.kt:118-149)의 도메인별 구분이 **정확**하다.
- **`/api/v1/config` permitAll**: `SecurityConfig.java:47` 확인. `AppConfigController`에는 `@ConditionalOnProperty`가 **없다** → taxiEnabled=false에서도 살아 있음. 별도 Retrofit 인스턴스를 두지 않은 판단 타당.
- **B1 전제 검증**: `DriverController.java:27`·`DispatchCallController:19`·`RideController:23`·`DriverLocationController:20` 모두 `@ConditionalOnProperty(moyeota.taxi.enabled=true, matchIfMissing=true)` → **taxiEnabled=false면 핸들러 미등록 = code 없는 404**. 계약 B1의 전제가 사실로 확인됨.
- **B6 전제 검증**: `PartyController`(matching/rooms)는 taxi 게이트 **없음** → 기사 기능이 꺼져도 소유권 조회가 살아 있다는 주장 사실.
- **`GET /drivers/me` 404 코드**: `DriverService.java:108` + `CurrentDriverArgumentResolver.java:41` 모두 `DRIVER_NOT_REGISTERED`. `isDriverMissing`이 이 코드만 보는 것이 **정확**(`DRIVER_NOT_FOUND`는 "driverId는 풀렸는데 행이 없음"이라 미등록 판정이 아니다).
- **`sms/send`의 선행 중복 거절**: `SmsVerificationService.java:57-59` → 기가입 번호는 문자 비용 전에 409 `USER104`. 클라 ①단계 `phone/check`와 **같은 원천**(`userProfiles.existsByPhoneNumber`)을 쓰므로 판정이 어긋나지 않는다.
- **`SmsVerifyRequest.phoneNumber`에는 `@Pattern`이 없다**(`@NotBlank`만) — 도메인 `PhoneNumber`가 `USER105`로 거른다. 클라 KDoc이 이를 잘못 주장하지 않음.
- **`register` 검사 순서**: `checkVerified → loginId → phoneNumber → nickname`(`LocalUserService.java:39-51`), `consume()`은 **저장 성공 후에만**(`:62`). → B3 이탈의 근거가 사실이며, 동시에 **결함 D1의 근거**다.

---

## 4. B3 USER101 이탈 — 타당성 평가: **타당. 유지 권고**

계약은 "`USER104`만 통과, `USER101`은 중단"인데 구현은 둘 다 로그인으로 진행한다.

**타당하다고 보는 근거(백엔드 소스로 재확인):**
1. `consume()`이 저장 성공 후에만 실행되고(`LocalUserService.java:62`), `register`는 `loginId`를 **두 번째**로 검사한다(`:41`).
2. 클라 `signUp`은 `register → login → POST /drivers → verify` 4콜이다(RemoteDriverRepository.kt:526-579). 3~4번째 콜이 실패하면 계정은 이미 만들어졌으므로, 재제출은 **반드시** `USER101`이다.
3. 여기서 하드 중단하면 탈출로가 없다 — 로그인 화면으로 가도 `GET /drivers/me` 404 → "기사로 가입되지 않은 계정"(D5 참조).
4. 계약의 실질 의도("엉뚱한 401 메시지 제거")는 `duplicateAccountMessage()`(641-645)로 지켜졌고, 테스트 2건이 이를 못 박는다(AuthSyncTest.kt:345, :357).

**잔여 리스크(수용 가능):** 중복이 실제로 남의 아이디면 클라가 사용자 비밀번호로 그 계정에 로그인을 1회 시도한다. 다만 서버는 설계상 `USER102`로 구분 불가하게 응답하고, 클라가 추가로 알아내는 정보는 409가 이미 알려준 "그 아이디가 존재함"뿐이다.

---

## 5. 결함 목록

### D1 · [Medium] 제출 실패 시 **유효한 verificationToken을 버리고** 로컬 쿨다운을 0으로 풀어 서버 60초 쿨다운과 충돌
- **파일**: `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/SignUpScreen.kt:276-288`
- **코드**: 주석이 "인증 토큰은 1회용 — 제출이 실패하면 처음부터 다시 인증해야 한다"로 단정하고 `codeSent = false, resendCooldown = 0`을 세팅한다.
- **사실 관계(백엔드 반증)**:
  - `LocalUserService.java:61-62` — 주석까지 명시: *"저장까지 끝난 뒤에 지움 - 닉네임 중복 등으로 실패하면 인증을 다시 하지 않고 재시도 가능"*. 즉 **실패 시 토큰은 10분간 그대로 유효**하다.
  - `SmsVerificationRedis.java:25-27` — 쿨다운은 `setIfAbsent(sms:cooldown:{phoneHash}, 60s)`. `releaseCooldown`은 **발송 실패 시에만** 호출되고(`SmsVerificationService.java:78`), 인증 성공으로는 해제되지 않는다.
- **재현**: ④에서 인증 성공 → `signUp` 실패(예: 이름 21자 → 400, 또는 `POST /drivers` 네트워크 실패) → 배너 + 「인증번호 받기」로 라벨 변경 → 즉시 누름 → **429 `USER202` "인증번호는 1분에 한 번만 요청할 수 있습니다."** → 최대 60초 대기 + 일일 5회 중 1회 소모. ③↔④ 왕복해도 `LaunchedEffect`(SignUpScreen.kt:358-363)가 자동 재발송을 걸어 같은 429를 맞는다.
- **수정안**:
  1. 발급 토큰을 `UiState`에 보관하고, 재제출 시 **재인증 없이 그 토큰으로 `signUp`을 다시** 호출한다.
  2. 재인증을 강제하는 조건은 서버가 `USER207`(`SMS_NOT_VERIFIED`)을 돌려줄 때로 한정한다 — 상수가 이미 `ServerErrorCodes.SMS_NOT_VERIFIED`(AuthMappers.kt:132)에 있으나 **아무도 쓰지 않는다**(D8).
  3. 부득이 재발송 경로를 열 땐 `resendCooldown = 0`이 아니라 `lastSentAt` 기준 잔여초(`60 - elapsed`)로 세팅한다.

### D2 · [Medium] 서버만 거르는 필드(`name` 20자)의 클라 사전검증 누락 — 실패 비용이 SMS 1통
- **파일**: `SignUpScreen.kt:338`(`var name`, 상한 없음), `:598-604`(`errorText` 없음) ↔ 서버 `UserRegisterRequest.java:30-32` `@Size(max = 20)`
- **재현**: ②에서 이름 21자 입력 → `profileValid`는 `name.isNotBlank()`만 보므로(SignUpScreen.kt:367) 통과 → ③ → ④ 인증 성공(코드 소모·토큰 발급) → register 400 `INVALID_REQUEST` "name: 이름은 20자를 넘을 수 없습니다." → **D1과 합쳐 60초 벽**.
- **수정안**: 닉네임과 같은 패턴으로 `onNameChange = { name = it.take(20) }` + `errorText`. ②에서 막히는 것은 모두 ②에서 막아야 한다 — ④ 도달 후의 실패는 SMS를 태운다.

### D3 · [Medium] verify의 **모든** 실패를 "오입력"으로 표시 — 네트워크 오류에도 OTP 6칸이 빨갛게 변함
- **파일**: `SignUpScreen.kt:261-269` — `codeInvalid = true`를 예외 종류와 무관하게 세팅
- **재현**: ④에서 올바른 6자리 입력 → 비행기 모드(또는 서버 503 `USER208`) → 「인증하고 가입 완료」 → 박스 테두리 `Danger500` + 숫자 빨강(AuthCommon.kt:219-235)으로 "번호를 잘못 썼다"고 오안내. 배너 문구는 네트워크 오류인데 시각 신호가 모순된다.
- **수정안**: `codeInvalid`는 `USER204`·`USER205`·`USER206`에서만 세팅. 현재 repository가 문구만 담은 `IllegalStateException`을 던지므로(RemoteDriverRepository.kt:465) 코드 분기가 불가능하다 → `verifyPhoneCode`가 서버 code를 보존하는 예외(예: `SmsVerificationException(code, message)`)를 던지도록 **data 계층과 함께** 수정 필요. **api-integrator + compose-builder 양쪽 과제.**

### D4 · [Medium] B1 절반 구현 — `restoreSession` 경로는 저장만 보존하고 **사용자에게 아무 말도 하지 않는다**
- **계약 B1**: "그 밖의 404는 저장 보존 **+ '서버에서 기사 기능을 사용할 수 없어요' 류의 메시지로 구분**"
- **파일**: `RemoteDriverRepository.kt:206` → `return RestoredSession(loggedIn = true, ongoingTrip = null)` — 문구 없음. `RestoredSession`(domain/.../DriverModels.kt:184-192)에 **메시지 필드 자체가 없다**.
- **재현**: 현 배포 서버(taxiEnabled=false)에 로그인된 상태로 앱 재실행 → 로그아웃은 안 됨(B1 목표 달성 ✅) → 그런데 `getHomeSummary`가 더미로 조용히 강등되어(RemoteDriverRepository.kt:703-709) 기사는 **"콜이 안 오는 정상 홈"**을 본다. 원인 안내 0건. `login()` 경로(369-390)만 문구를 갖는다.
- **수정안**: `RestoredSession`에 `serviceNotice: String? = null` 추가 → code 없는 404일 때 `driverFeatureUnavailableMessage()` 결과를 담아 → 홈 상단 배너로 노출.

### D5 · [Medium] 기존(승객) 계정 → 기사 전환 경로가 **데드엔드** (PR 이전부터 존재. B1의 새 문구가 이를 고착)
- **파일**: `SignUpScreen.kt:466-468`(exists=true면 CTA가 「로그인하러 가기」로 고정 — ②로 진행할 길이 없다) · `RemoteDriverRepository.kt:374-376`("기사로 가입되지 않은 계정입니다. **회원가입을 진행해 주세요.**")
- **재현 루프**: ①에 기존 승객 번호 → "이미 가입된 번호" + 「로그인하러 가기」 → D01에서 로그인 성공 → `GET /drivers/me` 404 `DRIVER_NOT_REGISTERED` → "회원가입을 진행해 주세요" → 가입 ①에서 또 차단. **탈출 불가.**
- 추가 근거: `SmsVerificationService.java:57-59`가 기가입 번호의 `sms/send`를 409로 거절하므로, ①의 가드를 뚫어도 ④에서 막힌다. 즉 현재 가입 화면으로는 전환이 **원리적으로 불가능**하다.
- **수정안(설계 판단 필요 — 리더 에스컬레이션)**: ① 최소 조치로 `RemoteDriverRepository.kt:375` 문구에서 "회원가입을 진행해 주세요"를 제거(없는 길을 안내하지 않는다). ② 근본 조치로 "로그인 후 기사 정보만 등록"(login → `POST /drivers`) 전용 경로 신설 — `signUp`의 2~4단계가 이미 그 로직이다.

### D6 · [Low] ④에 `CODE_TTL`(3분) 만료 타이머 없음 — 29a 권장(`29a_auth_diff.md:454`) 미반영
- `PhoneCodeStepFields`(PhoneCodeStep.kt:64-73)의 보조 문구는 **재전송 쿨다운(60초)만** 표시한다. 고아 화면인 D02는 180초 타이머를 갖고 있다(`PhoneVerifyScreen.kt:124,161,217`).
- **재현**: ④ 진입 후 3분 방치 → 코드 입력 → 제출 → `USER204` "인증번호가 만료되었습니다" (사전 경고 없음) → 이어서 **D1의 60초 벽**.
- **수정안**: 60초 쿨다운과 **별개**로 180초 카운트다운 추가, 0에서 OTP 입력 비활성 + 「인증번호 다시 받기」 노출.

### D7 · [Low] `USER206`(입력 5회 초과) 전용 복구 UI 없음 — 29a 권장(`:456`) 미반영
- 서버는 5회째에 코드를 폐기한다(`SmsVerificationService.java:91-100`). 이후 입력은 전부 `USER204`로 떨어진다. 클라는 입력을 활성 유지하고 재전송은 60초 쿨다운에 묶여 있어, 기사가 무의미한 입력을 반복할 수 있다.
- **수정안**: `USER206` 수신 시 OTP 입력 비활성 + 재전송만 활성. D3의 코드 보존 예외가 선행 조건.

### D8 · [Low] 미사용 코드 · 주석 불일치
| 위치 | 내용 |
|---|---|
| `AuthMappers.kt:121,129,131,132` | `ServerErrorCodes.DRIVER_NOT_FOUND`·`INVALID_PHONE_NUMBER`·`NICKNAME_DUPLICATED`·`SMS_NOT_VERIFIED` **참조 0건**. 특히 `SMS_NOT_VERIFIED`는 D1을 올바르게 고치는 데 필요한 상수다 |
| `AuthMappers.kt:55` | `MvpProfileDefaults.NAME`이 매퍼에서 더 이상 쓰이지 않는다(`name = name`으로 폼 값 사용, `:42`). 테스트와 주석만 참조 |
| `RemoteDriverRepository.kt:201-203` | 주석이 "`DRIVER_NOT_REGISTERED`/`DRIVER_NOT_FOUND` 면"이라 적었으나 `isDriverMissing`은 전자만 본다(AuthMappers.kt:146). 테스트가 `DRIVER_NOT_FOUND == false`를 명시적으로 단언하므로(AuthMappersTest) **주석이 틀렸다** |
| `RemoteDriverRepository.kt:1161-1162` | 빈 줄 2개 (사소) |

### D9 · [Low] `isTaxiServiceEnabled()`가 domain 인터페이스에 올라와 있으나 외부 소비자 0
- 호출부는 `RemoteDriverRepository.kt:386` 내부 한 곳뿐. presentation 참조 없음. 계약 B7이 domain 메서드로 지정했으므로 **계약 준수**이나, 모든 구현체·가짜 구현이 쓰이지 않는 메서드를 override해야 한다. `private suspend fun`으로 내려도 기능 동일.

### D10 · [Low] D02(`AUTH_PHONE`) 여전히 고아 — **의도대로**이나 지뢰가 커졌다
- 확인: `AuthGraph.kt:44`에 composable 등록은 있으나 `navigate(Routes.AUTH_PHONE)` **전역 0건** → 도달 불가. 계약("라우트는 리더 외 미변경") 준수 ✅
- 다만 누군가 배선하면: (a) 「인증번호」 버튼이 **실제 SMS**를 발송한다(일 5회 한도 소모, PhoneVerifyScreen.kt:77), (b) `AuthGraph.kt:48`의 `onVerified = { navController.navigate(...) }`가 암묵 `it`을 버려 **verificationToken이 유실**되고 D03/D04로는 가입을 완료할 수 없다, (c) `StepProgressBar(step = 1, total = 3)`(PhoneVerifyScreen.kt:169)이 4단계 플로우와 불일치.
- **수정안**: 리더 판단 — 삭제하거나, `AuthGraph.kt:48`에 `onVerified = { token -> /* TODO: 토큰 전달 경로 없음 */ }` TODO를 남겨 유실을 명시.

### D11 · [Low] ④에서 `USER108`/`USER101` 수신 시 고칠 필드(②)로 돌아갈 길 안내 없음
- `step()`(RemoteDriverRepository.kt:648-659)이 "회원가입 실패 — 계정 생성: 이미 사용 중인 닉네임입니다."를 ④ 배너에 띄우지만 닉네임 필드는 ②에 있다. 사용자는 back을 두 번 눌러야 한다. ②의 사전 조회가 대부분 막아 주므로(SignUpScreen.kt:412,486) 경합 시에만 발생.
- **수정안**: `USER108` → `step = 2` + 닉네임 `errorText` 자동 세팅.

---

## 6. 회귀 위험 (지시 항목 3) — 통과

| 점검 | 결과 |
|---|---|
| `requestOtp`/`verifyOtp` 제거 잔재 | ✅ 없음. `PhoneVerifyScreen.kt:72,142`의 `requestOtp`는 **ViewModel 자체 메서드명**(repository 호출은 `requestPhoneVerification`, :77) |
| `DriverSignUpForm` 필드 추가 영향 | ✅ 생성자 호출부 3곳 모두 갱신됨 — `SignUpScreen.kt:505-517`, `AuthSyncTest.kt:61-72`, `AuthMappersTest.kt:33-44`. 데이터 클래스 필드 추가라 누락 시 컴파일 실패 |
| `AuthApi` 신규 4메서드 ↔ 기존 가짜 구현 | ✅ 4개 테스트 파일 모두 override 추가(Session/Call/Duty/AuthSync) |
| 세션 복구 회귀 | ✅ `restoreSession`의 저장 삭제 조건이 **좁아지기만** 했다(code 확인 필수). 기존 테스트 2건은 범용 바디 `{"code":"ERROR"}`로 404를 던져 삭제를 기대했던 것이라 **교정이 올바르다**(실서버 코드 `DRIVER_NOT_REGISTERED`/`PARTY_NOT_FOUND`로 교체 — SessionTest.kt:130-136,306-312). 짝(code 없는 404 = 보존)은 신규 테스트가 검증 |
| 운행 복구 회귀 | ✅ `restoreOngoingTrip`(234-279) 소유권·상태 가드 무변경. B6이 **같은 함수·같은 매퍼**를 재사용(`restoreOwnedTrip`, 985-998) — 새 API·새 매퍼 없음 |
| 로그아웃 회귀 | ✅ `removeFcmTokenQuietly`가 `tokenStore.isLoggedIn()` 가드 + 전체 예외 삼킴(875-884). 기존 Session 테스트의 가짜가 `error()`를 던져도 로그아웃은 완료된다 |
| 더미 리포지토리 | ✅ 신규 4메서드 구현. 고정 코드 `000000`이 백엔드 `moyeota.sms.fixed-code`(dev, provider=log) 규약과 동일 — 수동 테스트 절차 일치 |
| `MoyeotaTextField` 시그니처 변경 | ✅ `onFocusChange`가 기본값 `null`인 **마지막 선택 파라미터** → 기존 호출부 전부 무영향. 내부 `if (focused != it.isFocused)` 가드로 중복 통보 차단 |
| 다크 모드 / 하드코딩 색상 | ✅ `grep "Color(0x" presentation/.../feature/auth/` **0건** |

## 7. 테스트 신뢰도 (지시 항목 5) — 통과, 단 커버리지 구멍 1

실제 개수: `RemoteDriverRepositoryAuthSyncTest` **26** + `AuthMappersTest` **+17**(15→32) = **신규 43건**, data 모듈 총 **155건**.
(참고: `31_api-integrator_sync.md`는 AuthMappers를 "+13"으로, QA 지시서는 신규를 "39건"으로 적었다 — 실측 43건.)

표본 12건 검토 결과 **껍데기 테스트 아님**:
- **상태 단언**: `storage.tokens`/`activePartyId`의 보존·삭제를 직접 확인(AuthSyncTest.kt:117-119, 132-134) — "호출됐다"가 아니라 "결과가 이렇다"를 본다.
- **순서 단언**: `hadTokenWhenRemoved = tokenStore.isLoggedIn()`을 DELETE **호출 시점에** 캡처(`:275`)해 "토큰 삭제 전에 호출"을 진짜로 검증한다(`:282`). B2에서 가장 중요한 단언이고, 정확하다.
- **부정 단언**: `assertFalse(loginCalled)`(`:327`), `assertFalse(message.contains("회원가입을 진행"))`(`:158`), `assertFalse(message.contains("비밀번호가 다릅니다"))`(`:368`) — "틀린 동작을 하지 않는지"까지 본다.
- **가짜의 엄격성**: `미로그인 상태의 로그아웃은 서버를 부르지 않는다`(`:303-310`)가 가짜 안에서 `fail()`을 쓴다. `fail()`은 `AssertionError`(= `Error`)를 던지므로 `removeFcmTokenQuietly`의 `catch (_: Exception)`에 **삼켜지지 않는다** — 의도적이고 올바른 설계.
- **계약 고정**: 직렬화 키 집합 비교(AuthMappersTest.kt:127-141)와 서버 `@Pattern` 역검증(`normalizePhoneNumber` 결과를 서버 정규식에 직접 매치)은 와이어 회귀를 실제로 잡는다.

지적:
- `AuthSyncTest.kt:281` `assertEquals("...", true, hadTokenWhenRemoved != null)`은 `assertNotNull`로 쓸 것을 우회한 형태. 무해.
- **커버리지 구멍**: `presentation/src/test`가 **존재하지 않는다**. 신규 43건 전부 `:data` 소속이고, **D1·D2·D3는 모두 presentation 상태 머신 결함**이라 현 테스트로는 영구히 잡히지 않는다. → **L4(32d) 권고**: `SignUpViewModel` 단위 테스트(제출 실패 후 토큰·쿨다운 상태, verify 실패 종류별 `codeInvalid`) 추가.

## 8. 미검증 항목

| 항목 | 사유 |
|---|---|
| 에뮬레이터 실기 | L2(32b) 점유 — 본 레인은 건드리지 않음 |
| 실서버 문자 인증 E2E | 배포 서버 미배포(`sms/send` 404). 로컬 백엔드 기동은 L3(32c) |
| 릴리즈 빌드 logcat 실측(B4) | 코드 분기는 확인(`NetworkModule.kt:39-40`)했으나 `assembleRelease` + logcat 검증은 미수행 — L2 권고 |
| 피그마 대조 | 스크린샷 비교 불가(에뮬레이터 미사용) |

## 9. 담당 에이전트별 요청

**compose-builder** (단독 수정 가능)
- D1 `SignUpScreen.kt:276-288` — 토큰 보관·재사용 + 쿨다운 잔여초 계산
- D2 `SignUpScreen.kt:338,598-604` — `name` 20자 상한 + errorText
- D6 `PhoneCodeStep.kt:64-73` — 180초 CODE_TTL 타이머 추가
- D10 `PhoneVerifyScreen.kt:169` — `total = 3` → 플로우와 정합(또는 화면 폐기)
- D11 — `USER108` 수신 시 ②로 복귀

**api-integrator**
- D4 `RemoteDriverRepository.kt:206` + `DriverModels.kt:184` — `RestoredSession.serviceNotice` 추가
- D8 — 미사용 상수·틀린 주석(`:201-203`) 정리
- D9 — `isTaxiServiceEnabled` 가시성 재검토(계약 변경이면 리더 승인 선행)

**양쪽 공동(경계면)**
- D3 — `verifyPhoneCode`가 서버 code를 보존하는 예외를 던지고(api-integrator), 화면이 `USER204/205/206`에서만 `codeInvalid`를 세팅(compose-builder)

**리더 에스컬레이션**
- D5 — 기존 계정 → 기사 전환 경로 부재는 설계 결정 사항
- B3 USER101 이탈 — 유지 권고(§4). 계약서 본문 갱신 필요
