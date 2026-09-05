package com.moyeota.driver.presentation.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.moyeota.driver.domain.call.CallAlertBus
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.feature.auth.authGraph
import com.moyeota.driver.presentation.feature.call.callGraph
import com.moyeota.driver.presentation.feature.history.historyGraph
import com.moyeota.driver.presentation.feature.home.homeGraph
import com.moyeota.driver.presentation.feature.settlement.settlementGraph
import com.moyeota.driver.presentation.feature.trip.tripGraph

// 그룹별 그래프는 각 feature 디렉토리의 {group}Graph 확장 함수가 소유한다.
// 공유 파일 충돌 방지: 이 파일과 Routes.kt 는 리더(오케스트레이터)만 수정한다.

/**
 * 콜 인입(CALL_OPENED) 시 콜 상세(D10)로 **자동 전환**한다.
 *
 * 콜은 기사가 목록을 열어 찾아가는 게 아니라 즉시 응답해야 하는 인터럽트다 —
 * FCM 수신(앱 생존 시)과 알림/full-screen intent 탭(백그라운드)이 모두 [CallAlertBus] 로 모이고,
 * 여기서 한 번만 소비해 D10 으로 보낸다.
 *
 * 자동 전환을 억제하는 화면(콜을 가로채면 안 되는 맥락):
 * - `auth/` 프리픽스: 미로그인 — 콜 상세 조회가 401 로 깨진다.
 * - `trip/` 프리픽스 · `call/assigned`: 이미 배차받아 운행 중 — 진행 중 운행을 콜 화면이 덮으면 안 된다.
 * - 같은 콜의 `call/detail/{callId}`: 이미 그 콜을 보고 있다.
 */
@Composable
fun MainNavGraph(repository: DriverRepository) {
    val navController = rememberNavController()
    val pendingCall by CallAlertBus.pending.collectAsState()

    // 현재 목적지도 키에 넣는다 — 로그인 화면에서 받은 콜이 홈 진입 직후 다시 판정되도록.
    val currentEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(pendingCall, currentEntry) {
        val alert = pendingCall ?: return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        val currentRoute = entry.destination.route ?: return@LaunchedEffect
        if (!allowsCallInterrupt(currentRoute, alert.partyId, entry.arguments?.getString("callId"))) {
            return@LaunchedEffect
        }
        CallAlertBus.consume(alert.partyId)
        navController.navigate(Routes.callDetail(alert.partyId)) { launchSingleTop = true }
    }

    NavHost(navController = navController, startDestination = Routes.AUTH_LOGIN) {
        authGraph(navController, repository)
        homeGraph(navController, repository)
        callGraph(navController, repository)
        tripGraph(navController, repository)
        settlementGraph(navController, repository)
        historyGraph(navController, repository)
    }
}

/** 현재 화면이 콜 인입 자동 전환을 받아도 되는 맥락인지 */
private fun allowsCallInterrupt(currentRoute: String, partyId: String, currentCallId: String?): Boolean = when {
    currentRoute.startsWith("auth/") -> false
    currentRoute.startsWith("trip/") -> false
    currentRoute == Routes.CALL_ASSIGNED -> false
    currentRoute == Routes.CALL_DETAIL && currentCallId == partyId -> false
    else -> true
}
