package com.moyeota.driver.presentation.feature.trip

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/D 운행 그래프 — 전 화면 getActiveTrip() 기반, 운행 플로우 전체 뒤로가기 차단.
// locationSource 는 지도가 있는 D13·D15 만 쓴다 (내 위치 오버레이).
fun NavGraphBuilder.tripGraph(
    navController: NavHostController,
    repository: DriverRepository,
    locationSource: DriverLocationSource,
) {
    composable(Routes.TRIP_PICKUP) { PickupRoute(navController, repository, locationSource) }   // D13 (도착 · 운행 시작)
    composable(Routes.TRIP_DRIVING) { DrivingRoute(navController, repository, locationSource) } // D15
    composable(Routes.TRIP_FARE) { FareRoute(navController, repository) }                       // D16 (+D16b 키패드)
}
