package com.moyeota.driver.presentation.feature.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.NaverMapView
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.GeoPoint
import com.moyeota.driver.presentation.core.attachTo
import com.moyeota.driver.presentation.core.routeCamera
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker

// 운행 플로우(D13~D16) 공용 요소 — trip 패키지 전용.

/** D13·D15 화면 꺼짐 방지 (피그마 공통 규칙: 운행 화면은 FLAG_KEEP_SCREEN_ON 유지) */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

internal fun formatWon(amount: Int): String = "%,d".format(amount)

internal fun formatKm(km: Double): String =
    if (km < 1.0) "${(km * 1000).toInt()}m" else "%.1fkm".format(km)

/**
 * D13·D15 네비 영역 — 파티 실좌표 지도 + 좌상단 MapPill (경로 요약).
 *
 * - 카메라: destination 이 null 이면 departure 중심(줌 14), 둘 다 있으면 중점 + 거리 기반 줌.
 *   둘 다 null 이면 기본 카메라 폴백 (routeCamera — D10 CallRouteMap 과 같은 규칙)
 * - 마커: 픽업(MarkerPickup)·하차(MarkerDropoff). null 좌표는 마커 생략
 * - 내 위치: locationOverlay (HomeMyLocationMap 패턴). 측위 전(null)이면 숨긴다
 *
 * @param departure 픽업지 좌표. D13 은 이것만 넘긴다 (픽업지로 가는 중)
 * @param destination 최종 하차지 좌표. D15 만 넘긴다 (운행 경로 조망)
 * @param myLocation 기사 현재 위치 — rememberDriverLocation 폴링 값
 */
@Composable
internal fun TripMap(
    pillText: String,
    departure: GeoPoint?,
    destination: GeoPoint?,
    myLocation: LatLng?,
    modifier: Modifier = Modifier,
) {
    val pickup = departure?.let { LatLng(it.latitude, it.longitude) }
    val dropoff = destination?.let { LatLng(it.latitude, it.longitude) }
    val (center, zoom) = routeCamera(pickup, dropoff)

    // CallRouteMap 과 같은 패턴 — onMapReady 로 지도 참조를 상태로 잡아
    // 지도 준비와 운행 데이터·위치 갱신 중 어느 쪽이 먼저 와도 오버레이가 최신 좌표를 가리키게 한다
    var map by remember { mutableStateOf<NaverMap?>(null) }
    // Marker 를 remember 로 재사용 — LaunchedEffect 재실행 시 중복 생성이 없다
    val pickupMarker = remember { Marker() }
    val dropoffMarker = remember { Marker() }
    val pickupTint = MoyeotaColor.MarkerPickup.toArgb()
    val dropoffTint = MoyeotaColor.MarkerDropoff.toArgb()

    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {
        NaverMapView(
            modifier = Modifier.fillMaxSize(),
            center = center,
            zoom = zoom,
            onMapReady = { map = it },
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(MoyeotaColor.SurfaceCard, CircleShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(text = pillText, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
        }
    }

    LaunchedEffect(map, pickup, dropoff, myLocation) {
        val naverMap = map ?: return@LaunchedEffect
        pickupMarker.attachTo(naverMap, pickup, pickupTint)
        dropoffMarker.attachTo(naverMap, dropoff, dropoffTint)
        // 내 위치 오버레이 — 측위 전이면 숨긴다 (엉뚱한 지점에 점을 찍는 것보다 없는 편이 덜 헷갈린다)
        val overlay = naverMap.locationOverlay
        if (myLocation != null) {
            overlay.position = myLocation
            overlay.isVisible = true
        } else {
            overlay.isVisible = false
        }
    }

    // 지도 dispose 시 마커 참조 정리 (NaverMapView 가 내부 map=null 로 되돌리는 패턴에 맞춘다)
    DisposableEffect(Unit) {
        onDispose {
            pickupMarker.map = null
            dropoffMarker.map = null
        }
    }
}
