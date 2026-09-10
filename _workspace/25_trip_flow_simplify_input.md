# 25. 운행 플로우 단순화 — 승객별 탑승/하차 처리 제거

## 요청 (사용자)
1. D14(승객 탑승) 화면 제거 — 승객별 「탑승 확인/노쇼 처리」는 데모 느낌 + 기사는 그냥 태우면 됨.
   D13 「도착·탑승 확인」 → 바로 운행 시작(D15).
2. D15(운행 중)의 승객별 하차 목록(1·승객1 전포카페거리 하차 100m …) 제거 —
   승차지·하차지가 파티 단위라 승객별 정보 불필요. 하차 안내 + 운행 완료만 남긴다.

## 서버 근거
dispatch 라이드 API 는 파티 단위: POST rides/{partyId}/arrive · /board · /complete(fare).
승객별 탑승/노쇼/하차 개념이 서버에 없음 — 화면 단순화가 서버 계약과 일치.

## 리더 확정 계약
1. **domain** (api-integrator)
   - DriverRepository 에서 제거: `confirmBoarding(tripId, passengerId)`, `markNoShow`, `completeDropoff`
   - 추가: `suspend fun startRide(tripId: String): ActiveTrip` — 탑승 일괄 처리(서버 board) + phase 전환
   - `notifyPickupArrival`(arrive)·`submitFinalFare`(complete+fare) 는 유지
   - ActiveTrip 모델은 유지 (passengers·stops 필드 삭제하지 않음 — 매퍼/후속 화면 여지)
2. **data** (api-integrator)
   - RemoteDriverRepository: startRide = dispatchApi.board(partyId) → remoteTrip phase EN_ROUTE·전원 boarded 갱신.
     기존 confirmBoarding/markNoShow/completeDropoff 구현 제거. 관련 테스트 갱신.
   - DummyDriverRepository: 동일 단순화 (startRide 로 상태 전환).
3. **presentation** (compose-builder)
   - Routes.TRIP_BOARDING 제거, BoardingScreen.kt 삭제, tripGraph 에서 D14 라우트 제거
   - PickupScreen(D13): 「도착·탑승 확인」버튼 → notifyPickupArrival + startRide 후 D15 직행
     (버튼 문구는 「도착 · 운행 시작」으로 변경)
   - DrivingScreen(D15): 승객별 스톱 목록 섹션 삭제. 유지 = 상단 하차지 헤더 + 지도(마커·내 위치)
     + 하단 「운행 완료」 버튼 → D16(요금 입력) 직행. completeDropoff 호출 제거.
   - FareScreen(D16) 은 변경 없음 (submitFinalFare 가 서버 complete)
4. 문구·주석 톤 유지, 다크 모드 영향 없음.

## 검증 (리더)
- assembleDebug + :data:testDebugUnitTest
- 에뮬레이터: 콜 수락 → D13 「도착 · 운행 시작」 → D15(목록 없음) → 운행 완료 → D16 요금 → 서버 complete 204
