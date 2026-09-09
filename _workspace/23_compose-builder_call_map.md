# 23 · compose-builder — 콜 상세(D10) 지도 실좌표 반영 (presentation)

## 변경 파일
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/call/CallDetailScreen.kt` (단일 파일)

## 구현 내용
1. **고정 카메라 제거**: `NaverMapView(center = GangnamCenter)` → 새 private 컴포저블 `CallRouteMap(departure, destination)` 호출로 교체. `GangnamCenter` import 제거 (`MapDefaults.kt`는 TripCommon이 아직 사용하므로 유지).
2. **CallRouteMap** (CallDetailScreen.kt 내 private):
   - `detail.departurePoint` / `detail.destinationPoint`(domain `GeoPoint?`) → Naver `LatLng` 변환
   - 카메라: 둘 다 있으면 **중점** + 거리 기반 줌(`zoomFor`: <1km→14.0, <3km→12.5, <8km→11.5, <20km→10.0, 이상→8.5), 출발지만 있으면 출발지+14.0, 둘 다 없으면 `MoyeotaDefaultCamera`(서면) 폴백
   - 마커: `onMapReady`로 NaverMap 참조를 상태로 잡고 `LaunchedEffect(map, pickup, dropoff)`에서 부착 — HomeComponents.kt `HomeMyLocationMap` 패턴 동일
   - 픽업 마커 `MoyeotaColor.MarkerPickup`, 하차 마커 `MoyeotaColor.MarkerDropoff`를 `iconTintColor`로 적용. 좌표 null이면 해당 마커 생략(`marker.map = null`)
   - `Marker` 2개를 `remember`로 재사용해 LaunchedEffect 재실행 시 중복 생성 없음. `DisposableEffect` onDispose에서 `marker.map = null` 정리
3. 하드코딩 hex 없음 — 색은 전부 `MoyeotaColor` 토큰.

## 화면 진입 경로
홈(D07 영업 중) → 콜 수신 푸시 or 콜 목록(D09) → 콜 상세(D10, `call/detail/{callId}`) → 상세 조회 Success 시 상단 140dp 지도에 픽업/하차 마커.

## 검증
- `./gradlew :app:assembleDebug` **BUILD SUCCESSFUL**
- 좌표 미제공 파티: 마커 없이 서면 기본 카메라 (기존 폴백 유지)
- 에뮬레이터 실기 확인은 qa-verifier 몫 (서면 좌표 파티 생성 → D10 진입 → 마커·중심 확인)

## 범위 외 (백로그 유지)
- 운행 화면(D15, TripCommon.kt)은 여전히 `GangnamCenter` 고정 — ActiveTrip 좌표 전달은 별도 작업
