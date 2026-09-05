# GRP/F 평점 · 운행 이력 (D19 · D20 · D21) — compose-builder 산출물

## 변경 파일

| 파일 | 내용 |
|------|------|
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/history/HistoryGraph.kt` | 스텁 → 실제 화면 등록으로 교체. `HISTORY_DETAIL`은 `navArgument("tripId", StringType)`. 하단탭 공통 라우팅(`navigateToTab`) 포함 |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/history/RatingScreen.kt` | D19 내 평점 — RatingRoute/ViewModel/Screen (신규) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/history/TripHistoryScreen.kt` | D20 운행 이력 목록 — TripHistoryRoute/ViewModel/Screen (신규) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/history/TripHistoryDetailScreen.kt` | D21 운행 이력 상세 — TripHistoryDetailRoute/ViewModel/Screen (신규) |

수정 금지 파일(Routes.kt · MainNavGraph.kt · core:designsystem · 타 feature · domain/data)은 건드리지 않음.

## 화면 진입 경로

- **D19 내 평점** (`Routes.RATING = "rating"`): 하단탭 「마이」 탭 (마이 화면 미작성 — 평점이 임시 마이 탭, 계약 문서 기준). `TabStateScaffold(selectedTab = MYPAGE)`
- **D20 운행 이력** (`Routes.HISTORY = "history"`): D19 「운행 이력 전체 보기」 행 탭. (D17 일자 행 탭에서도 진입 예정 — settlement 담당) `TabStateScaffold(selectedTab = MYPAGE)`
- **D21 운행 상세** (`Routes.HISTORY_DETAIL = "history/detail/{tripId}"`): D19 코멘트 카드 탭(`comment.tripId`) · D20 행 탭. `BackStateScaffold`, 뒤로가기 → 직전 화면

## 구현 요약 (피그마 get_design_context 확인 후 Compose 번역)

- **D19** (2521:916): 평균 평점 카드(NumberXl 34sp + 「우수 기사」 StatusBadge INFO), 별점 분포 3행(별5/별4/별3 이하 — 건수·퍼센트·Primary500 비율 바), 최근 승객 코멘트 카드(탭 → D21). 평가 5건 미만이면 「평가 모으는 중」, 평균 4.5 미만이면 WAITING 배너 + 교육 안내. D20 진입 행 배치
- **D20** (2522:907): 필터 칩 전체·합승·단독·취소(MoyeotaChip, 클라이언트 필터, rememberSaveable 보존), 행 = StatusBadge(합승 INFO/단독 SUCCESS/취소 ERROR) + 일자 + 경로 + 요금 + 정산액(NumberMd). CANCELED 건은 회색 처리 + 「취소된 운행 · 위약금만 정산」. 하단 INFO 배너(12개월 조회 안내). LazyColumn + 빈 상태 문구
- **D21** (2522:974): 요약 카드(일시·유형 배지·경로·운행 시간·거리·운행 번호), 경유 정차 타임라인(RouteStop — 탑승 MarkerPickup 초록 / 하차 MarkerDropoff 주황 점 + 세로 연결선), 요금 구성 카드(미터기 + 호출료 − 수수료 + 합승 보너스 = 내 정산액 NumberLg, 보너스에 「주간 정산 합산 지급」 병기). 취소 건은 요금 구성·타임라인 숨기고 ERROR 배너(위약금만 정산). 「영수증 보기」 SecondaryButton `enabled=false` + 「준비 중」 표기

- ViewModel은 스킬 UiState(Loading/Success/Error) 패턴, `viewModel(factory=...)` + repository 파라미터. 하드코딩 hex 없음(MoyeotaColor 토큰만), 본문 BodyLg 17sp 이상, 금액·평점 NumberMd~Xl, 터치 타깃 60dp 이상(코멘트 카드·이력 행 84dp)

## 스펙 대비 미구현 항목 (docs 「미연결」 및 데이터 계약 한계)

- D19: 분포 행 탭 → 점수별 코멘트 필터 (docs 미연결), 코멘트 신고 시트 (미연결), 승객 익명 마스킹은 서버(도메인 모델) 책임으로 UI 미가공
- D20: 페이지네이션(20건 단위 무한 스크롤) — `getTripHistory()`가 페이지 파라미터 없는 단일 목록이라 전체 로드. 기간 직접 선택·검색 (docs 미연결). 17에서 진입 시 날짜 필터 유지 — 라우트에 날짜 파라미터가 없어 미구현(Routes.kt 수정 금지). 요금 미확정 건 상단 고정 — `TripHistoryStatus`에 미확정 상태 없음
- D21: 영수증 상세·이의 신청·안심번호 통화 기록 (docs 미연결 — 영수증은 비활성 버튼으로 표기), 승객 평가 있으면 하단 평점·코멘트 표시 → 19 이동 — `TripHistoryDetail`에 리뷰 필드 없음. 승객 앱 32 영수증 값 대조 error 배너 — 대조 대상 데이터 없음

## 빌드

`./gradlew :presentation:compileDebugKotlin --console=plain` — history feature 컴파일 에러 0건. (동시 작업 중인 타 feature 에이전트의 미완성 파일 때문에 모듈 전체 통과는 타이밍에 따라 달라질 수 있음 — 재시도 루프로 확인)
