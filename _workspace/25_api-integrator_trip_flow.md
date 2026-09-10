# 25. api-integrator — 운행 플로우 단순화 (domain·data)

## 작업 범위
지시서 `25_trip_flow_simplify_input.md` 의 1번(domain)·2번(data)만 수행.
presentation(3번)은 compose-builder 몫 — 이 시점의 presentation 은 아직 옛 메서드
(confirmBoarding/markNoShow/completeDropoff)를 호출하므로 **전체 앱 컴파일은 의도적으로 깨진 상태**다.
검증은 `:domain:assemble` + `:data:testDebugUnitTest` 모듈 단위로만 수행했다 (통과).

## 서버 근거 (컨트롤러/기존 DispatchApi 기준 — 실서버 미검증)
- dispatch 라이드 API 는 **파티 단위**: `POST /api/v1/dispatch/rides/{partyId}/board` (승객별 탑승/노쇼/하차 API 없음)
- board 1회 성공 = 파티 전원 탑승(IN_RIDE). 기존 `DispatchApi.board(partyId)` 를 그대로 사용 — DTO·API 변경 없음.
- `arrive`(notifyPickupArrival)·`complete+fare`(submitFinalFare)는 유지, 변경 없음.

## 변경 파일
| 파일 | 변경 |
|------|------|
| `domain/src/main/kotlin/com/moyeota/driver/domain/repository/DriverRepository.kt` | `confirmBoarding`·`markNoShow`·`completeDropoff` 제거, `startRide` 추가 (KDoc 에 서버 board 가 파티 단위 1회임을 명시) |
| `data/src/main/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepository.kt` | `startRide` 구현: `dispatchApi.board(partyId)` → remoteTrip 전원 boarded + `TripPhase.IN_TRIP` + nextStopIndex 첫 하차 스톱으로. 제거 3메서드 구현 삭제. `StopKind` import 추가 |
| `data/src/main/kotlin/com/moyeota/driver/data/repository/DummyDriverRepository.kt` | 동일 단순화 — `startRide` 가 전원 탑승 + IN_TRIP 전환을 흉내. 제거 3메서드 삭제 |
| `data/src/test/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepositoryCallTest.kt` | FakeDispatchApi 에 `onAccept`/`onBoard` 훅 추가, startRide 테스트 2건 추가 (기존 테스트는 제거 메서드 미참조 — 수정 불요) |

## 확정 Repository 시그니처 (운행 섹션 — compose-builder 경계면)
```kotlin
// 운행 (D13~D16b)
suspend fun getActiveTrip(): ActiveTrip?
suspend fun notifyPickupArrival(tripId: String)          // arrive — 알림 전용, 실패 무시
suspend fun startRide(tripId: String): ActiveTrip        // board(파티 단위 1회) — 전원 boarded + phase IN_TRIP, 실패는 예외 전파
suspend fun submitFinalFare(tripId: String, meterFare: Int): FareResult   // complete+fare — 변경 없음
```
- `TripPhase` enum 에 `EN_ROUTE` 는 없다 — 서버 IN_RIDE 에 대응하는 도메인 값은 기존 `TripPhase.IN_TRIP` 을 사용 (enum 미변경).
- `ActiveTrip` 모델 유지 — `passengers`·`stops` 필드 삭제하지 않음 (지시서 계약). startRide 후 `nextStopIndex` 는 첫 DROPOFF 스톱을 가리킨다.
- 실패 정책: `startRide` 는 상태 전이 액션 — 예외 전파(화면 유지 + 재시도). `notifyPickupArrival` 은 종전대로 실패 무시.

## 검증
- `./gradlew :domain:assemble :data:testDebugUnitTest` BUILD SUCCESSFUL
- RemoteDriverRepositoryCallTest 13건 전부 통과 (startRide 2건 포함: board 1회 호출·전원 탑승·IN_TRIP 전환 / board 실패 시 409 예외 전파)
- 실서버 미검증 — 에뮬레이터 E2E(콜 수락 → D13 → D15 → complete 204)는 리더/qa-verifier 검증 단계 몫

## qa-verifier 교차 검증 요청
- D13 「도착 · 운행 시작」 버튼이 `notifyPickupArrival` → `startRide` 순으로 호출하는지
- D15 진입 시 trip.phase == IN_TRIP · 승객별 하차 호출(completeDropoff) 잔존 참조 없는지
- 운행 완료 → D16 `submitFinalFare` 경로 무변경 확인
