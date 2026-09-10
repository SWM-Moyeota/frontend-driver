# 25 · compose-builder — 운행 플로우 단순화 (presentation)

## 변경 파일
| 파일 | 변경 |
|------|------|
| `presentation/.../core/Routes.kt` | `TRIP_BOARDING`(D14) 상수 제거, D15 주석에 제거 사유 명시 |
| `presentation/.../feature/trip/BoardingScreen.kt` | **삭제** (D14 화면 · BoardingViewModel · PassengerCard 전부) |
| `presentation/.../feature/trip/TripGraph.kt` | D14 composable 등록 제거 → D13·D15·D16 3화면 그래프 |
| `presentation/.../feature/trip/PickupScreen.kt` | CTA 「도착 · 탑승 확인」→「도착 · 운행 시작」. `startRide()` 추가: arrive(runCatching, 실패 무시) → `repository.startRide` 성공 시 `started` 플래그 → Route 의 LaunchedEffect 가 D15 직행(popUpTo D13 inclusive). 실패 시 CTA 위 `NoticeBanner(ERROR)` + 버튼 재탭으로 재시도(진행 중 loading). 승객 카드는 인원 요약 유지(pill 문구 "승객 N명 대기 중"), noShow 필터 제거 |
| `presentation/.../feature/trip/DrivingScreen.kt` | 승객별 하차 목록 섹션·`completeDropoff` 호출·processing/actionError 상태·FARE_INPUT 자동 이동 전부 제거. 유지 = 상단 다음 하차지 헤더(장소·거리·"도착 예정 N분") + TripMap(출발·하차 마커 + 내 위치) + 하단 「운행 완료」 → D16 직행(popUpTo D15 inclusive, 서버 complete 는 D16 submitFinalFare 담당) |
| `presentation/.../feature/trip/FareScreen.kt` | 로직 변경 없음 — 진입 주석만 「D15 운행 완료」로 갱신 |

## 새 흐름
D11 콜 배정 → D13 픽업 이동 → 「도착 · 운행 시작」(arrive 알림 + board 파티 단위 1회) → D15 운행 중(하차 안내 + 지도) → 「운행 완료」 → D16 요금 입력(submitFinalFare = 서버 complete) → 홈 복귀

- D15 하차지 헤더는 `stops[nextStopIndex]`(startRide 후 첫 하차 스톱)를 쓰되 DROPOFF 가 아니면 첫 DROPOFF 스톱으로 폴백
- 운행 플로우 뒤로가기 차단 · KeepScreenOn · 다크 토큰 규칙 그대로

## 화면 진입 경로 (검증용)
홈(영업중) → 콜 수락(D10) → D11 「픽업 안내 시작」 → D13 → 「도착 · 운행 시작」 → D15 → 「운행 완료」 → D16

## 검증
- `./gradlew :app:assembleDebug` **BUILD SUCCESSFUL** (전 모듈 컴파일 통과 — 옛 메서드 참조 해소)
- trip 패키지 하드코딩 `Color(0x` 없음, `TRIP_BOARDING` 참조 잔재 없음
