package com.moyeota.driver.presentation.feature.history

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/F 평점 · 운행 이력 — D19 내 평점 · D20 운행 이력 목록 · D21 운행 이력 상세
fun NavGraphBuilder.historyGraph(navController: NavHostController, repository: DriverRepository) {
    // D19 내 평점 (하단탭 노출 — 마이 화면 미작성이라 평점이 임시 마이 탭)
    composable(Routes.RATING) {
        RatingRoute(
            onTabSelect = { tab -> navController.navigateToTab(tab) },
            onCommentClick = { tripId -> navController.navigate(Routes.historyDetail(tripId)) },
            onHistoryClick = { navController.navigate(Routes.HISTORY) },
            repository = repository,
        )
    }

    // D20 운행 이력 목록 (하단탭 노출 — 마이 탭 소속)
    composable(Routes.HISTORY) {
        TripHistoryRoute(
            onTabSelect = { tab -> navController.navigateToTab(tab) },
            onTripClick = { tripId -> navController.navigate(Routes.historyDetail(tripId)) },
            repository = repository,
        )
    }

    // D21 운행 이력 상세 — tripId는 navArgument
    composable(
        route = Routes.HISTORY_DETAIL,
        arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val tripId = backStackEntry.arguments?.getString("tripId").orEmpty()
        TripHistoryDetailRoute(
            tripId = tripId,
            onBack = { navController.popBackStack() },
            repository = repository,
        )
    }
}

// 하단탭 공통 라우팅 — 계약 문서: HOME → 홈, CALLS → 콜 리스트, SETTLEMENT → 정산, MYPAGE → 평점(임시 마이)
private fun NavHostController.navigateToTab(tab: MoyeotaTab) {
    val route = when (tab) {
        MoyeotaTab.HOME -> Routes.HOME
        MoyeotaTab.CALLS -> Routes.CALL_LIST
        MoyeotaTab.SETTLEMENT -> Routes.SETTLEMENT
        MoyeotaTab.MYPAGE -> Routes.RATING
    }
    navigate(route) { launchSingleTop = true }
}
