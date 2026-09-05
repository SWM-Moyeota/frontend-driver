# 경계면 계약 (리더 확정)

## 데이터 계약
- `domain/src/main/kotlin/com/moyeota/driver/domain/model/DriverModels.kt` — 전 도메인 모델
- `domain/src/main/kotlin/com/moyeota/driver/domain/repository/DriverRepository.kt` — 유일한 데이터 계약
- 구현체: `data/.../DummyDriverRepository` (백엔드 미구현 — 더미 연동). AppContainer가 주입.

## 네비게이션 계약
- `presentation/core/Routes.kt` — 전 라우트 상수 (리더 소유, 에이전트 수정 금지)
- `presentation/core/MainNavGraph.kt` — 그룹 그래프 조립 (리더 소유, 에이전트 수정 금지)
- 각 에이전트는 자기 feature 디렉토리의 `{group}Graph.kt` 스텁을 실제 화면으로 교체:
  - auth: `authGraph` D01,D02,D03,D03b,D04,D05,D05b
  - home: `homeGraph` D06,D07(HOME 상태분기),D08
  - call: `callGraph` D09,D10,D11,D12,D22
  - trip: `tripGraph` D13,D14,D15,D16,D16b(D16 내 키패드)
  - settlement: `settlementGraph` D17,D18
  - history: `historyGraph` D19,D20,D21
- 확장 함수 시그니처 유지: `fun NavGraphBuilder.{group}Graph(navController: NavHostController, repository: DriverRepository)`

## 공용 컴포넌트 (core:designsystem — 에이전트 수정 금지)
- `PrimaryCtaButton(text, onClick, modifier, enabled, loading)` / `SecondaryButton(text, onClick, modifier, enabled)` / `SafetyButton`
- `MoyeotaTopBar(title, onBack, modifier, actions)` / `MoyeotaBottomBar(selected, onSelect)` / `MoyeotaTab { HOME("홈"), CALLS("콜"), SETTLEMENT("정산"), MYPAGE("마이") }` / `StatusBarMock` / `BackArrowIcon`
- `MoyeotaTextField(value, onValueChange, modifier, label, placeholder, errorText, helperText, keyboardOptions, visualTransformation, singleLine, enabled, trailing)`
- `NoticeBanner(kind: NoticeKind, text)` / `StatusBadge(kind, text)` / `NoticeKind { INFO, ERROR, SUCCESS, WAITING }`
- `AvatarCircle` / `MoyeotaChip` / `SheetHandle` / `PageDots` / `MapPlaceholder(modifier)` / `NaverMapView`
- presentation/core: `LoadingBox`, `ErrorBox(message, onRetry)`, `TabStateScaffold(selectedTab, onTabSelect){content}`, `BackStateScaffold(title, onBack){content}`
- 색: `MoyeotaColor`(다크 토큰), 타이포: `MoyeotaType` (본문 BodyLg 17sp, 핵심 숫자 NumberMd/Lg/Xl 24~34sp)

## 하단탭 라우팅 (onTabSelect 공통 구현)
- HOME → Routes.HOME, CALLS → Routes.CALL_LIST, SETTLEMENT → Routes.SETTLEMENT, MYPAGE → Routes.RATING (마이 화면 미작성 — 평점을 임시 마이 탭으로)

## 인가 계약 (2026-09-02 리더 확정)
- 도메인 모델 추가:
  `data class DriverSignUpForm(loginId, password, vehicleType, seats: Int, plateNumber, qualificationNumber)`
- DriverRepository 변경:
  - `login(loginId: String, password: String): LoginResult` — 파라미터명 phone → loginId (시그니처 유지)
  - 추가 `suspend fun signUp(form: DriverSignUpForm): LoginResult` — 계정 생성 → 자동 로그인 → 기사 등록 → verify → 차량 등록 일괄 수행
  - 추가 `suspend fun logout()`
  - requestOtp/verifyOtp/checkQualifications/registerVehicle 는 유지 (D02~D04 화면 보존용, MVP 플로우 미사용)
- 라우트: `Routes.AUTH_SIGNUP = "auth/signup"` 추가 (리더 완료). MVP 플로우: D01 「기사 회원가입」 → AUTH_SIGNUP → 성공 시 AUTH_APPROVED(D05b) → HOME
- 백엔드 인가 사양: POST /api/v1/auth/register(UserRegisterRequest, 201 UserResponse) · /login({loginId,password} → {accessToken,refreshToken}) · /reissue({refreshToken}) · /logout({refreshToken}, 204). 이외 전 API Bearer 필수 (SecurityConfig anyRequest().authenticated())
- 클라 검증 규칙(백엔드 @Pattern 동일): loginId `^[a-z][a-z0-9_]{3,19}$`, password 8~64 + 영문·숫자·특수문자 각 1+, seats ≥ 2
- 토큰: data 모듈 인메모리 TokenStore + OkHttp Authorization 인터셉터, 401 시 reissue 1회 재시도. 앱 재실행 시 재로그인 (MVP)
- MVP 기본값(자동 채움): name="기사", birthDate 고정 과거일, phoneNumber "010-0000-0000" 형식 통과값, email "{loginId}@moyeota.local", gender 기본값 — RemoteDriverRepository 내부에서 채움

## 가입 3단계(phone-first) 계약 (2026-09-02 리더 확정, 화면만 — API는 사용자가 백엔드 구현 예정)
- DriverSignUpForm 확장: phoneNumber(1단계)·name(2단계) 추가 (loginId/password/차량·자격 유지)
- DriverRepository 추가: `isPhoneRegistered(phoneNumber): Boolean` — 더미 폴백: 숫자 기준 9999로 끝나면 true(기가입 분기 테스트용), 그 외 false
- 가입 플로우: ①휴대폰 입력 → isPhoneRegistered → true면 "이미 가입된 번호" 안내 + 로그인 유도(D01 복귀) / false면 ②프로필(이름·아이디·비밀번호) → ③택시 정보(차종·좌석수·번호판·운수종사자 번호) → signUp(form)
- 백엔드 예정 API: GET /api/v1/users/exists?phoneNumber=… (permitAll, {exists:Boolean}) — 생기면 Remote 실연동 교체 (RemoteDriverRepository TODO 주석 참조)
- 전화번호 검증: 백엔드 @Pattern `^01[016-9]-?\d{3,4}-?\d{4}$`

## 긴급 신고 계약 (2026-09-06 리더 확정)
- 흐름: D15 신고 버튼 **3초 홀드** → ①서버 저장(POST /api/v1/reports {partyId, latitude, longitude} → {reportId}) ②112 다이얼러(ACTION_DIAL, 번호 입력된 상태 — 통화 버튼만 누르면 연결) → 앱 복귀 시 "실제 통화 여부" 다이얼로그 → PATCH /api/v1/reports/call-result {called} (204, 서버가 내 최근 신고에 기록)
- 도메인 (리더 반영 완료): `reportEmergency(tripId): Long` (위치는 데이터 계층이 locationSource 최신값으로 채움), `confirmEmergencyCall(called: Boolean)`. Dummy 구현 완료
- SafetyButton (리더 반영 완료): 시그니처 변경 → `SafetyButton(text, onHoldComplete, modifier, holdMillis=3000, onShortPress)` — 홀드 진행 게이지 내장, 짧은 탭은 onShortPress(안내용)
- 원칙: 신고 저장 실패와 무관하게 112 다이얼은 **반드시** 열린다 (통화 최우선). 다이얼로그 미응답 상태는 화면 유지
