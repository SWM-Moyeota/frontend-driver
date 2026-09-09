# 22. 홈(D06·D07) 지도 — 현재 위치 중심 + 내 위치 마커

## 요청 (사용자 스크린샷 기준)
1. 홈 지도가 `GangnamCenter`(37.4979, 127.0276) 고정 카메라 → **기사 현재 위치(GPS) 중심**으로
2. **내 위치 마커**(네이버 지도 locationOverlay)가 없음 → 표시

## 리더 확정 계약
- 위치 공급: 기존 `domain/location/DriverLocationSource.current(): DriverCoordinate?` 사용.
  구현체는 app 모듈 `AppContainer.locationSource`(AndroidLocationSource — 구독 캐시 + lastKnown 폴백, `emu geo fix` 좌표 포함).
- 전달 경로: MainActivity → MainNavGraph → homeGraph → HomeRoute 에 `locationSource: DriverLocationSource` 파라미터 추가.
  (presentation 은 domain 인터페이스에만 의존 — app 구현체 직접 참조 금지)
- HomeRoute: 화면 구성 동안 주기 폴링(예: produceState + delay 5s)로 `LatLng?` 상태 유지.
- HomeOffDutyScreen · HomeOnDutyScreen:
  - `center = 현재위치 ?: MoyeotaDefaultCamera` (GangnamCenter 폴백 대체 — 서면 기본 카메라)
  - `onMapReady` 로 NaverMap 참조를 잡고, `LaunchedEffect(map, 현재위치)` 에서
    `map.locationOverlay.apply { isVisible = true; position = ... }` 갱신. 위치 null 이면 오버레이 숨김.
- 범위 제한: 홈 2개 화면만. CallDetailScreen·TripCommon 의 GangnamCenter 는 이번 범위 아님(콜 상세 좌표 마커는 별도 백로그).
- 문구·주석은 레포 톤(한국어, 이유 중심) 유지. 다크 모드 고정 영향 없음.

## 검증 (리더 수행 예정)
- `./gradlew :app:assembleDebug :data:testDebugUnitTest`
- 에뮬레이터(서면역 geo fix) 재설치 → 홈 지도 중심·마커 스크린샷
