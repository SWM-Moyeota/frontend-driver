# 33. compose-builder — QA 결함 수정 (F1·F2·F3·F4)

- 브랜치: `feature/backend-sync-sms-auth` (새 브랜치·커밋 없음)
- 범위: `presentation/` 만. `domain/`·`data/`·`app/`·`Routes.kt`·`MainNavGraph.kt` 무변경
- 검증: `./gradlew :presentation:compileDebugKotlin` → **BUILD SUCCESSFUL** (경고 0)

## 변경 파일

| 파일 | 내용 |
|---|---|
| `presentation/core/UserMessage.kt` | **신규** — 서버 메시지 노출 게이트 `Throwable?.userMessageOr(fallback)` |
| `feature/auth/LoginScreen.kt` | F1 — 로그인 실패 문구를 리포지토리 메시지로, 필드/화면 오류 분리 |
| `feature/home/HomeRoute.kt` | F1 — 영업 시작·홈 로딩 실패 문구 / F2 — `isTaxiServiceEnabled()` 상태 |
| `feature/home/HomeOffDutyScreen.kt` | F2 — 경고 배너 + 영업 시작 CTA 비활성 + 새로 고침 |
| `feature/home/HomeOnDutyScreen.kt` | F2 — 경고 배너 + 배지(`콜 중단`) + 콜 카드 문구 |
| `feature/auth/SignUpScreen.kt` | F3 — `verificationToken`·쿨다운 보존, 「다시 제출」 경로 |
| `feature/auth/PhoneCodeStep.kt` | F3 — 재발송 라벨·만료 판별 / F4 — 부제 상태 기반 |
| `feature/auth/PhoneVerifyScreen.kt` | F1 파생 — 같은 메시지 게이트 적용(고아 D02 라우트) |

## F1 — 리포지토리 메시지가 화면까지 간다

새 게이트 `presentation/core/UserMessage.kt:23` — `e.message` 를 우선 쓰되
**비어 있음 / 120자 초과 / 여러 줄 / `{`·`[`·`<` 로 시작 / 한글 없음** 이면 기존 일반 문구로 폴백한다.
(서버 원문 덤프·JSON 본문·플랫폼 영문 예외가 화면에 그대로 새는 것을 막는 조건이다.)

- `LoginScreen.kt:78-82` — `catch (CancellationException) { throw e }` 추가 후 `loginFailureState(e)`
- `LoginScreen.kt:97-103` — 메시지에 "비밀번호"가 있으면 필드 오류, 그 밖은 `screenError`
- `LoginScreen.kt:61`·`138`·`185-186` — `screenError` 를 CTA 위 `NoticeBanner(ERROR)` 로 렌더
- `HomeRoute.kt:160-163` — `startDutyErrorMessage` 의 `else ->` 를 `e.userMessageOr(...)` 로
- `HomeRoute.kt:80`·`114` — 홈 로딩 실패도 같은 게이트(`HOME_LOAD_FAILED` 폴백)
- 파생 적용: `SignUpScreen.kt:156`·`212`·`254`·`300`·`341`, `PhoneVerifyScreen.kt:90`·`122`

QA D-1 재현 경로(배포 서버 `taxiEnabled=false` + 기사 미등록 계정 로그인)에서
"아이디 또는 비밀번호가 맞지 않아요" → **"서버에서 택시 서비스가 꺼져 있어요 · 운영자에게 문의해 주세요"**(배너).

## F2 — 기사 기능 비활성 경고 배너

- `HomeRoute.kt:53-59` — `UiState.Success.taxiServiceEnabled: Boolean = true`
- `HomeRoute.kt:188-192` — `load()` 안에서 1회 확인 (홈 진입)
- `HomeRoute.kt:142-153` — `refreshServiceAvailability()`, `HomeRoute.kt:243` 에서 `LifecycleResumeEffect` 로 재개 시 1회
  (첫 진입 시엔 아직 `Loading` 이라 no-op → 중복 호출 없음)
- `HomeRoute.kt:165-174` — `taxiServiceEnabled()`: 취소 외 예외는 `true` → **실패 시 배너 없음**
- `HomeOffDutyScreen.kt:118-124` — 배너 "서버에서 기사 기능이 꺼져 있어요 · 지금은 콜을 받을 수 없어요"
  + 액션 「서버 상태 새로 고침」(`viewModel::refresh`) / `:139` CTA `enabled = !taxiServiceDisabled`
- `HomeOnDutyScreen.kt:71-80`·`:89-93`·`:141` — 배지 `콜 중단`, 배너, 콜 카드 문구

CTA 를 **닫은** 이유: `taxiEnabled=false` 는 낙관 폴백(실패=true)을 통과한 **서버의 명시적 응답**이라
눌러도 404 로 확정 실패한다. 대신 「서버 상태 새로 고침」을 배너에 붙여 서버 복구를 그 자리에서 반영한다
(pull-to-refresh 가 없어 갇히는 길을 막는다).

## F3 — 제출 실패 시 토큰·쿨다운 보존

- `SignUpScreen.kt:116-129` — `verifiedToken: String?` · `verificationExpired: Boolean` 추가
- `SignUpScreen.kt:289-305` — 토큰을 들고 있으면 `verifyPhoneCode` 를 **건너뛰고** 바로 제출
- `SignUpScreen.kt:306-316` — 토큰을 state 에 먼저 커밋 (제출 실패 후 재시도가 이 값을 쓴다)
- `SignUpScreen.kt:340-351` — `afterSignUpFailure`: `submitting=false` 만 내리고
  **토큰·`codeSent`·쿨다운은 그대로**. 만료가 분명할 때만 토큰 폐기 + `codeSent=false` (쿨다운은 계속 흐른다)
- `PhoneCodeStep.kt:113-137` — `indicatesVerificationExpired()` 만료 판별(과탐지 회피)
- `SignUpScreen.kt:176-180` — 번호를 고치면 토큰 폐기(다른 번호의 토큰은 USER207)
- `SignUpScreen.kt:238-245` — 재발송 성공 시 토큰 폐기(새 코드로 다시 인증)

쿨다운을 끊는 코드(`cooldownJob?.cancel()` + `resendCooldown = 0`)를 제출 실패 경로에서 제거했다 —
이게 429(USER202) + 일일 5통 소모의 직접 원인이었다.

## F4 — 발송 실패 화면 문구 충돌

`PhoneCodeStep.kt:70-80` 부제를 상태 기반으로:

| 상태 | 부제 |
|---|---|
| 토큰 보유 | "인증은 끝났어요 · 가입 제출만 다시 하면 돼요" |
| 발송 중 | "{번호} 로 인증번호를 보내고 있어요" |
| 만료 | "인증이 만료됐어요 · 인증번호를 다시 받아 주세요" |
| 발송 완료 | "{번호} 로 6자리 인증번호를 보냈어요" |
| 미발송(발송 실패 포함) | "{번호} 로 인증번호를 보낼게요" |

`PhoneCodeStep.kt:89-103` 안내 줄도 만료·토큰 보유 분기를 추가하고, 만료 시에만 `Danger500`.

## 디자인 규칙 확인

- 하드코딩 색상 없음 (`Color(0x` 검색 0건) · `MoyeotaColor` 토큰만 사용
- 다크 모드 고정 유지 (라이트 배경·검정 텍스트 신규 도입 없음)
- 신규 컴포넌트 없음 — `NoticeBanner` · `HomeNotice` · `SecondaryButton` · `StatusBadge` 재사용
- 터치 타깃: `HomeNotice` 액션 60dp · CTA 68dp 유지

## qa-verifier 검증 요청 — 진입 경로

1. **F1 로그인**: 앱 실행 → D01 → 기사 미등록 계정(`qadriver01`/`Qatest1234!`) 로그인
   → CTA 위 배너에 서버 메시지. 401 대조군(`nosuchuser9`)은 비밀번호 필드 하단 문구 유지
2. **F1 영업 시작 / F2 배너**: 로그인 후 D06 → 배너 "서버에서 기사 기능이 꺼져 있어요…" + 「영업 시작하기」 비활성
   → 「서버 상태 새로 고침」 탭 시 재조회. (배너 없는 서버에서는 CTA 활성 + 실패 시 서버 메시지)
3. **F3/F4 가입 ④**: D01 「기사 회원가입」 → ①~③ 입력 → ④ 진입
   - 발송 404: 부제가 "…보낼게요"로 바뀜(F4), "보냈어요" 동시 노출 없음
   - 로컬 백엔드로 발송 성공 후 제출 실패(닉네임 중복 등): CTA 가 「다시 제출」, OTP 입력 비활성,
     「인증번호 다시 받기 (N초)」 쿨다운 유지 → `sms/send` 재호출 **없음**이 핵심 확인점
   - 만료 메시지로 실패: OTP 입력 초기화 + 쿨다운 잔여 초 표시 후 재발송 개방

## 범위 밖 / 후속

- `sms/send` **발송 실패** 시 쿨다운 미적용(32b 부수 관찰) — 404 상황에서 즉시 재시도를 막게 되므로 유지했다.
  서버 레이트리밋이 복구된 뒤 별도 판단 필요.
- `presentation/src/test` 소스셋 신설(빌드 설정 변경) — 계약대로 별도 PR.
  특히 `userMessageOr` 폴백 조건, `afterSignUpFailure` 의 토큰·쿨다운 보존, `indicatesVerificationExpired`
  세 개는 순수 함수라 단위 테스트가 바로 붙는다.
