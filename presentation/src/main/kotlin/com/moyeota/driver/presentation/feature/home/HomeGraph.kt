package com.moyeota.driver.presentation.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes

// GRP/B 홈 · 영업 상태 — D06(휴무)·D07(영업중)은 Routes.HOME 상태 분기, D08은 별도 라우트.
fun NavGraphBuilder.homeGraph(navController: NavHostController, repository: DriverRepository) {
    composable(Routes.HOME) { // D06 · D07
        HomeRoute(
            repository = repository,
            onTabSelect = { tab -> navController.navigateToTab(tab) },
            onNavigateOffDutyConfirm = { navController.navigate(Routes.HOME_OFF_DUTY_CONFIRM) },
            onNavigateCallList = { navController.navigate(Routes.CALL_LIST) },
            onNavigatePromotion = { navController.navigate(Routes.CALL_PROMOTION) },
        )
    }
    composable(Routes.HOME_OFF_DUTY_CONFIRM) { // D08
        OffDutyConfirmRoute(
            repository = repository,
            onBack = { navController.popBackStack() }, // 「조금 더 운행」· 뒤로가기 → D07 복귀
            onDutyEnded = {
                // 영업 종료 확정 → HOME(휴무) 복귀. HOME 재생성으로 dutyStatus 재조회
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
            },
            onNavigateSettlement = { navController.navigate(Routes.SETTLEMENT) },
        )
    }
}

// 하단탭 라우팅 — 계약 문서의 공통 매핑 (MYPAGE는 마이 화면 미작성으로 평점을 임시 탭으로)
private fun NavHostController.navigateToTab(tab: MoyeotaTab) {
    val route = when (tab) {
        MoyeotaTab.HOME -> Routes.HOME
        MoyeotaTab.CALLS -> Routes.CALL_LIST
        MoyeotaTab.SETTLEMENT -> Routes.SETTLEMENT
        MoyeotaTab.MYPAGE -> Routes.RATING
    }
    if (currentDestination?.route != route) {
        navigate(route)
    }
}
