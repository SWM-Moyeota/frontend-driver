package com.moyeota.driver.presentation.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.driver.domain.call.CallAlertBus
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.model.RestoredSession
import com.moyeota.driver.domain.model.TripPhase
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.feature.auth.authGraph
import com.moyeota.driver.presentation.feature.call.callGraph
import com.moyeota.driver.presentation.feature.history.historyGraph
import com.moyeota.driver.presentation.feature.home.homeGraph
import com.moyeota.driver.presentation.feature.settlement.settlementGraph
import com.moyeota.driver.presentation.feature.trip.tripGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// 그룹별 그래프는 각 feature 디렉토리의 {group}Graph 확장 함수가 소유한다.
// 공유 파일 충돌 방지: 이 파일과 Routes.kt 는 리더(오케스트레이터)만 수정한다.

/**
 * 앱의 네비게이션 진입점. 첫 화면은 **복구 결과가 정한다** — 운행 중 앱이 죽어도(강제 종료·시스템 회수)
 * 재실행하면 재로그인 없이 그 운행 화면으로 돌아온다. 판정 규칙은 [startRouteFor] 참고.
 */
@Composable
fun MainNavGraph(
    repository: DriverRepository,
    locationSource: DriverLocationSource,
) {
    // 앱 재실행 복구 — 저장된 세션과 진행 중이던 운행을 확인해 첫 화면을 정한다.
    // NavHost 의 startDestination 은 최초 구성에서 확정돼야 하므로, 판정이 끝날 때까지 로딩만 그린다.
    // rememberSaveable 이라 구성 변경(회전 등) 재생성에서는 복구를 다시 돌지 않는다.
    var startDestination by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (startDestination != null) return@LaunchedEffect
        // 복구가 실패하거나 늦어도 앱은 떠야 한다 — 로그인 화면이 최종 폴백.
        // 로그인으로 떨어져도 운행을 잃지 않는다: 로그인 성공 시 저장된 운행을 다시 복원해 그 화면으로 보낸다.
        // 상한을 두는 이유 — 서버가 연결만 받고 응답을 안 주면 리포지토리 재시도(3회)가 1분 가까이 걸린다.
        val restored = try {
            withTimeoutOrNull(RESTORE_TIMEOUT_MS) { startRouteFor(repository.restoreSession()) }
        } catch (e: CancellationException) {
            throw e   // 화면 이탈로 인한 취소는 삼키지 않는다
        } catch (e: Exception) {
            null
        }
        startDestination = restored ?: Routes.AUTH_LOGIN
    }

    val resolvedStart = startDestination
    if (resolvedStart == null) {
        Box(Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) { LoadingBox() }
        return
    }

    DriverNavHost(
        repository = repository,
        locationSource = locationSource,
        startDestination = resolvedStart,
    )
}

/** 복구가 이 시간을 넘기면 기다리지 않고 로그인 화면으로 — 운행은 로그인 후 복원된다 */
private const val RESTORE_TIMEOUT_MS = 15_000L

/** 복구 결과 → 첫 화면 */
private fun startRouteFor(session: RestoredSession): String {
    if (!session.loggedIn) return Routes.AUTH_LOGIN
    val trip = session.ongoingTrip ?: return Routes.HOME
    return tripRouteFor(trip.phase)
}

/**
 * 진행 중 운행의 단계 → 복귀할 화면.
 *
 * 서버 파티 상태로는 D11(배차 확정)과 D13(픽업 이동)을 구분할 수 없다 — 둘 다 DRIVER_ASSIGNED 다.
 * D11 은 수락 직후 스쳐 가는 확인 화면이므로, 현장에서 의미 있는 D13(픽업 이동)으로 복원한다.
 */
internal fun tripRouteFor(phase: TripPhase): String = when (phase) {
    TripPhase.ASSIGNED, TripPhase.TO_PICKUP, TripPhase.BOARDING -> Routes.TRIP_PICKUP
    else -> Routes.TRIP_DRIVING
}

/**
 * 그래프 본체. 콜 인입(CALL_OPENED) 시 콜 상세(D10)로 **자동 전환**한다.
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
private fun DriverNavHost(
    repository: DriverRepository,
    locationSource: DriverLocationSource,
    startDestination: String,
) {
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

    NavHost(navController = navController, startDestination = startDestination) {
        authGraph(navController, repository)
        homeGraph(navController, repository, locationSource)
        callGraph(navController, repository)
        tripGraph(navController, repository, locationSource)
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
