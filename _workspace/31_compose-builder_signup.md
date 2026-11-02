# 31 · compose-builder — 가입 4단계 재구성 (문자 인증을 마지막으로)

계약서 A의 **presentation 부분만** 구현. `domain/`·`data/`·`app/`·`presentation/core/Routes.kt`·`MainNavGraph.kt`·`feature/auth/AuthGraph.kt` 는 손대지 않았다.

> 전달받은 경로의 `_workspace/30_sync_contract.md` · `_workspace/29a_auth_diff.md` 는 **레포에 존재하지 않았다**(최신 산출물이 `28_*`). 지시문에 명시된 「확정 경계면」 시그니처를 계약으로 삼아 구현했고, 작업 중 api-integrator 가 `domain/` 에 그 시그니처를 올려 그대로 맞물렸다.

## 1. 변경 · 신규 파일

| 파일 | 상태 | 내용 |
|------|------|------|
| `presentation/.../feature/auth/SignUpScreen.kt` | 변경(대폭) | 3단계 → **4단계** 재구성. ④ 문자 인증 + 토큰 즉시 제출, ② 닉네임 중복 선조회 |
| `presentation/.../feature/auth/PhoneCodeStep.kt` | **신규** | ④ 스텝 본문 공용 컴포저블 `PhoneCodeStepFields` + `RESEND_COOLDOWN_SECONDS`(60) + `resendLabel()` |
| `presentation/.../feature/auth/AuthCommon.kt` | 변경(추가) | `OtpCodeInput`(6칸 박스 입력) · `OTP_CODE_LENGTH` 를 D02 에서 끌어올려 auth 공용화 |
| `presentation/.../feature/auth/PhoneVerifyScreen.kt` | 변경 | 새 경계면으로 이관(`requestOtp`/`verifyOtp` 제거 대응) + 공용 `OtpCodeInput` 사용 |
| `core/designsystem/.../component/Inputs.kt` | 변경(1개 파라미터) | `MoyeotaTextField(onFocusChange: ((Boolean) -> Unit)? = null)` 추가 — 기본값 있어 기존 호출부 무영향 |

라우트 상수 · NavGraph 등록은 **변경 없음**. `SignUpRoute(repository, onBack, onSignedUp)` 시그니처를 유지해 `AuthGraph.kt` 수정이 불필요하다.

### 진입 경로
`D01 AUTH_LOGIN` → 「기사 회원가입」 → `AUTH_SIGNUP`(단일 라우트 내부 스텝 머신) → 성공 시 `AUTH_APPROVED`(D05b)

## 2. 4단계 흐름과 단계별 검증 규칙

```
D01 ──「기사 회원가입」──▶ ① 휴대폰 번호
                            │ 「다음」 → isPhoneRegistered(하이픈 포맷)
                            ├─ 조회 실패 → NoticeBanner(ERROR) + 재시도, 화면 유지
                            ├─ true(기가입) → NoticeBanner(INFO) + CTA가 「로그인하러 가기」 → onBack
                            └─ false(신규) ─▶ ② 프로필
                                                │ 포커스 아웃 / 「다음」 → isNicknameTaken
                                                ├─ taken → 필드 errorText + CTA disabled
                                                ├─ 조회 실패 → 배너 + 진행 차단(「다음」 재시도)
                                                └─ 사용 가능 ─▶ ③ 택시 정보
                                                                 │ 「다음」(로컬 검증만, 서버 호출 없음)
                                                                 ▼
                                                              ④ 휴대폰 인증
                                                                 │ 진입 시 자동 1회 발송
                                                                 │ 「인증하고 가입 완료」
                                                                 │   → verifyPhoneCode → token
                                                                 │   → signUp(form.copy(verificationToken))
                                                                 ├─ 실패 → 배너(서버 메시지)
                                                                 └─ 성공 → AUTH_APPROVED(D05b)

back(버튼 · 시스템 공통, BackHandler): ④ → ③ → ② → ① → popBackStack(D01)   ※ 입력값 전부 보존
```

**왜 인증이 마지막인가** — 서버 `verificationToken` TTL 10분. 인증을 ①·②에 붙이면 ②③ 입력 중 만료돼 제출이 `USER207`(휴대폰 인증 필요)로 터진다. ④에서 인증 성공 직후 **사용자 입력을 끼우지 않고** 곧바로 `signUp` 을 호출해 토큰 수명 안에서 제출을 끝낸다 (`SignUpViewModel.verifyAndSignUp` 단일 코루틴).

| 단계 | 필드 | 규칙 (CTA 활성 조건) |
|------|------|----------------------|
| ① | 휴대폰 | 숫자만 입력(최대 11자리), `^01[016-9]\d{7,8}$`. 제출 시 `formatPhoneNumber`로 3-4-4(또는 3-3-4) 하이픈 포맷 |
| ② | 이름 | 비어있지 않음 |
| ② | 닉네임 | 형식 `^[가-힣a-zA-Z0-9]{2,10}$` **+ `isNicknameTaken == false`** (아래 3절) |
| ② | 아이디 | `^[a-z][a-z0-9_]{3,19}$` + 입력 필터(소문자화 · 허용문자만 · 20자) |
| ② | 비밀번호 | 8~64자 + 영문 · 숫자 · 특수문자 각 1+ , 마스킹 |
| ③ | 차종 · 번호판 · 운수종사자 번호 | 비어있지 않음 |
| ③ | 좌석수 | 정수 ≥ 2 (1자리 숫자 필터) |
| ④ | 인증번호 | `codeSent == true` **and** 6자리 **and** ③ 검증 유효 |

스텝 · 전 입력값(인증번호 포함) `rememberSaveable` — 스텝 왕복 · 프로세스 재생성에도 보존.

### ② 닉네임 중복 선조회 (요구 4)
- **두 지점에서** 확인: 닉네임 필드 **포커스 아웃**(새로 추가한 `MoyeotaTextField.onFocusChange`) + **「다음」 직전**(`ensureNicknameChecked`).
- 「사용 가능」으로 확인이 끝난 값(`nicknameAvailable == nicknameTrimmed`)은 재조회 없이 통과 → 중복 호출 없음.
- 닉네임을 고치면 `resetNicknameCheck()` 로 이전 결과 즉시 무효화.
- 피드백: 조회 중 helperText 「확인하고 있어요」 / 사용 가능 「쓸 수 있는 닉네임이에요」 / 중복 errorText 「이미 누군가 쓰고 있는 닉네임이에요」 + CTA disabled.
- 조회 **실패**(네트워크)는 진행시키지 않는다 — 배너 + 「다음」 재시도. 제출 때 `USER108` 로 터지는 것보다 ②에서 막는 쪽이 낫다.

### ④ 발송 방식 선택: **진입 시 자동 1회 발송**
번호는 ①에서 이미 받았으므로 한 번 더 누르게 할 이유가 없다 → `LaunchedEffect(step, phoneDigits)` 로 ④ 진입 시 1회 발송. 중복 발송은 3중 가드로 막는다: `codeSent` 플래그(ViewModel 보유 → ③↔④ 왕복해도 재발송 없음) · `sendingCode` · `resendCooldown > 0`. 자동 발송이 **실패**하면 `codeSent` 가 false 로 남아 버튼 라벨이 「인증번호 받기」로 바뀌고 명시적 재시도가 된다.

## 3. 에러 · 쿨다운 처리

| 상황 | 표현 |
|------|------|
| 발송 실패 (레이트리밋 1분 · 일일 5회 · 기가입 번호 등) | CTA 위 `NoticeBanner(ERROR)` — **서버 예외 message 그대로**, 한국어 폴백 보유. `codeSent` 유지 안 함 → 「인증번호 받기」로 재시도 |
| 인증번호 불일치 · 만료 · 횟수 초과 | OTP 6칸 테두리 `Danger500`(`codeInvalid`) + CTA 위 배너에 서버 message. 숫자를 고치면 `onCodeEdited()` 로 즉시 해제 |
| 가입 제출 실패 | 배너에 서버 message. 토큰은 1회용이므로 `codeSent = false`, 쿨다운 0 으로 풀어 **바로 재전송** 가능. 입력값 전부 보존 |
| ① 번호 조회 실패 | ① 배너 + 「다음」 재시도 |
| ② 닉네임 조회 실패 | ② 배너 + 「다음」 재시도 |

**쿨다운**: `RESEND_COOLDOWN_SECONDS = 60`(서버 재전송 대기 1분과 동일). 발송 성공 직후 60 으로 세팅, `cooldownJob` 이 1초마다 감소. 버튼 라벨 `재전송 (45초)` 로 남은 초를 표시하고 `enabled = false`. 보조 문구도 「문자가 안 오면 45초 뒤에 재전송할 수 있어요」 → 0 이 되면 「재전송을 눌러 주세요」. ①에서 번호를 고치면 `resetPhoneCheck()` 가 발송 상태 · 쿨다운 · 오입력 표시를 모두 리셋한다(다른 번호의 코드를 남기지 않음). 재전송 시 입력해 둔 숫자는 비운다.

## 4. 요구 3 — D02 입력 UI 재사용 (재사용함)

- D02 `PhoneVerifyScreen.kt` 안에 private 으로 있던 6칸 입력을 `AuthCommon.kt` 의 `internal fun OtpCodeInput` 으로 끌어올려 **D02 와 가입 ④가 동일 컴포저블**을 쓴다(박스 60dp = 터치 타깃 하한 유지).
- ④ 스텝 본문 전체는 `PhoneCodeStep.kt` 의 `PhoneCodeStepFields`(헤드라인 · 번호 표시 · `OtpCodeInput` · 상태 문구 · 재전송 버튼)로 분리 — 다른 가입 스텝들(`PhoneStepFields` 등)과 같은 "Column 안에 형제들을 emit" 패턴.
- D02 화면 **전체**를 그대로 쓰지는 않았다. D02 는 이름 입력 · 번호 입력 · 약관 체크 3개를 품은 독립 화면이고, ④는 번호를 ①에서 확정해 받아 수정 불가로 표시한다 — 레이아웃 요구가 달라 **입력 UI 단위로 공유**하는 선택이 맞다.
- D02 는 `requestOtp`/`verifyOtp` 가 사라지므로 새 경계면으로 이관했다: `requestPhoneVerification`, `verifyPhoneCode`(토큰 반환). `PhoneVerifyRoute.onVerified` 가 `(verificationToken: String) -> Unit` 으로 바뀌었지만 `AuthGraph.kt` 의 `onVerified = { navController.navigate(...) }` 는 암묵 `it` 로 **수정 없이 컴파일된다**. 라우트는 여전히 고아 상태 — D02 피그마 대조용 보존.

## 5. 디자인 규칙

- 다크 모드 고정. 색상은 `MoyeotaColor` 토큰만 — `grep "Color(0x" feature/auth/*.kt` → 0건.
- 재사용 컴포넌트: `MoyeotaTopBar` · `StatusBarMock` · `MoyeotaTextField` · `PrimaryCtaButton` · `SecondaryButton` · `NoticeBanner` · `StepProgressBar`(4단계로 total 변경) · `OtpCodeInput`.
- 본문 17sp(`BodyLg`) 이상, 인증번호 숫자 24sp(`NumberMd`), OTP 박스 높이 60dp.
- 새 UI 요소를 `core/designsystem` 에 만들지 않았다 — 추가한 것은 `MoyeotaTextField` 의 선택 파라미터 하나뿐.

## 6. 컴파일 결과

```
./gradlew :presentation:compileDebugKotlin --rerun-tasks   →  BUILD SUCCESSFUL
```
(유일한 경고는 기존 `Bars.kt:91` 의 `Icons.Rounded.ReceiptLong` deprecation — 이번 변경과 무관)

`:app:assembleDebug` 는 지시대로 돌리지 않았다. 참고로 작성 시점의 `DummyDriverRepository.kt` 는 아직 `requestOtp`/`verifyOtp` 를 override 하고 있어(api-integrator 작업 중) data 모듈이 깨진 상태 — presentation 과는 무관하다.

## 7. 리더에게 — 확인 필요

1. **api-integrator KDoc 의 단계 번호가 다르다.** `DriverSignUpForm` / `DriverRepository` 주석은 「①휴대폰 확인 → ②문자 인증 → ③프로필 → ④택시 정보」로 적혀 있고, 내가 구현한 presentation 은 지시대로 「①휴대폰 → ②프로필 → ③택시 → ④문자 인증」이다. **시그니처는 동일**해서 컴파일 · 동작에 문제가 없으나, 토큰 10분 만료를 피하려는 설계 의도와 주석이 어긋난다 — `domain/` 은 내 소유가 아니라 손대지 않았다. 주석 정정이 필요하다.
2. `_workspace/30_sync_contract.md` · `29a_auth_diff.md` 가 레포에 없다. 문자 인증 에러코드별 UX 권장안을 못 봤으므로, 현재는 **서버 예외 message 를 그대로 노출**하는 방식이다. 코드별 문구 매핑이 필요하면 해당 절을 주면 반영한다.

## 8. qa-verifier 참고 (재현 경로)

- 진입: D01 → 「기사 회원가입」. 상단 진행률이 `1 / 4`.
- 기가입 분기: ①에 `01000009999` → 「다음」 → INFO 배너 + 「로그인하러 가기」.
- 닉네임 중복: ②에서 닉네임 입력 후 **다른 필드 탭**(포커스 아웃) → 중복이면 즉시 errorText + CTA disabled.
- 쿨다운: ④ 진입 직후 「재전송 (60초)」 비활성 → 카운트다운 → 0 에서 「재전송」 활성.
- 오입력: ④에 아무 6자리 → 「인증하고 가입 완료」 → 박스 빨강 + 배너(서버 메시지). 숫자 한 자 고치면 빨강 해제.
- 입력 보존: ④까지 입력 후 시스템 back 3회 → ①, 다시 전진 시 ②③ 입력 유지 · ④ 재발송 없음.
- ①에서 번호를 고치면 ④의 발송 상태 · 쿨다운이 리셋되는지 확인.
