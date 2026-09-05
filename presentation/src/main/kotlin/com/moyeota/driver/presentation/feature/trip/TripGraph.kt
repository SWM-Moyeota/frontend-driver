package com.moyeota.driver.presentation.feature.trip

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/D 운행 그래프 — 전 화면 getActiveTrip() 기반, 운행 플로우 전체 뒤로가기 차단.
fun NavGraphBuilder.tripGraph(navController: NavHostController, repository: DriverRepository) {
    composable(Routes.TRIP_PICKUP) { PickupRoute(navController, repository) }     // D13
    composable(Routes.TRIP_BOARDING) { BoardingRoute(navController, repository) } // D14
    composable(Routes.TRIP_DRIVING) { DrivingRoute(navController, repository) }   // D15
    composable(Routes.TRIP_FARE) { FareRoute(navController, repository) }         // D16 (+D16b 키패드)
}
