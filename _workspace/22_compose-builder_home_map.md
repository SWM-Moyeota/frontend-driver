# 22 — 홈(D06·D07) 지도 현재 위치 중심 + 내 위치 마커 (compose-builder 산출물)

## 변경 파일
| 파일 | 변경 내용 |
|------|----------|
| `presentation/.../feature/home/HomeComponents.kt` | `HomeMyLocationMap` 신규 — NaverMapView 래핑, `onMapReady`로 NaverMap 참조를 상태로 잡고 `LaunchedEffect(map, myLocation)`에서 locationOverlay 갱신 (위치 null이면 `isVisible = false`). center = `myLocation ?: MoyeotaDefaultCamera`(서면) |
| `presentation/.../feature/home/HomeOffDutyScreen.kt` | D06 지도를 GangnamCenter 고정 → `HomeMyLocationMap(myLocation)` 교체, `myLocation: LatLng?` 파라미터 추가 |
| `presentation/.../feature/home/HomeOnDutyScreen.kt` | D07 동일 교체 + `myLocation: LatLng?` 파라미터 추가 |
| `presentation/.../feature/home/HomeRoute.kt` | `locationSource: DriverLocationSource` 파라미터 추가. `produceState<LatLng?>` + 5초 `delay` 폴링으로 `locationSource.current()` → `LatLng?` 상태 유지, 두 화면에 전달 |
| `presentation/.../feature/home/HomeGraph.kt` | `homeGraph(..., locationSource)` 파라미터 추가, HomeRoute로 전달 |
| `presentation/.../core/MainNavGraph.kt` | `MainNavGraph(repository, locationSource)` 파라미터 추가, homeGraph에 전달 |
| `presentation/.../core/MapDefaults.kt` | `GangnamCenter` 상수·값 유지(CallDetail·TripCommon이 계속 사용), 주석만 현행화 |
| `app/.../MainActivity.kt` | `MainNavGraph` 호출에 `AppContainer.locationSource` 주입 (presentation은 domain 인터페이스로만 참조) |

## 동작
- 홈(D06 휴무·D07 영업중) 진입 시 `DriverLocationSource.current()`(구독 캐시 + lastKnown 폴백)를 5초 주기로 읽어 지도 카메라를 기사 현재 위치로 잡는다.
- 네이버 지도 `locationOverlay`가 같은 좌표에 내 위치 마커로 표시된다. 측위 전/권한 미허용(null)이면 오버레이 숨김 + `MoyeotaDefaultCamera`(부산 서면) 폴백.
- 범위 준수: 홈 2개 화면만. CallDetailScreen·TripCommon의 GangnamCenter 카메라는 그대로 (콜 상세 좌표 마커는 별도 백로그).

## 진입 경로
로그인 → 홈 탭 (Routes.HOME — D06/D07은 dutyStatus 분기)

## 검증
- `./gradlew :app:assembleDebug -q` 통과 (에러 출력 없음, APK 생성 확인)
- 홈 외 GangnamCenter 참조(CallDetail·TripCommon) 컴파일 영향 없음 확인
- 실기(에뮬레이터 `geo fix` 서면 좌표) 스크린샷 검증은 리더 수행 예정
