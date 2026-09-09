# 24. compose-builder — 운행 화면(D13·D15) 지도 실좌표 + 내 위치 (3번 presentation 항목)

## 변경 파일
| 파일 | 변경 |
|------|------|
| `presentation/.../core/MapDefaults.kt` | 공용 함수 추가: `routeCamera(departure, destination)`(중점 + 거리 기반 줌, 출발지만 → 줌 14, 둘 다 null → MoyeotaDefaultCamera 폴백), `zoomForDistance(m)`, `Marker.attachTo(naverMap, point, tint)`. 23번 CallRouteMap 의 private 로직을 추출한 것 |
| `presentation/.../core/DriverLocationState.kt` | 신규 — `rememberDriverLocation(locationSource): State<LatLng?>` 5초 폴링 헬퍼 (HomeRoute produceState 패턴 추출) |
| `presentation/.../feature/trip/TripCommon.kt` | `TripMap(pillText, departure: GeoPoint?, destination: GeoPoint?, myLocation: LatLng?, modifier)` 로 확장. GangnamCenter 고정 제거 → routeCamera 카메라 + 픽업/하차 마커(remember 재사용, null 생략) + locationOverlay 내 위치(측위 전 숨김) + dispose 시 마커 정리 |
| `presentation/.../feature/trip/PickupScreen.kt` | Route 에 locationSource 추가 + rememberDriverLocation. TripMap 호출: departure = trip.departurePoint, **destination = null** (픽업지 + 내 위치만) |
| `presentation/.../feature/trip/DrivingScreen.kt` | Route 에 locationSource 추가 + rememberDriverLocation. TripMap 호출: departure·destination 모두 전달 (운행 경로 조망) + 내 위치 |
| `presentation/.../feature/trip/TripGraph.kt` | `tripGraph(navController, repository, locationSource)` 로 시그니처 확장 — D13·D15 만 locationSource 사용, Boarding(D14)·Fare(D16) 는 변경 없음 |
| `presentation/.../core/MainNavGraph.kt` | `tripGraph(navController, repository, locationSource)` 호출부 한 줄 수정 (리더 지시에 따른 호출부 변경) |
| `presentation/.../feature/call/CallDetailScreen.kt` | CallRouteMap 이 공용 `routeCamera`/`attachTo` 를 쓰도록 정리 — private zoomFor·attachTo 삭제 (동작 동일) |
| `presentation/.../feature/home/HomeRoute.kt` | produceState 5초 폴링 → 공용 `rememberDriverLocation` 으로 교체 (동작 동일, 권장 사항 반영) |

## 계약 대비 참고
- 지시서의 `myLocation: GeoPoint?` 는 `LatLng?` 로 구현 — 내 위치는 공용 헬퍼 `rememberDriverLocation` 이 LatLng 로 산출하고(HomeMyLocationMap 도 LatLng? 수신), 도메인 주석상 GeoPoint 는 "장소 좌표" · 기사 현재 위치는 별도 의미라 presentation 내부 타입으로 LatLng 를 유지했다. TripMap 은 trip 패키지 internal 이라 경계면 영향 없음.

## 동작
- D13 픽업 이동: 픽업지(departurePoint) 중심 줌 14, 픽업 마커 + 내 위치 오버레이. 좌표 null 이면 기본 카메라(기존 화면 유지), 측위 전이면 내 위치 숨김
- D15 운행 중: 출발·하차 중점 + 거리 기반 줌, 픽업·하차 마커 + 내 위치. destinationPoint null 이면 하차 마커 생략·출발지 중심
- Boarding(D14)·Fare(D16) 미변경

## 진입 경로 (검증용)
로그인 → 영업 시작(D07) → 콜 수락(D10) → 배차(D11) 「픽업 안내 시작」 → **D13**(픽업지 중심 확인) → 「도착 · 탑승 확인」 → D14 「운행 시작」 → **D15**(두 지점 조망 확인)

## 빌드
`./gradlew :app:assembleDebug` BUILD SUCCESSFUL
