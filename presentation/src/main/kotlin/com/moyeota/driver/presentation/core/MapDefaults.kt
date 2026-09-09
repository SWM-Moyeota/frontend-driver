package com.moyeota.driver.presentation.core

import com.moyeota.core.designsystem.component.MoyeotaDefaultCamera
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker

/**
 * 더미 데이터 기준 좌표(강남역) — 좌표 미제공 파티의 지도 카메라 폴백 참고용.
 * DummyDriverRepository·RemoteDriverRepository 의 고정 좌표와 같은 지점을 바라본다.
 * 홈(D06·D07)은 기사 현재 위치(DriverLocationSource)를, 콜 상세(D10)·운행(D13·D15)은
 * 파티 실좌표(routeCamera)를 쓰므로 화면 코드는 더는 이 상수를 참조하지 않는다.
 */
val GangnamCenter: LatLng = LatLng(37.4979, 127.0276)

/**
 * 출발(픽업)·도착(하차) 좌표로 지도 카메라(중심·줌)를 잡는다 — D10 CallRouteMap·D13/D15 TripMap 공용.
 *
 * - 두 좌표가 다 있으면 중점을 중심으로, 줌은 두 점 직선 거리에 맞춘다
 * - 출발지만 있으면 출발지 중심(줌 14)
 * - 둘 다 없으면 기본 카메라(서면) 폴백 — 좌표를 안 준 파티도 기존 화면 그대로 뜬다
 */
fun routeCamera(departure: LatLng?, destination: LatLng?): Pair<LatLng, Double> = when {
    departure != null && destination != null -> LatLng(
        (departure.latitude + destination.latitude) / 2,
        (departure.longitude + destination.longitude) / 2,
    ) to zoomForDistance(departure.distanceTo(destination))

    departure != null -> departure to 14.0
    else -> MoyeotaDefaultCamera to 14.0
}

/** 픽업↔하차 직선 거리(m)에 맞는 대략적 줌 — 카드형 지도에서 두 마커가 함께 보이는 수준이면 충분 */
fun zoomForDistance(distanceMeters: Double): Double = when {
    distanceMeters < 1_000 -> 14.0
    distanceMeters < 3_000 -> 12.5
    distanceMeters < 8_000 -> 11.5
    distanceMeters < 20_000 -> 10.0
    else -> 8.5
}

/** 좌표가 있으면 지도에 부착·갱신, 없으면 떼어낸다 — remember 로 재사용하는 마커 전용 */
fun Marker.attachTo(naverMap: NaverMap, point: LatLng?, tint: Int) {
    if (point == null) {
        map = null
        return
    }
    position = point
    iconTintColor = tint
    map = naverMap
}
