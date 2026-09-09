# 24. 운행 화면(D13·D15) 지도 — 실좌표 + 내 위치

## 요청
TripCommon.TripMap 이 GangnamCenter 고정 → 실제 파티 좌표 + 기사 현재 위치 오버레이.
브랜치 feature/trip-map-realdata (23번 GeoPoint 위에 스택).

## 리더 확정 계약
1. **domain** (api-integrator)
   - `ActiveTrip` 에 `val departurePoint: GeoPoint? = null`, `val destinationPoint: GeoPoint? = null` 추가 (23번 GeoPoint 재사용).
2. **data** (api-integrator)
   - `DispatchMappers.toActiveTrip()` 에서 DTO 좌표 → GeoPoint 매핑 (23번 geoPointOrNull 헬퍼 재사용).
   - DispatchMappersTest 에 toActiveTrip 좌표 매핑 테스트 추가 (값 매핑 + 누락 null).
   - DummyDriverRepository 의 ActiveTrip 생성부는 기본값 null 로 컴파일 영향 없어야 함 (확인만).
3. **presentation** (compose-builder)
   - `TripCommon.TripMap` 확장: `TripMap(pillText, departure: GeoPoint?, destination: GeoPoint?, myLocation: GeoPoint?, modifier)`.
     - 카메라: destination 이 null 이면 departure 중심(줌 14), 둘 다 있으면 중점 + 거리 기반 줌(23번 CallRouteMap 의 zoomFor 로직 재사용 — 중복 구현 말고 공용 함수로 추출해도 좋다. 위치: presentation/core).
     - 마커: 픽업(MarkerPickup)·하차(MarkerDropoff), null 은 생략. 내 위치는 locationOverlay (HomeMyLocationMap 패턴).
     - 둘 다 null 이면 MoyeotaDefaultCamera 폴백 (기존과 동일한 화면 유지).
   - 호출부:
     - PickupScreen(D13): departure = trip.departurePoint, destination = null (픽업지로 가는 중 — 픽업지 + 내 위치만)
     - DrivingScreen(D15): departure·destination 모두 전달 (운행 경로 조망)
   - 내 위치: `tripGraph(navController, repository, locationSource)` 로 시그니처 확장, MainNavGraph 호출부 수정.
     Pickup/Driving Route 에서 HomeRoute 와 같은 produceState(5s 폴링) 패턴 — 공용 헬퍼로 추출 권장 (예: presentation/core 의 rememberDriverLocation(locationSource)) 하고 HomeRoute 도 그걸 쓰도록 정리해도 좋다(선택).
4. 범위: D13·D15 지도만. Boarding(D14)·Fare(D16) 는 지도 없음 — 건드리지 않는다.

## 검증 (리더)
- assembleDebug + :data:testDebugUnitTest
- 에뮬레이터: 파티 수락 → D13 픽업지 중심 확인 → D15 두 지점 조망 확인 스크린샷
