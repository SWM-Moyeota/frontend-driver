---
name: compose-feature
description: "모여타 기사 앱의 Compose 화면 구현 레시피. 화면 신규 생성, 기존 화면 수정, UI 변경, 컴포넌트 추가, 네비게이션 연결, 피그마 디자인 반영 등 presentation/ 또는 core:designsystem을 건드리는 모든 작업 시 반드시 이 스킬을 사용할 것. 화면 ID(D01~D22, D03b, D05b, D16b)나 피그마 노드가 언급되면 이 스킬을 사용할 것."
---

# 모여타 기사 앱 화면 구현 레시피

## 모듈 지도

| 모듈 | 역할 | 규칙 |
|------|------|------|
| `presentation/` | 화면·ViewModel·네비게이션 (`com.moyeota.driver.presentation`) | domain만 의존, data 직접 참조 금지 |
| `core/designsystem/` | 공용 컴포넌트·테마 (`com.moyeota.core.designsystem` — 승객 앱 v15 포팅) | 도메인 모델 참조 금지 |
| `domain/` | 모델·Repository 인터페이스 (`com.moyeota.driver.domain`) | UI에서 보는 유일한 데이터 계약 |
| `app/` | MainActivity·AppContainer(수동 DI) (`com.moyeota.driver.app`) | Repository 인스턴스 공급처 |

화면 스펙의 원천은 `docs/FIGMA-SCREENS.md` — 화면 ID(D01~D22)와 피그마 노드 ID 매핑, 공통 규칙, 화면별 스펙(진입 경로·인터랙션·검증 조건)이 있다.

## 화면 구현 순서

1. **스펙 확인**: `docs/FIGMA-SCREENS.md`에서 해당 화면 ID의 스펙과 노드 ID를 읽는다
2. **피그마 디테일 확인**: 시각 디테일(레이아웃·간격·텍스트)이 필요하면 figma-design-to-code 스킬을 로드한 뒤 해당 노드에 `get_design_context`(fileKey `nOqpTUmzAhjBW1SDLVd24A`)를 호출한다. 반환 코드는 React+Tailwind 참고용 — Compose로 번역하고 프로젝트 컴포넌트·토큰으로 치환한다
3. **유사 화면 모방**: `presentation/feature/` 하위에서 가장 비슷한 기존 화면을 찾아 구조를 따른다. 새 스타일을 발명하지 않는다. 첫 구현이라 기존 화면이 없으면 승객 앱(`../frontend/presentation/`)의 유사 화면을 참고한다
4. **Screen 작성**: 스테이트리스 Composable. 데이터·콜백은 전부 파라미터로 받는다
5. **Route 작성** (서버 데이터 필요 시): ViewModel + UiState를 같은 파일에 두는 패턴
6. **라우트 등록**: `Routes.kt`에 상수 추가(화면 ID 주석 포함) → `MainNavGraph.kt`에 composable 등록
7. **빌드 확인**: `./gradlew :app:assembleDebug --console=plain`

## ViewModel 패턴

서버 연동 화면은 이 형태를 그대로 따른다 (승객 앱 ExploreRoute.kt와 동일 패턴):

```kotlin
class FooViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val data: ...) : UiState
        data class Error(val message: String) : UiState   // 한국어 사용자 메시지
    }
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }
    fun refresh() { viewModelScope.launch { /* try-catch로 Error 매핑 */ } }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { FooViewModel(repository) }
        }
    }
}
```

- Hilt 미도입 — `viewModel(factory = ...)` + `AppContainer`에서 내려온 repository 파라미터를 쓴다
- 로딩/에러 UI는 `presentation/core/LoadState.kt`의 `LoadingBox` / `ErrorBox`(onRetry 필수)를 재사용한다
- `init { refresh() }`는 화면 재진입 시에만 재로드된다. 액션 후 갱신이 필요하면 액션 성공 콜백에서 명시적으로 refresh를 호출한다

## 디자인 시스템 — 기사 앱 규칙 (피그마 공통 규칙)

- **다크 모드 고정** (야간·거치대 시인성). `MoyeotaDriverTheme`이 darkColorScheme을 적용한다. 라이트 배경/잉크 색을 화면에서 직접 쓰지 않는다
- 색상은 `MoyeotaColor`(theme/Color.kt — 다크 토큰 포함)만 사용. hex 하드코딩 금지
- 승객 v15 컴포넌트 포팅본 우선 재사용: `PrimaryCtaButton`, `SecondaryButton`, `MoyeotaTopBar`, `MoyeotaBottomBar`(+`MoyeotaTab`), `MoyeotaTextField`, `MoyeotaChip`, `StatusBadge`, `NoticeBanner`, `AvatarCircle`, `SheetHandle`, `MapPlaceholder`, `BackArrowIcon`, `StatusBarMock` 등
- 본문 최소 17sp, 금액·남은 거리 같은 핵심 숫자는 24~34sp, 터치 타깃 최소 60dp 높이
- 운행 화면(D13·D15)은 화면 꺼짐 방지(`FLAG_KEEP_SCREEN_ON`) 유지, 문구는 한 줄 3어절 이내
- 2개 이상 화면에서 쓰이는 새 UI 요소만 `core/designsystem/component/`로 승격. 단일 화면 전용이면 화면 파일 내 private Composable

## 네비게이션 규칙

- `Routes.kt` 상수는 `"영역/화면"` 소문자-하이픈 형식, 오른쪽에 `// 화면ID` 주석
- 하단탭(홈·콜·정산·마이)은 **D06·D07·D09·D12·D17·D19·D20에서만** 노출
- 배차 이후 운행 플로우(D11, D13~D16)는 스택 초기화(`popUpTo` inclusive) + 뒤로가기 차단(`BackHandler`)
- 그 외 뒤로가기는 항상 직전 화면, 입력값 보존
- CTA 활성 조건: 필수 입력·확인 충족까지 disabled. 제출 중 비활성+로딩. 콜 수락(D10)은 15초 카운트다운 동안만 활성
- 에러: 필드 단위는 필드 하단 danger 문구, 화면 단위는 CTA 위 `NoticeBanner`(error)

## 완료 체크리스트

- [ ] `./gradlew :app:assembleDebug` 통과
- [ ] Routes 상수·NavGraph 등록·navigate 호출 3자 일치
- [ ] 하드코딩 색상 없음 (`MoyeotaColor` 외 `Color(0x` 검색으로 확인)
- [ ] 다크 모드 위반 없음 (라이트 배경·검정 텍스트 잔재)
- [ ] 서버 화면이면 Loading/Error/Success 3상태 모두 렌더링 가능
- [ ] 변경 파일 목록 + 화면 진입 경로를 산출물 파일에 기록
