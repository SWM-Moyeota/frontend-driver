package com.moyeota.driver.presentation.feature.settlement

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/E 정산 — D17 결제 정산 내역 · D18 정산 상세 (건별·주간)
fun NavGraphBuilder.settlementGraph(navController: NavHostController, repository: DriverRepository) {
    composable(Routes.SETTLEMENT) {
        SettlementRoute(
            repository = repository,
            onTabSelect = { tab ->
                val route = when (tab) {
                    MoyeotaTab.HOME -> Routes.HOME
                    MoyeotaTab.CALLS -> Routes.CALL_LIST
                    MoyeotaTab.SETTLEMENT -> Routes.SETTLEMENT
                    MoyeotaTab.MYPAGE -> Routes.RATING // 마이 화면 미작성 — 평점을 임시 마이 탭으로
                }
                if (route != Routes.SETTLEMENT) {
                    navController.navigate(route) { launchSingleTop = true }
                }
            },
            onOpenDetail = { navController.navigate(Routes.SETTLEMENT_DETAIL) },
        )
    }
    composable(Routes.SETTLEMENT_DETAIL) {
        SettlementDetailRoute(
            repository = repository,
            onBack = { navController.popBackStack() },
        )
    }
}
