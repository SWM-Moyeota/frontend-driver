package com.moyeota.driver.presentation.feature.call

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/C 콜 — D09 콜 리스트 · D10 콜 상세 · D11 배차 확정 · D12 놓친 콜 · D22 합승 프로모션
fun NavGraphBuilder.callGraph(navController: NavHostController, repository: DriverRepository) {
    composable(Routes.CALL_LIST) { // D09
        CallListRoute(
            repository = repository,
            onCallClick = { id -> navController.navigate(Routes.callDetail(id)) },
            onMissedClick = { navController.navigate(Routes.CALL_MISSED) },
            onPromotionClick = { navController.navigate(Routes.CALL_PROMOTION) },
            onTabSelect = { tab -> navController.navigateToTab(tab) },
        )
    }
    composable(
        route = Routes.CALL_DETAIL, // D10 — callId는 백스택 인자
        arguments = listOf(navArgument("callId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val callId = backStackEntry.arguments?.getString("callId").orEmpty()
        CallDetailRoute(
            repository = repository,
            callId = callId,
            onBack = { navController.popBackStack() },
            onAccepted = {
                // 배차 이후 운행 플로우 — HOME까지 스택을 정리해 뒤로가기로 콜 화면에 돌아가지 못하게 한다
                navController.navigate(Routes.CALL_ASSIGNED) {
                    popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
            },
        )
    }
    composable(Routes.CALL_ASSIGNED) { // D11
        CallAssignedRoute(
            repository = repository,
            onStartPickup = { navController.navigate(Routes.TRIP_PICKUP) },
        )
    }
    composable(Routes.CALL_MISSED) { // D12
        MissedCallsRoute(
            repository = repository,
            onBack = { navController.popBackStack() },
            onTabSelect = { tab -> navController.navigateToTab(tab) },
        )
    }
    composable(Routes.CALL_PROMOTION) { // D22
        PromotionRoute(
            repository = repository,
            onBack = { navController.popBackStack() },
            onGoCalls = {
                navController.navigate(Routes.CALL_LIST) { launchSingleTop = true }
            },
            onAccruedClick = { navController.navigate(Routes.SETTLEMENT) },
        )
    }
}

// 하단탭 공통 라우팅 — 계약 문서: MYPAGE는 마이 화면 미작성이라 평점(D19)을 임시 마이 탭으로 쓴다
internal fun NavHostController.navigateToTab(tab: MoyeotaTab) {
    val route = when (tab) {
        MoyeotaTab.HOME -> Routes.HOME
        MoyeotaTab.CALLS -> Routes.CALL_LIST
        MoyeotaTab.SETTLEMENT -> Routes.SETTLEMENT
        MoyeotaTab.MYPAGE -> Routes.RATING
    }
    navigate(route) { launchSingleTop = true }
}

// 금액 공통 포맷 — "12,400원"
internal fun won(amount: Int): String = "%,d원".format(amount)

// 거리 공통 포맷 — "1.2km"
internal fun km(value: Double): String = "%.1fkm".format(value)
