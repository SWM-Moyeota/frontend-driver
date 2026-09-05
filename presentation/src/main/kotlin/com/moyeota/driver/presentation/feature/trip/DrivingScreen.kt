package com.moyeota.driver.presentation.feature.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SafetyButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPassenger
import com.moyeota.driver.domain.model.TripPhase
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D15 · 운행 중 — 네비 · 하차 처리. 진입: D14 「운행 시작」.
// 경유 순서대로 하차 처리. 마지막 하차 완료(FARE_INPUT) 시 D16으로 자동 이동.
// 운행 플로우 공통: 뒤로가기 차단 + 화면 꺼짐 방지.

class DrivingViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val trip: ActiveTrip,
            val processing: Boolean = false,
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

    fun completeDropoff(passengerId: String) {
        val current = _uiState.value as? UiState.Success ?: return
        if (current.processing) return
        viewModelScope.launch {
            _uiState.update { (it as UiState.Success).copy(processing = true, actionError = null) }
            try {
                val updated = repository.completeDropoff(current.trip.id, passengerId)
                _uiState.value = UiState.Success(trip = updated)
            } catch (e: Exception) {
                _uiState.update {
                    (it as UiState.Success).copy(
                        processing = false,
                        actionError = "하차 처리하지 못했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { DrivingViewModel(repository) }
        }
    }
}

@Composable
fun DrivingRoute(navController: NavHostController, repository: DriverRepository) {
    val viewModel: DrivingViewModel = viewModel(factory = DrivingViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()

    BackHandler { /* 운행 플로우 — 뒤로가기 차단 */ }
    KeepScreenOn()

    // 마지막 하차 완료 → D16 최종 요금 입력으로 자동 이동
    val fareReady = (state as? DrivingViewModel.UiState.Success)?.trip?.phase == TripPhase.FARE_INPUT
    LaunchedEffect(fareReady) {
        if (fareReady) {
            navController.navigate(Routes.TRIP_FARE) {
                popUpTo(Routes.TRIP_DRIVING) { inclusive = true }
            }
        }
    }

    when (val s = state) {
        is DrivingViewModel.UiState.Loading -> LoadingBox()
        is DrivingViewModel.UiState.Error -> ErrorBox(message = s.message, onRetry = viewModel::refresh)
        is DrivingViewModel.UiState.Success -> DrivingScreen(
            trip = s.trip,
            processing = s.processing,
            actionError = s.actionError,
            onDropoff = viewModel::completeDropoff,
        )
    }
}

@Composable
private fun DrivingScreen(
    trip: ActiveTrip,
    processing: Boolean,
    actionError: String?,
    onDropoff: (passengerId: String) -> Unit,
) {
    // 경유(하차) 순서대로 남은 승객 정렬
    val dropoffStops = trip.stops.filter { it.kind == StopKind.DROPOFF }
    val remaining: List<Pair<TripPassenger, String>> = dropoffStops.mapNotNull { stop ->
        trip.passengers
            .firstOrNull { it.maskedName == stop.passengerMaskedName && it.boarded && !it.droppedOff && !it.noShow }
            ?.let { it to stop.place }
    }
    val next = remaining.firstOrNull()

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 다음 하차지 카드 — 문구는 한 줄 3어절 이내
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.Primary600, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "다음 하차", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextOnDark)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = next?.second ?: "하차지 없음",
                        style = MoyeotaType.NumberLg,
                        color = MoyeotaColor.TextOnDark,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatKm(trip.remainingKm),
                        style = MoyeotaType.NumberXl,
                        color = MoyeotaColor.TextOnDark,
                    )
                }
                Text(
                    text = "경유 ${remaining.size}곳 · 남은 승객 ${remaining.size}명 · ${trip.remainingMin}분 남음",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextOnDark,
                )
            }

            // 내비 — 경유 순서 · 실시간 경로
            TripMap(
                pillText = "${formatKm(trip.remainingKm)} · ${trip.remainingMin}분 남음",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // 남은 하차 목록
            remaining.forEachIndexed { index, (passenger, place) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${index + 1} · ${passenger.maskedName}",
                            style = MoyeotaType.HeadingLg,
                            color = MoyeotaColor.InkPrimary,
                        )
                        Text(text = "$place 하차", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextBody)
                    }
                    if (index == 0) {
                        Text(text = formatKm(trip.remainingKm), style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
                    }
                }
            }

            if (actionError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = actionError)
            }
        }

        // 하단 CTA — 좌: 긴급 신고(시트 미연결, 표시만), 우: 하차 처리
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SafetyButton(
                text = "긴급 · 신고",
                onClick = { /* 긴급 신고 시트 미연결 — 표시만 */ },
                modifier = Modifier.weight(1f).height(60.dp),
            )
            PrimaryCtaButton(
                text = "하차 처리",
                onClick = { next?.let { onDropoff(it.first.id) } },
                enabled = next != null,
                loading = processing,
                modifier = Modifier.weight(2f).height(60.dp),
            )
        }
    }
}
