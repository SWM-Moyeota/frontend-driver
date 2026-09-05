# 12 · compose-builder — MVP 간소 가입 화면 + 로그인 수정

## 변경 파일
| 파일 | 변경 |
|------|------|
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/SignUpScreen.kt` | **신규** — MVP 간소 가입 화면 (SignUpViewModel + SignUpRoute + SignUpScreen). 아이디·비밀번호 + 차종·좌석수·차량 번호·운수종사자 번호 한 화면, VehicleScreen(D04) 폼 스타일 준수 |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/AuthGraph.kt` | `composable(Routes.AUTH_SIGNUP)` 등록. D01 「기사 회원가입」 목적지 AUTH_PHONE → AUTH_SIGNUP. D02~D04 라우트는 화면 보존을 위해 유지 |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/LoginScreen.kt` | D01 휴대폰 번호 필드 → **아이디** 필드 (라벨·placeholder·검증·Ascii 키보드). ViewModel/Screen 파라미터명 phone → loginId. 실패 문구 "아이디 또는 비밀번호가 맞지 않아요" |

## 진입 경로
- 앱 실행 → D01 로그인 → 「기사 회원가입」 → **AUTH_SIGNUP** (간소 가입)
- 가입 성공 → `Routes.AUTH_APPROVED`(D05b) → 「영업 시작하기」 → HOME (기존 스택 정리 로직 유지: `popUpTo(AUTH_LOGIN) inclusive`)
- 가입 화면 뒤로가기 → D01 복귀 (D01 입력값 보존)

## 검증 규칙 적용 내역 (백엔드 @Pattern 동일)
- **loginId**: `^[a-z][a-z0-9_]{3,19}$` — `isValidLoginId()` (SignUpScreen.kt, 로그인 CTA 조건에도 공용). 입력 필터: 소문자화 + `[a-z0-9_]`만 허용 + 20자 제한. helperText "영소문자 시작, 4~20자 영소문자 · 숫자 · _", 형식 미충족 시 필드 danger 문구
- **password**: 8~64자 + 영문·숫자·특수문자 각 1개 이상 — `isValidPassword()`. `PasswordVisualTransformation` 마스킹, helperText "8자 이상, 영문 · 숫자 · 특수문자 포함"
- **seats**: 숫자만 입력(1자리), `toIntOrNull() >= 2`. helperText "2석 이상", 미충족 시 danger 문구
- **vehicleType / plateNumber / qualificationNumber**: 공백 아님 필수
- **CTA**: 전 필드 유효할 때만 활성, 제출 중 loading + 전 필드 disabled
- **실패 처리**: `repository.signUp` 예외 message를 CTA 위 `NoticeBanner(ERROR)`로 노출, 입력값 보존 (rememberSaveable)
- **ViewModel**: 액션형 단순 UiState (`submitting` / `screenError`) — 스킬 패턴의 idle/submitting/error 축약형
- **로그인(D01)**: CTA 조건 = loginId 형식 유효 + 비밀번호 8자 이상. `repository.login(loginId, password)` 파라미터명 정리 완료

## 빌드
- `./gradlew :presentation:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL**
- (:app 전체 빌드는 :data 병렬 작업 중이라 미실행 — 리더 지시 준수)

## qa-verifier 검증 요청
- 진입: 앱 실행 → D01 → 「기사 회원가입」
- 확인 포인트: (1) 필드별 helper/danger 문구, (2) 전 필드 유효 전 CTA 비활성, (3) DummyDriverRepository.signUp 성공 시 D05b 도달 및 D05b 「영업 시작하기」로 홈 이동 + 뒤로가기 시 가입 화면 미노출, (4) 다크 토큰 위반 없음
