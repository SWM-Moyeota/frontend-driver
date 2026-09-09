# 23. 콜 상세(D10) 지도 — 실제 매칭방 좌표 반영

## 요청
콜 상세 지도가 GangnamCenter 고정 카메라 + 마커 없음 → 실제 파티 출발지 중심 + 픽업/하차 마커.
서버(PartySummaryDto)는 departureLatitude/Longitude · destinationLatitude/Longitude 를 이미 준다 — 프론트만 수정.

## 리더 확정 계약
1. **domain** (api-integrator)
   - `DriverModels.kt` 에 `data class GeoPoint(val latitude: Double, val longitude: Double)` 추가
     (DriverCoordinate 는 "기사 단말 위치" 의미라 재사용하지 않는다 — 장소 좌표는 별도 타입).
   - `CallDetail` 에 `val departurePoint: GeoPoint? = null`, `val destinationPoint: GeoPoint? = null` 추가.
     null = 서버가 좌표 미제공 (화면은 기존 폴백 유지).
2. **data** (api-integrator)
   - `DispatchMappers.toCallDetail()` 에서 DTO 좌표 → GeoPoint 매핑 (위·경도 둘 다 있을 때만 non-null).
   - 단위 테스트: 좌표 매핑 + 좌표 누락 시 null 확인 (기존 매퍼 테스트 파일 관례를 따른다).
3. **presentation** (compose-builder)
   - `CallDetailScreen.kt:264` `center = GangnamCenter` → `detail.departurePoint ?: MoyeotaDefaultCamera` 중심.
   - onMapReady 로 NaverMap 참조를 잡고 LaunchedEffect 에서 픽업 마커(departurePoint)·하차 마커(destinationPoint) 표시.
     마커 색은 기존 토큰(MoyeotaColor.MarkerPickup / MarkerDropoff 상당)을 따르고, 없는 값은 마커 생략.
   - 좌표 둘 다 있으면 두 지점이 모두 보이게 카메라(중점 + 적정 줌) 조정 — 과한 구현 금지, 단순 중점이면 충분.
4. **범위 제한**: 운행 화면(D15, TripCommon) 지도는 이번 범위 아님 (ActiveTrip 좌표 전달은 별도 백로그).

## 검증 (리더)
- assembleDebug + :data:testDebugUnitTest
- 에뮬레이터: 배포 서버에 서면 좌표 파티 생성 → 콜 상세 진입 → 지도 중심·마커 스크린샷
