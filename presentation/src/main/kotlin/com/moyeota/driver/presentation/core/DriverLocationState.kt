package com.moyeota.driver.presentation.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.moyeota.driver.domain.location.DriverLocationSource
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.delay

/**
 * 화면이 떠 있는 동안 단말 측위 캐시를 5초 주기로 폴링해 기사 현재 위치를 상태로 노출한다 —
 * 홈(D06·D07)·운행(D13·D15) 공용.
 *
 * [DriverLocationSource.current] 는 즉시 반환(구독 캐시 + lastKnown 폴백)이라 폴링 부담이 없고,
 * 권한 미허용·측위 전이면 null 이 유지된다 — 호출부는 null 이면 내 위치 오버레이를 숨긴다.
 */
@Composable
fun rememberDriverLocation(locationSource: DriverLocationSource): State<LatLng?> =
    produceState<LatLng?>(initialValue = null, locationSource) {
        while (true) {
            value = locationSource.current()?.let { LatLng(it.latitude, it.longitude) }
            delay(5_000)
        }
    }
