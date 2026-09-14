# 28 · compose-builder — 영업 시작 위치 권한 게이트 (presentation)

지시서 `_workspace/28_location_guard_input.md` 의 **4번(presentation)** 항목만 구현.
`domain/`·`data/`·`app/` 및 `Routes.kt`·`MainNavGraph.kt` 는 건드리지 않았다.

## 변경 파일 (4개, 전부 `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/home/`)

| 파일 | 변경 |
|------|------|
| `HomeComponents.kt` | 위치 권한 헬퍼(`LocationPermissions`, `Context.hasLocationPermission()`, `Context.openAppSettings()`)와 액션 버튼이 붙는 `HomeNotice` 배너 추가 |
| `HomeRoute.kt` | ViewModel 에 `startDutyError` 상태 + `clearStartDutyError()` 추가, 영업 시작 실패를 전체 Error 화면 대신 배너로 강등. Route 에 권한 게이트·권한 런처·재개 시 권한 재확인 추가 |
| `HomeOffDutyScreen.kt` (D06) | `startDutyError` · `locationPermissionRejected` · `onOpenAppSettings` 파라미터 추가, CTA 위 안내 배너 |
| `HomeOnDutyScreen.kt` (D07) | `locationPermissionMissing` · `onOpenAppSettings` 파라미터 추가, 헤더 배지/배너/콜 카드 문구가 권한 상태를 반영 |

화면 진입 경로: `Routes.HOME` (하단탭 홈). D06/D07 은 `summary.dutyStatus` 로 분기 — 기존과 동일.

## 권한 흐름 (D06 「영업 시작하기」)

`onStartDuty` 는 항상 `context.hasLocationPermission()`(FINE 또는 COARSE) 을 먼저 읽는다.

| 상황 | 동작 |
|------|------|
| **권한 있음** | 바로 `viewModel.startDuty()` |
| **권한 없음(최초)** | `RequestMultiplePermissions` 런처로 시스템 다이얼로그 → `startDuty()` 호출 안 함 |
| **요청 후 허용** | 콜백에서 `locationGranted = true` 후 **이어서 `startDuty()` 자동 진행** (CTA 재탭 불필요) |
| **요청 후 거부** | `permissionRejected = true` → CTA 위 ERROR 배너 "위치 권한을 허용해야 영업을 시작할 수 있어요" + 「설정에서 허용」. `startDuty()` 미호출 |
| **영구 거부** | 런처가 다이얼로그 없이 즉시 거부로 돌아오므로 위와 동일한 배너 경로. `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:` uri 가 유일한 출구 |
| **설정에서 허용 후 복귀** | `LifecycleResumeEffect` 가 권한을 재확인 → 배너 사라짐. 영업 시작은 사용자가 CTA 를 다시 눌러 진행 |

**권한이 없으면 어떤 경로로도 `startDuty()` 가 호출되지 않는다.**

## 영업 시작 실패 (권한은 있는데 좌표가 없거나 서버 실패)

`UiState.Success.startDutyError` 로 홈을 유지한 채 CTA 위 배너만 띄운다 (기존엔 화면 전체 `UiState.Error`).
- `DriverLocationUnavailableException` → "위치를 확인하는 중이에요 · 실외에서 잠시 후 다시 시도해 주세요"
- 그 밖 → "영업을 시작하지 못했어요. 다시 시도해 주세요."
- `setDutyStatus` 성공 후의 재조회 실패만 기존처럼 전체 Error(재시도 버튼) 로 남긴다.

## 영업 중(D07) 권한 회수 대응

`LifecycleResumeEffect` 가 재개할 때마다 권한을 다시 읽는다(권한 변경 브로드캐스트가 없어 폴링 대신 재개 시점 확인).
권한이 없으면:
- 헤더 배지 `콜 대기`(INFO) → `위치 꺼짐`(ERROR)
- 지도 위에 ERROR 배너 "위치 권한이 꺼져 콜을 받을 수 없어요" + 「설정에서 허용」
- 콜 카드 보조 문구 → "위치 권한을 다시 켜면 콜이 들어와요"

서버는 하트비트 중단 → TTL 로 자동 오프라인되므로 화면에서 강제 종료 처리는 하지 않는다.

## 제약 준수

- **새 의존성 없음** — `Context.checkSelfPermission`(minSdk 24), `androidx.activity.compose`(navigation-compose 로 이미 들어와 있고 모듈 내 `BackHandler` 사용 중)
- 하드코딩 색상 없음(`grep "Color(0x"` → 0건), 다크 토큰만 사용
- 배너 본문 17sp(`BodyLg`), 「설정에서 허용」 버튼 60dp — 거치대 시인성 규칙 충족
  (designsystem `NoticeBanner` 는 액션 버튼을 못 받고 본문이 13sp 라 home 안에서만 `HomeNotice` 로 확장. D06·D07 두 화면 공용이지만 home 전용이라 승격 대상 아님)

## 컴파일

`./gradlew :presentation:compileDebugKotlin` → **BUILD SUCCESSFUL** (domain 의 `DriverLocationUnavailableException` 반영 상태에서 확인)

## qa-verifier 요청

진입 경로: 앱 실행 → 로그인 → 홈(D06). 권한 거부 상태로 「영업 시작하기」 탭 시 다이얼로그 → 거부 → 배너 확인 → 「설정에서 허용」 → 권한 켜고 복귀 → 재탭 시 영업 시작.
영업 중 상태에서 설정으로 권한 회수 후 앱 복귀 → D07 배너/배지 전환 확인.
