# GRP/B 홈 · 영업 상태 (D06 · D07 · D08) — compose-builder 산출물

## 변경 파일 (presentation/feature/home/ 내부만 수정)

| 파일 | 내용 |
|------|------|
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/home/HomeGraph.kt` | 스텁 → 실제 등록. `Routes.HOME`(D06/D07 상태 분기) + `Routes.HOME_OFF_DUTY_CONFIRM`(D08). 하단탭 라우팅 헬퍼 포함 |
| `.../home/HomeRoute.kt` | `HomeViewModel`(UiState Loading/Success/Error) + `HomeRoute`. `getHomeSummary()`의 `dutyStatus`로 OFFLINE→D06, ONLINE→D07 분기. ONLINE이면 `getCalls()`로 합승 콜 수 집계(실패해도 홈은 표시) |
| `.../home/HomeOffDutyScreen.kt` | D06 스테이트리스 화면 (피그마 2514:520) |
| `.../home/HomeOnDutyScreen.kt` | D07 스테이트리스 화면 (피그마 2514:559) |
| `.../home/OffDutyConfirmRoute.kt` | D08 ViewModel + Route + 화면 (피그마 2514:607). `getActiveTrip()`으로 진행 중 운행 확인 |
| `.../home/HomeComponents.kt` | home 내부 공용: `PromotionBanner`, `DutyToggle`, `formatWon`, `formatOnlineMinutes` |

수정 금지 파일(Routes.kt, MainNavGraph.kt, core:designsystem, domain/data)은 건드리지 않음.

## 화면 진입 경로

- **D06 홈 — 휴무**: `Routes.HOME` 진입 시 `dutyStatus == OFFLINE`. (로그인 후 기본 상태 · D08 종료 후 복귀)
- **D07 홈 — 영업중**: D06 「영업 시작하기」 → `setDutyStatus(true)` → 재조회 후 같은 라우트에서 D07 렌더링
- **D08 영업 종료 확인**: D07 「영업 종료」 버튼 또는 상단 토글 off → `Routes.HOME_OFF_DUTY_CONFIRM`

## 인터랙션 연결

- D06 「영업 시작하기」 → `setDutyStatus(true)` → 명시적 refresh → D07 (제출 중 CTA 로딩)
- D07 합승 콜 카드 · 「콜 리스트 보기」 행 → `Routes.CALL_LIST` (D09)
- D07 「영업 종료」·토글 off → `Routes.HOME_OFF_DUTY_CONFIRM` (D08)
- D08 「영업 종료」 → `setDutyStatus(false)` → `navigate(HOME) { popUpTo(HOME) { inclusive } }` (HOME 재생성으로 휴무 상태 재조회)
- D08 「조금 더 운행」·뒤로가기 → `popBackStack()` → D07 복귀
- D08 「오늘 수익」 행 → `Routes.SETTLEMENT` (D17)
- D06·D07 프로모션 배너(`HomeSummary.promotionBanner` 있을 때 상시 노출) → `Routes.CALL_PROMOTION` (D22)
- D06·D07 하단탭(TabStateScaffold, selectedTab=HOME): HOME→HOME, CALLS→CALL_LIST, SETTLEMENT→SETTLEMENT, MYPAGE→RATING. D08은 탭바 없음(BackStateScaffold)
- D08 진행 중 운행(`getActiveTrip()` phase != COMPLETED) 있으면 종료 CTA disabled + WAITING 배너. 제출 실패는 CTA 위 ERROR 배너

## 규칙 준수

- 다크 고정 — `MoyeotaColor` 토큰만 사용, 하드코딩 hex 없음
- 핵심 숫자 NumberMd~Xl (오늘 수익 NumberLg 28sp, 건수·시간 NumberMd 24sp)
- 주 CTA 높이 68dp(홈) / 60dp(D08), 배너·행 60dp 이상 터치 타깃
- `./gradlew :presentation:compileDebugKotlin` 통과 확인

## 스펙 대비 미구현 항목

| 항목 | 사유 |
|------|------|
| D06·D07 위치 권한 거부 시 CTA disabled + 권한 안내 배너 | 권한 상태를 줄 데이터·플랫폼 계층 계약 없음 (Repository에 권한 API 부재) |
| D06 서류 만료 임박 warning 배너 | `HomeSummary`에 서류 만료 정보 없음 |
| D07 GPS 끊김 30초 error 배너 · 백그라운드 위치 전송 · 배터리 절약 경고 | 위치 스트림 계약 없음 (더미 리포지토리 단계) |
| D07 새 콜 푸시 수신 → D10 자동 표시 | 푸시/이벤트 스트림 계약 없음 |
| D07 지도 위 「대기 N분 · 반경 Nkm」 MapPill | 대기 시간·반경 데이터가 `HomeSummary`에 없음. 지도는 `MapPlaceholder` |
| D08 「취소 · 노쇼 N건」 행 | `HomeSummary`에 취소·노쇼 집계 없음 → 행 생략 (api-integrator에 필드 추가 제안 대상) |
| D08 「06:04 영업 시작」 시각 표기 | 영업 시작 시각 데이터 없음 → 보조 문구를 「오늘 영업 시간」으로 대체 |
| D08 미정산 운행 시 D16 유도 | 진행 중 운행이면 종료 차단(배너)까지만 구현. FARE_INPUT 단계 별도 유도는 미구현 |
| 지도 탭 → 수요 히트맵 상세 | 스펙상 미연결 |
