# GRP/C 콜 5화면 구현 결과 (compose-builder)

## 변경 파일

| 파일 | 내용 |
|------|------|
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/call/CallGraph.kt` | 스텁 → 실제 화면 5개 등록. `callDetail`은 `navArgument("callId")`. 하단탭 공통 라우팅 헬퍼(`navigateToTab`) + 금액/거리 포맷터(`won`, `km`) 포함 |
| `.../feature/call/CallListScreen.kt` | D09 콜 리스트 (피그마 2515:592). `CallListViewModel` — `getCalls()` + `getPromotion()` 병행 로드 |
| `.../feature/call/CallDetailScreen.kt` | D10 콜 상세 (2515:656). `CallDetailViewModel` — `getCallDetail(callId)` / `acceptCall` / `declineCall` |
| `.../feature/call/CallAssignedScreen.kt` | D11 배차 확정 (2518:649). `CallAssignedViewModel` — `getActiveTrip()` |
| `.../feature/call/MissedCallsScreen.kt` | D12 놓친 콜 (2518:690). `MissedCallsViewModel` — `getMissedCalls()` |
| `.../feature/call/PromotionScreen.kt` | D22 합승 프로모션 (2529:965). `PromotionViewModel` — `getPromotion()` |

Routes.kt / MainNavGraph.kt / core:designsystem / domain / data 미수정 (계약 준수).

## 화면 진입 경로

- **D09** `call/list`: 하단탭 「콜」(어느 탭 화면에서든) · D07 「콜 리스트 보기」(home 담당). TabStateScaffold(selectedTab=CALLS), 상단바 우측 「놓친 콜」→ D12
- **D10** `call/detail/{callId}`: D09 카드 탭. BackStateScaffold, 뒤로 → D09
- **D11** `call/assigned`: D10 「수락하기」 성공 → `acceptCall` → navigate(CALL_ASSIGNED) { popUpTo(HOME) } — 운행 플로우 스택 초기화. BackHandler로 뒤로가기 차단. 「픽업 이동 시작」→ `trip/pickup`(D13)
- **D12** `call/missed`: D09 상단 「놓친 콜」. TabStateScaffold(selectedTab=CALLS) + 상단바 뒤로
- **D22** `call/promotion`: D09 하단 프로모션 배너 탭 (D06/D07 배너는 home 담당 몫). 「이번 주 적립」 행 탭 → `settlement`(D17). CTA 「콜 보러 가기」→ D09

## 스펙 반영 포인트

- D09 합승 카드: 「픽업 x.xkm · 보너스 +1,500원」 병기 + 예상 수익 = `expectedTotal` — 단독 콜과 금액 비교가 카드에서 끝남. 필터 칩(전체/합승만/1km 이내) 클라이언트 필터
- D10 예상 수익 행: 「미터기 + 호출료 + 보너스」 합산 표기. 15초 카운트다운(LaunchedEffect 타이머) 동안만 「수락」 활성, 제출 중에는 타이머 일시정지로 만료 이탈과 충돌 방지. 만료 시 자동 뒤로. 액션 실패는 CTA 위 ERROR NoticeBanner
- D11 합승이면 「배차 확정 · 합승 보너스 1,500원 적립 예정」 SUCCESS 배너 (`ActiveTrip.poolBonus` 서버 값)
- D22 금액·기간·조건·적립 전부 `Promotion` 서버 값. 주간 정산 합산 지급 명시
- 다크 고정 — 하드코딩 hex 없음(`MoyeotaColor`만), 본문 BodyLg(17sp) 이상, 금액 NumberMd/Xl, 카드·행 60dp 이상

## 스펙 대비 미구현 항목

- D09: 당겨서 새로고침(수동 refresh만), 「부산대 방향」 등 방향 필터(모델에 방향 데이터 없음), 다른 기사 선수락 실시간 회색 처리(더미 — 실시간 채널 없음), 휴무 상태 진입 차단 안내
- D10: 카운트다운 만료 시 놓친 콜 자동 기록(더미라 생략 — 코멘트로 명시, 실연동 시 서버 집계), 지도 전체 경로 보기(미연결), 거절 사유 시트(미연결), 총 운행 거리 행(CallDetail 모델에 총거리 필드 없음 → 픽업 거리 행으로 대체), 카운트다운 danger 색(Primary 카드 위 대비 문제로 흰색 유지)
- D11: 3초 후 D13 자동 전환(스펙 언급 있으나 계약 지시는 수동 CTA만 — 미적용), 콜 취소 요청(사유 시트 미연결 — 표시만), 승객 행 탭 상세(미연결). 승객 신뢰 지표(탑승 N회·평점)는 `TripPassenger` 모델에 없어 픽업→하차 경로로 대체
- D12: 기간 필터(오늘/이번 주 — `MissedCall`에 날짜 필드 없어 사유 필터로 대체), 「다른 기사 수락」 사유(`MissedReason`에 TIMEOUT/DECLINED만 존재), 최근 1시간 미응답 3회 감지 배너(정책 TBD — 상시 안내 문구로 대체), 30일 보관 표기
- D22: 「합승 콜 받기 켜기」 토글(Repository에 설정 메서드 없음 → 「콜 보러 가기」 CTA로 대체), 종료된 프로모션 상태 전환, 보너스 항목 필터된 D17 진입(D17 필터 파라미터 없음 — 일반 진입)

### Repository 시그니처 제안 (api-integrator 참고)
- `setPoolCallEnabled(enabled: Boolean)` — D22 CTA·D04 토글용
- `CallDetail.totalDistanceKm` — D10 총 운행 거리 행용
- `MissedReason.TAKEN_BY_OTHER` + `MissedCall.date` — D12 사유 3종·기간 필터용

## 빌드

`./gradlew :presentation:compileDebugKotlin` 통과 (call 파일 에러 0 — 동시 작업 중이던 auth 파일 에러와 별개).
