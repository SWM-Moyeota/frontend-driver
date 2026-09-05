package com.moyeota.driver.presentation.feature.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.AvatarCircle
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPassenger
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── D11 배차 확정 ───────────────────────────────────────────────────────

class CallAssignedViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val trip: ActiveTrip) : UiState
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
                _uiState.value = if (trip != null) {
                    UiState.Success(trip)
                } else {
                    UiState.Error("배차 정보를 찾을 수 없어요")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("배차 정보를 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { CallAssignedViewModel(repository) }
        }
    }
}

@Composable
fun CallAssignedRoute(
    repository: DriverRepository,
    onStartPickup: () -> Unit,
) {
    val viewModel: CallAssignedViewModel = viewModel(factory = CallAssignedViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    // 배차 이후 운행 플로우 — 뒤로가기 차단 (스택 초기화, 운행 플로우로만 진행)
    BackHandler { }

    Column(Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "배차 확정")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
                is CallAssignedViewModel.UiState.Loading -> LoadingBox()
                is CallAssignedViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
                is CallAssignedViewModel.UiState.Success -> CallAssignedScreen(
                    trip = state.trip,
                    onStartPickup = onStartPickup,
                )
            }
        }
    }
}

@Composable
private fun CallAssignedScreen(
    trip: ActiveTrip,
    onStartPickup: () -> Unit,
) {
    val isPool = trip.type == CallType.POOL
    val firstPickup = trip.stops.firstOrNull { it.kind == StopKind.PICKUP }
    val waitingCount = trip.passengers.count { !it.boarded && !it.noShow }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 합승이면 보너스 적립 예정 SUCCESS 배너 (고지 → 확정 → 정산 3단 일치)
        if (isPool && trip.poolBonus > 0) {
            NoticeBanner(
                kind = NoticeKind.SUCCESS,
                text = "✓ 배차 확정 · 합승 보너스 ${won(trip.poolBonus)} 적립 예정",
            )
        } else {
            NoticeBanner(
                kind = NoticeKind.SUCCESS,
                text = "✓ 배차가 확정됐어요 · 픽업지로 이동해 주세요",
            )
        }

        // 픽업지 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MoyeotaColor.SurfaceCard)
                .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = firstPickup?.place ?: "픽업지 확인 중",
                style = MoyeotaType.DisplayLg,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = "픽업까지 ${km(trip.remainingKm)} · ${trip.remainingMin}분 · 승객 ${waitingCount}명 대기 중",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
        }

        Text(
            text = "탑승 승객",
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.TextMute,
        )
        trip.passengers.forEachIndexed { index, passenger ->
            PassengerRow(index = index, passenger = passenger)
        }

        Spacer(Modifier.weight(1f))

        // 픽업 이동 시작 → D13 픽업 이동 (운행 플로우)
        PrimaryCtaButton(text = "픽업 이동 시작", onClick = onStartPickup)

        // 콜 취소 요청 — 취소 사유 시트 미연결 (확정 후 취소는 패널티 경고 대상, 스펙 미연결 항목)
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "콜 취소 요청",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
    }
}

@Composable
private fun PassengerRow(index: Int, passenger: TripPassenger) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = passenger.maskedName,
                    style = MoyeotaType.HeadingLg,
                    color = MoyeotaColor.InkPrimary,
                )
                Box(
                    modifier = Modifier
                        .background(MoyeotaColor.SurfaceSoft, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "승객 ${index + 1}",
                        style = MoyeotaType.CaptionMd,
                        color = MoyeotaColor.TextBody,
                    )
                }
            }
            Text(
                text = "${passenger.pickupPlace} → ${passenger.dropoffPlace}",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
        }
    }
}
