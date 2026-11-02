# 32b. L2 실기 회귀 — 에뮬레이터 검증 결과

- 대상: `feature/backend-sync-sms-auth` (PR #27), `app/build/outputs/apk/debug/app-debug.apk` (2026-10-13 04:15 빌드)
- 단말: AVD `Pixel_6_QA` (emulator-5554, 1080x2400, API 36), `-memory 2048`
- 위치: `adb emu geo fix 129.059215 35.157845` (서면) · 위치/알림 권한 사전 부여
- 서버: 배포 `https://api.moyeota.p-e.kr/` · **`GET /api/v1/config` → `{"taxiEnabled":false}` 실측 확인**
- 로그캣 전량: `/tmp/qa-logcat-full.log`
- 스크린샷: `_workspace/qa/32b_*.png` (25장)

## 결론 요약

| # | 항목 | 판정 |
|---|---|---|
| 1 | 앱 시작 · 복구 게이트 → 로그인 착지 | **통과** |
| 2 | 404 오진 문구 대신 서버 기능 비활성 안내 | **실패 (D-1)** |
| 3 | 로그인 실패/404 후 세션 보존 | **통과** (단, D-2 부작용) |
| 4 | 가입 4단계 회귀 (이동·후퇴·보존·배너) | **통과** (쿨다운만 미검증, D-3 사소) |
| 5 | 기존 화면 스모크 | **통과** (D-4 동일 패턴) |
| 6 | 로그캣 크래시 | **통과 — FATAL 0건, ANR 0건** |

**크래시: 없음.** `FATAL EXCEPTION` 0건, `E/AndroidRuntime`(앱) 0건, ANR 0건.
전 과정에서 잡힌 `E/` 라인은 `pm clear`·`force-stop` 에 따른 시스템측 `InputDispatcher: Channel is unrecoverably broken` 뿐이며 앱 결함이 아니다.

**다만 이번 PR 의 핵심 수정(404 오진 → 서버 비활성 안내)이 로그인 화면에서 사용자에게 도달하지 않는다 (D-1).**
Repository 계층은 정확히 동작하지만 ViewModel 이 메시지를 버린다.

---

## 1. 앱 시작 · 복구 게이트 — 통과

| 절차 | 결과 |
|---|---|
| `pm clear` 후 콜드 스타트 | 스플래시(`32b_01_start_t1s.png`) → **D01 로그인**(`32b_02_start_t5s.png`) |
| 소요 | `Displayed com.moyeota.driver/.app.MainActivity: +2s698ms` |
| 무한 로딩 | 없음. 토큰 없음 → `restoreSession()` 이 네트워크 없이 즉시 `loggedIn=false` 반환 |
| 다크 모드 | 정상 (SurfaceCanvas 어두운 배경 · 흰 텍스트) |

---

## 2. 로그인 404 안내 — **실패 (D-1, 최우선)**

### 재현 절차
1. 배포 서버에 계정을 하나 만든다(기사 등록 없이 유저만):
   `curl -X POST https://api.moyeota.p-e.kr/api/v1/auth/register -H 'Content-Type: application/json' -d '{"loginId":"qadriver01","password":"Qatest1234!","nickname":"qadriver01","name":"QaDriver","birthDate":"1990-01-01T00:00:00Z","phoneNumber":"010-7777-8801","gender":"MALE","email":"qadriver01@example.com"}'` → **201**
2. 앱 D01 에서 `qadriver01` / `Qatest1234!` 로 로그인 (`32b_03_login_filled.png`)
3. 결과 화면: `32b_04_login_404_result.png`

### 실측 네트워크 (로그캣, 10:37:30~31)
```
<-- 200 .../api/v1/auth/login                 (토큰 발급 성공)
--> GET .../api/v1/drivers/me
<-- 404 .../api/v1/drivers/me
    {"timestamp":"...","status":404,"error":"Not Found","path":"/api/v1/drivers/me"}   ← code 필드 없음
--> GET .../api/v1/config
<-- 200 {"taxiEnabled":false}                 ← driverFeatureUnavailableMessage() 가 실제로 호출됨
```
즉 `RemoteDriverRepository.driverLookupFailure()`(`RemoteDriverRepository.kt:368~388`)는 설계대로 동작해
`IllegalStateException("서버에서 택시 서비스가 꺼져 있어요 · 운영자에게 문의해 주세요")` 를 만들었다.
(`/api/v1/config` 조회가 뒤따른 것이 그 경로를 탔다는 직접 증거다.)

### 그런데 화면에는 이렇게 뜬다
> **"아이디 또는 비밀번호가 맞지 않아요"**

### 원인 — 파일:라인
`presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/LoginScreen.kt:74-76`
```kotlin
} catch (e: Exception) {
    _uiState.value = UiState(fieldError = "아이디 또는 비밀번호가 맞지 않아요")
}
```
`e.message` 를 쓰지 않고 **모든 예외를 자격증명 오류 한 문구로 뭉갠다.**
`RemoteDriverRepositoryAuthSyncTest.kt:150` 의 단위 테스트는 Repository 반환값만 보므로 이 갭을 잡지 못한다.

### 영향
- PR 이 없앴다는 오진("기사로 가입되지 않은 계정")이 **다른 오진("비밀번호가 틀렸다")으로 바뀌었을 뿐**,
  사용자 입장에서는 여전히 원인을 알 수 없다. 멀쩡한 비밀번호를 계속 다시 입력하게 된다.
- `/api/v1/config` 추가 왕복이 UI 에서 버려지므로 네트워크 비용만 늘었다.

### 수정 요청 (담당: compose-builder)
`LoginScreen.kt:74-76` 을 서버 메시지 우선으로:
```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    _uiState.value = UiState(fieldError = e.message ?: "로그인하지 못했어요 · 잠시 후 다시 시도해 주세요")
}
```
(현재는 `CancellationException` 까지 삼켜 화면 이탈 시에도 에러 문구가 남을 수 있다.)
추가로 `LoginViewModel` 단위 테스트에 "repository 예외 메시지가 fieldError 로 그대로 노출된다" 케이스를 넣어야 재발을 막는다.

### 대조군 — 진짜 401 은 정상
존재하지 않는 계정 `nosuchuser9` / `Wrongpass1!` → 서버 401 `USER102` → 같은 문구 표시(`32b_10_login_401_bogus.png`).
이 경우는 문구가 맞다. 또한 `moyeota_driver_session.xml` 이 생성되지 않아 **토큰 저장도 없다** — 정상.

---

## 3. 세션 보존 — 통과 (단, D-2 부작용)

`adb shell run-as` 로 SharedPreferences 직접 확인했다.

| 시점 | `/data/data/com.moyeota.driver/shared_prefs/moyeota_driver_session.xml` |
|---|---|
| 401 로그인 실패(대조군) 후 | **파일 없음** (토큰 저장 안 함) — 정상 |
| 404(기사 조회 실패) 후 | `access_token` · `refresh_token` **보존** — 지시서 요구대로 안 지워짐 |
| `am force-stop` → 재실행 후 | **보존 유지** |

`restoreSession()`(`RemoteDriverRepository.kt:189~224`)의 `else -> RestoredSession(loggedIn = true, ...)` 분기가
code 없는 404 를 "서버 사정"으로 보고 저장을 지키는 설계와 일치한다. **항목 3 자체는 통과.**

### D-2 (중간) — "로그인 실패"라 해놓고 재실행하면 로그인되어 있다
`RemoteDriverRepository.login()` 은 `tokenStore.update(...)`(`:344`)를 `driverApi.getMe()`(`:347`) **앞에서** 수행한다.
따라서 D-1 의 404 경로에서 화면은 "아이디 또는 비밀번호가 맞지 않아요"로 실패를 알리지만 토큰은 이미 저장돼 있다.

**재현:** 2번 절차로 로그인 실패 문구 확인 → `adb shell am force-stop com.moyeota.driver` → 재실행
→ **D06 홈 화면에 착지** (`32b_05_restart_after_failed_login.png`). 2회 반복 모두 재현.

홈에는 `박기사 기사님` · `오늘 운행 7건 · 86,200원` 이 뜬다 — `cachedDriverName` 이 비어 있고 `getMe()` 가 404 라
`DummyDriverRepository` 폴백 값이 그대로 노출된 것이다(`RemoteDriverRepository.kt:703-709`, 의도된 폴백).

판단: 저장 정책 자체는 의도대로지만 **"실패했다는 화면 문구"와 "성공으로 취급되는 저장 상태"가 어긋난다.**
D-1 을 고쳐 문구가 "서버에서 택시 서비스가 꺼져 있어요"로 바뀌면 "로그인은 됐는데 기사 기능이 없다"는 뜻이 되어
이 모순은 자연히 해소된다 — **D-1 수정이 D-2 의 근본 해결이다.** 별도 수정 불필요(단, D-1 미수정 시 중간 결함으로 남음).

---

## 4. 가입 4단계 회귀 — 통과

| 검증 | 결과 | 스크린샷 |
|---|---|---|
| D01 「기사 회원가입」 → 1/4 진입 | 통과 | `32b_11_signup_step1.png` |
| ① 기가입 번호(`01077778801`) | `phone/check` 200 `{exists:true}` → "이미 가입된 번호예요…" + CTA 가 **「로그인하러 가기」로 전환** | `32b_12_signup_step1_dup_phone.png` |
| ① 번호 수정 시 상태 리셋 | 통과 — CTA 가 「다음」으로 복귀 | `32b_13_signup_step1_new_phone.png` |
| ① → ② 이동 | 통과 | `32b_14_signup_step2.png` |
| ② 닉네임 실시간 중복 조회 | `nickname/check` 200 → "쓸 수 있는 닉네임이에요" | `32b_16_signup_step2_filled.png` |
| ② → ③ → ④ 이동 | 통과 | `32b_17/18/19_*.png` |
| ④ 진입 시 자동 1회 발송 | `POST /api/v1/auth/sms/send` → **404** → CTA 위 **에러 배너** "인증번호를 보내지 못했어요 · 서버 오류(404)" · OTP 입력 비활성 · 「인증하고 가입 완료」 disabled | `32b_19_signup_step4.png` |
| ④ 「인증번호 받기」 재시도 | 재호출 확인(10:43:39 두 번째 `sms/send`) · 배너 유지 · 크래시 없음 | `32b_20_signup_step4_resend_404.png` |
| 뒤로가기 후퇴 ④→③→②→① | **전 단계 입력값 100% 보존** (K5 / 4 / 12ga3456 / QA1234567, QaDriverTwo / qadriver02 / qadriver02 / 비밀번호, 01044556677) | `32b_21_signup_back_chain.png` |
| ① 에서 한 번 더 뒤로 | D01 로그인 복귀, 앱 생존 | `32b_22_back_to_login.png` |

### 미검증 — ④ 재전송 쿨다운 표시
`SignUpScreen.kt:213-220` 에서 쿨다운(`RESEND_COOLDOWN_SECONDS=60`)은 **발송 성공 시에만** 시작된다.
배포 서버가 `sms/send` 404 라 성공 경로를 탈 수 없어 `재전송 (60초)` 라벨·`문자가 안 오면 N초 뒤에…` 문구를
**실기로 관찰하지 못했다.** 로컬 백엔드(L3) 또는 ViewModel 단위 테스트(L4)로 덮어야 한다.

부수 관찰: 발송 **실패** 시에는 쿨다운이 걸리지 않아 「인증번호 받기」를 무제한 연타할 수 있다(실측 2회 연속 호출 성공).
서버 레이트리밋이 복구되면 과호출 소지가 있으나 현재 영향은 없다 — 참고 사항.

### D-3 (사소) — ④ 부제가 실패 상태에서도 "보냈어요"라고 단정
`presentation/.../auth/PhoneCodeStep.kt:52`
```kotlin
text = "$phoneDisplay 로 6자리 인증번호를 보냈어요",
```
`codeSent` 와 무관하게 고정 문구다. 404 로 발송이 **실패한 화면**에서 다음 세 줄이 동시에 보인다:
- "010-4455-6677 로 6자리 인증번호를 보냈어요" ← 사실과 다름
- "「인증번호 받기」를 누르면 문자로 보내드려요" ← 맞음(조건부로 잘 돼 있음)
- "인증번호를 보내지 못했어요 · 서버 오류(404)" ← 맞음

**수정 요청(담당: compose-builder):** `PhoneCodeStep.kt:52` 를 `codeSent` 로 분기
(예: `codeSent` 면 "…보냈어요", 아니면 "…로 인증번호를 보낼게요").

---

## 5. 기존 화면 스모크 — 통과 (D-4 동일 패턴)

로그인 전 접근 가능한 화면은 D01 과 가입 4단계뿐이다(「비밀번호 찾기」는 미연결·비활성 표기).
D-2 경로로 홈에 진입해 로그인 후 화면도 함께 확인했다.

| 화면 | 결과 | 스크린샷 |
|---|---|---|
| D06 홈(휴무) | 렌더 정상, 하단탭 노출(규칙 적합) | `32b_05_restart_after_failed_login.png` |
| D09 콜 리스트 | 빈 상태 "지금 받을 수 있는 콜이 없어요" 정상, 크래시 없음 | `32b_06_tab_call.png` |
| D17 정산 | 더미 데이터 렌더 정상 | `32b_07_tab_settlement.png` |
| D19 내 평점 | 더미 데이터 렌더 정상 | `32b_08_tab_my.png` |
| D20 운행 이력 목록 | 정상 · **하단탭 노출(규칙 적합 — D20 은 노출 대상)** | `32b_23_trip_history.png` |
| D21 운행 이력 상세 | 정상 · **하단탭 미노출(규칙 적합)** | `32b_24_trip_history_detail.png` |
| D18 정산 상세 | 정상 · **하단탭 미노출(규칙 적합)** | `32b_25_settlement_detail.png` |

하단탭 노출 규칙(06·07·09·12·17·19·20) 위반 없음. 전 화면 다크 모드 유지.

### D-4 (중간) — 영업 시작도 404 를 "다시 시도해 주세요"로 뭉갠다
D06 「영업 시작하기」 탭 → `POST /api/v1/dispatch/online` → **404** →
화면 문구 **"영업을 시작하지 못했어요. 다시 시도해 주세요."** (`32b_09_duty_start_404.png`)

`presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/home/HomeRoute.kt:127-131`
```kotlin
private fun startDutyErrorMessage(e: Exception): String = when (e) {
    is DriverLocationUnavailableException -> "위치를 확인하는 중이에요 · 실외에서 잠시 후 다시 시도해 주세요"
    else -> "영업을 시작하지 못했어요. 다시 시도해 주세요."
}
```
D-1 과 같은 유형(서버 메시지 폐기)이다. "다시 시도"는 서버에 기능이 없는 상황에서 영원히 통하지 않는 안내다.
**수정 요청(담당: compose-builder):** `else ->` 를 `e.message ?: "영업을 시작하지 못했어요 · 잠시 후 다시 시도해 주세요"` 로.

부수 확인: `PUT /api/v1/drivers/fcm-token` 404 는 조용히 무시되어 화면에 영향 없음(best-effort 설계대로, 정상).

---

## 6. 로그캣 — 통과

```
FATAL EXCEPTION            : 0건
E/AndroidRuntime (앱 프로세스): 0건
ANR in / Input dispatching : 0건
com.moyeota 스택트레이스     : 0건
```
검출된 `E/` 는 전부 `pm clear`·`force-stop` 부산물(`InputDispatcher: Channel is unrecoverably broken`,
`WindowOrganizerController: Attempting to externally change a non-organized container`)로 시스템측 로그다.

정적 확인: `NetworkModule.kt:38-39` 의 로깅 레벨은 `BuildConfig.DEBUG` 일 때만 `BODY`, 릴리즈는 `NONE` 이다.
이번 실기 로그에 accessToken·비밀번호가 평문으로 남은 것은 **디버그 빌드라서 정상**이며 릴리즈에는 해당 없음.

---

## 결함 목록 (수정은 담당 에이전트가 수행 — QA 는 미수정)

| ID | 심각도 | 파일:라인 | 담당 |
|---|---|---|---|
| D-1 | **높음 (PR 핵심 수정 무효화)** | `presentation/.../auth/LoginScreen.kt:74-76` | compose-builder |
| D-2 | 중간 (D-1 수정으로 해소) | `data/.../RemoteDriverRepository.kt:344` ↔ `LoginScreen.kt:74` 경계 | compose-builder / api-integrator |
| D-3 | 사소 (문구) | `presentation/.../auth/PhoneCodeStep.kt:52` | compose-builder |
| D-4 | 중간 (동일 패턴) | `presentation/.../home/HomeRoute.kt:127-131` | compose-builder |

공통 원인: **Repository 가 공들여 만든 진단 메시지를 ViewModel 의 `catch` 가 고정 문구로 덮는다.**
D-1·D-4 외에도 같은 패턴이 있는지 `catch (e: Exception)` 뒤에 `e.message` 를 안 쓰는 지점을 일괄 점검할 것을 권한다.

## 검증 환경 잔여물 (정리 필요)
- 배포 서버에 QA 계정이 **생성됨**: `loginId=qadriver01`, `phoneNumber=010-7777-8801`, `uuid=01a132e7-312e-7480-8d4e-e97b6389ae1b`
  (2번 재현에 필요해 curl 로 직접 생성. 기사 등록은 안 됨 — 유저 레코드만). 운영 정리 시 제거 요망.
- `qadriver02` / `010-4455-6677` 은 ④ 단계에서 막혀 **생성되지 않았다**(서버에 레코드 없음).
