# GRP/E 정산 — D17 · D18 구현 산출물 (compose-builder)

## 변경 파일
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/settlement/SettlementGraph.kt` — 스텁 → 실제 화면 등록으로 교체 (시그니처 유지)
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/settlement/SettlementScreen.kt` — 신규. D17 결제 정산 내역 (SettlementViewModel + SettlementRoute + SettlementScreen)
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/settlement/SettlementDetailScreen.kt` — 신규. D18 정산 상세 (SettlementDetailViewModel + SettlementDetailRoute + SettlementDetailScreen)

수정 금지 파일(Routes.kt · MainNavGraph.kt · core:designsystem · domain/data)은 건드리지 않음.

## 화면 진입 경로
- **D17** `Routes.SETTLEMENT` ("settlement"): 하단탭 「정산」 (다른 탭 화면의 onTabSelect → SETTLEMENT) · D08 「오늘 수익」 탭(홈 담당 에이전트가 navigate)
- **D18** `Routes.SETTLEMENT_DETAIL` ("settlement/detail"): D17 일자 행 탭 · 「주간 정산 상세 보기」 행 탭 → navigate(SETTLEMENT_DETAIL). 뒤로가기 → popBackStack → D17

## 구현 내용
- D17: TabStateScaffold(selectedTab=SETTLEMENT) + title-only 상단 바(「정산」). 기간 필터 칩(「이번 주」만 active, 나머지 비활성 표기) → Primary600 입금 예정 카드(총 정산액 NumberXl 34sp, 지급 예정일·계좌, 「계좌 변경 (준비 중)」 비활성 표기) → 일자별 행(SettlementDay, min 84dp, 탭→D18) → 「주간 정산 상세 보기」 nav 행(→D18)
- D18: BackStateScaffold(「정산 상세」). 주간 헤더 카드(weekLabel + 지급 예정일·계좌) → 요약 행: 운행 수입(totalFare) / 합승 보너스(+totalBonus, Success) / 서비스 수수료(−totalServiceFee, Danger, 「청구액의 5%」) / 취소·노쇼 차감(잔차 도출, 0이면 숨김) / 최종 지급액(NumberLg, 「세전 기준」) — 기사 정산액 공식(총액 − 수수료 ± 보너스·차감)과 합계 항상 일치 → 건별 내역(SettlementTripRow, 합승 건은 「합승」 배지 + 보너스 병기) → 「명세서 내려받기」 disabled + 「준비 중」 안내
- 탭 라우팅: HOME→Routes.HOME, CALLS→Routes.CALL_LIST, MYPAGE→Routes.RATING (계약 문서 규칙), 현재 탭 재탭은 no-op
- ViewModel은 스킬 UiState 패턴(Loading/Success/Error + factory), 로딩·에러는 LoadingBox/ErrorBox(onRetry)
- 다크 토큰(MoyeotaColor)만 사용, 하드코딩 hex 없음. 본문 BodyLg 17sp, 금액 NumberMd~Xl, 행 최소 84dp

## 스펙 대비 미구현 항목 (의도된 미연결 — 비활성/생략 처리)
- D17 기간 필터: 「이번 달·지난달·기간 선택」 칩은 비활성 표기만 (기간 선택 시트 미연결, repository에 기간 파라미터 없음 — getSettlementDetail()은 이번 주 고정)
- D17 계좌 변경 · 세금계산서: 미연결 (계좌 변경은 카드 안 「준비 중」 표기)
- D17 일자 행 → 원래 스펙은 D20 운행 이력(날짜 필터)이나, 작업 지시에 따라 Routes.SETTLEMENT_DETAIL로 연결
- D17 「정산 대기」 회색 처리 · 미확정 운행 error 배너: 도메인 모델에 결제 대기/미확정 상태 없음 — 미구현
- D18 항목 행 탭 → 건별 목록 필터: 미연결 (스펙상 미연결 항목)
- D18 명세서 PDF · 정산 문의하기: 미연결 (PDF 버튼 disabled 표기, 문의하기는 미노출)
- D18 지급 완료 주차 success 배너: 더미 데이터가 지급 예정 상태뿐이라 미표시 (모델에 지급 완료 여부 없음)
- D18 일자 행 sub의 「합승 N건」 병기(피그마): SettlementDay에 합승 건수 필드 없음 — 「운행 N건」만 표기

## 빌드
`./gradlew :presentation:compileDebugKotlin --console=plain` → BUILD SUCCESSFUL
