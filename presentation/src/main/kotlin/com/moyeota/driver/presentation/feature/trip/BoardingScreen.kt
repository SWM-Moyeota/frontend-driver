package com.moyeota.driver.presentation.feature.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPassenger
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D14 · 승객 탑승 확인. 진입: D13 「도착 · 탑승 확인」.
// 승객별 「탑승 확인」 탭 · 노쇼 처리 (탑승 코드 검증 없음 — 서버 API 미존재로 제거).
// 전원 처리 후 「운행 시작」 → D15.
// 승객 전원 노쇼면 콜 대기(HOME) 복귀.

class BoardingViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val trip: ActiveTrip,
            val processingPassengerId: String? = null,
            val actionError: String? = null,
        ) : UiState

        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val trip = repository.getActiveTrip()
                _uiState.value = if (trip == null) {
                    UiState.Error("진행 중인 운행이 없어요")
                } else {
                    UiState.Success(trip)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("운행 정보를 불러오지 못했어요")
            }
        }
    }

    fun confirmBoarding(passengerId: String) {
        runAction(passengerId) { trip -> repository.confirmBoarding(trip.id, passengerId) }
    }

    fun markNoShow(passengerId: String) {
        runAction(passengerId) { trip -> repository.markNoShow(trip.id, passengerId) }
    }

    private fun runAction(passengerId: String, action: suspend (ActiveTrip) -> ActiveTrip) {
        val current = _uiState.value as? UiState.Success ?: return
        if (current.processingPassengerId != null) return
        viewModelScope.launch {
            _uiState.update {
                (it as UiState.Success).copy(processingPassengerId = passengerId, actionError = null)
            }
            try {
                val updated = action(current.trip)
                _uiState.value = UiState.Success(trip = updated)
            } catch (e: Exception) {
                _uiState.update {
                    (it as UiState.Success).copy(
                        processingPassengerId = null,
                        actionError = "처리하지 못했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { BoardingViewModel(repository) }
        }
    }
}

@Composable
fun BoardingRoute(navController: NavHostController, repository: DriverRepository) {
    val viewModel: BoardingViewModel = viewModel(factory = BoardingViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()

    BackHandler { /* 운행 플로우 — 뒤로가기 차단 */ }

    when (val s = state) {
        is BoardingViewModel.UiState.Loading -> LoadingBox()
        is BoardingViewModel.UiState.Error -> ErrorBox(message = s.message, onRetry = viewModel::refresh)
        is BoardingViewModel.UiState.Success -> BoardingScreen(
            trip = s.trip,
            processingPassengerId = s.processingPassengerId,
            actionError = s.actionError,
            onConfirmBoarding = viewModel::confirmBoarding,
            onMarkNoShow = viewModel::markNoShow,
            onStartTrip = {
                navController.navigate(Routes.TRIP_DRIVING) {
                    popUpTo(Routes.TRIP_BOARDING) { inclusive = true }
                }
            },
            onAllNoShow = {
                // 승객 전원 노쇼 → 콜 대기 복귀
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = false }
                    launchSingleTop = true
                }
            },
        )
    }
}

@Composable
private fun BoardingScreen(
    trip: ActiveTrip,
    processingPassengerId: String?,
    actionError: String?,
    onConfirmBoarding: (passengerId: String) -> Unit,
    onMarkNoShow: (passengerId: String) -> Unit,
    onStartTrip: () -> Unit,
    onAllNoShow: () -> Unit,
) {
    val allProcessed = trip.passengers.all { it.boarded || it.noShow }
    val boardedCount = trip.passengers.count { it.boarded }
    val allNoShow = allProcessed && boardedCount == 0
    val pickupPlace = trip.stops.firstOrNull { it.kind == StopKind.PICKUP }?.place ?: "픽업지"

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "승객 탑승")

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 도착 안내 카드
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
                    .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "$pickupPlace 도착", style = MoyeotaType.NumberLg, color = MoyeotaColor.InkPrimary)
                Text(
                    text = "승객 ${trip.passengers.size}명 탑승을 확인하고 운행을 시작해 주세요",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextBody,
                )
            }

            trip.passengers.forEachIndexed { index, passenger ->
                PassengerCard(
                    passenger = passenger,
                    order = index + 1,
                    processing = processingPassengerId == passenger.id,
                    actionLocked = processingPassengerId != null,
                    onConfirm = { onConfirmBoarding(passenger.id) },
                    onNoShow = { onMarkNoShow(passenger.id) },
                )
            }

            if (!allProcessed) {
                NoticeBanner(
                    kind = NoticeKind.WAITING,
                    text = "5분 넘게 안 오면 노쇼로 처리할 수 있어요",
                )
            }
            if (allNoShow) {
                NoticeBanner(
                    kind = NoticeKind.ERROR,
                    text = "승객 전원 노쇼 — 콜 대기로 복귀해 주세요",
                )
            }
            if (actionError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = actionError)
            }
        }

        // 하단 CTA — 탑승 1명 이상 + 전원 처리 시 운행 시작
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
        ) {
            if (allNoShow) {
                PrimaryCtaButton(
                    text = "콜 대기로 복귀",
                    onClick = onAllNoShow,
                    modifier = Modifier.height(60.dp),
                )
            } else {
                PrimaryCtaButton(
                    text = "운행 시작",
                    onClick = onStartTrip,
                    // D14 스펙: 탑승 확인 1명 이상이면 활성 (전원 처리 대기 없음)
                    enabled = boardedCount >= 1,
                    modifier = Modifier.height(60.dp),
                )
            }
        }
    }
}

@Composable
private fun PassengerCard(
    passenger: TripPassenger,
    order: Int,
    processing: Boolean,
    actionLocked: Boolean,
    onConfirm: () -> Unit,
    onNoShow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${passenger.maskedName} · 승객 $order",
                style = MoyeotaType.HeadingLg,
                color = MoyeotaColor.InkPrimary,
            )
            when {
                passenger.boarded -> StatusBadge(kind = NoticeKind.SUCCESS, text = "탑승")
                passenger.noShow -> StatusBadge(kind = NoticeKind.ERROR, text = "노쇼")
                else -> StatusBadge(kind = NoticeKind.WAITING, text = "대기")
            }
        }

        Text(
            text = "${passenger.pickupPlace} → ${passenger.dropoffPlace}",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )

        if (!passenger.boarded && !passenger.noShow) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "노쇼 처리",
                    onClick = onNoShow,
                    enabled = !actionLocked,
                    modifier = Modifier.weight(1f).height(60.dp),
                )
                PrimaryCtaButton(
                    text = "탑승 확인",
                    onClick = onConfirm,
                    enabled = !actionLocked,
                    loading = processing,
                    modifier = Modifier.weight(1f).height(60.dp),
                )
            }
        }
    }
}
