package com.moyeota.driver.presentation.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.NaverMapView
import com.moyeota.driver.presentation.core.GangnamCenter
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.CallDetail
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.RouteStop
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.BackStateScaffold
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── D10 콜 상세 · 수락 · 거절 ───────────────────────────────────────────

class CallDetailViewModel(
    private val repository: DriverRepository,
    private val callId: String,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val detail: CallDetail) : UiState
        data class Error(val message: String) : UiState
    }

    enum class Submitting { NONE, ACCEPT, DECLINE }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _submitting = MutableStateFlow(Submitting.NONE)
    val submitting: StateFlow<Submitting> = _submitting.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getCallDetail(callId))
            } catch (e: Exception) {
                _uiState.value = UiState.Error("콜 정보를 불러오지 못했어요")
            }
        }
    }

    fun accept(onAccepted: () -> Unit) {
        if (_submitting.value != Submitting.NONE) return
        viewModelScope.launch {
            _submitting.value = Submitting.ACCEPT
            _actionError.value = null
            try {
                repository.acceptCall(callId)
                onAccepted()
            } catch (e: Exception) {
                _submitting.value = Submitting.NONE
                // 콜 TTL 만료·타 기사 선점(409 CALL_CLOSED)은 재시도해도 소용없다 —
                // repository 가 만든 "이미 마감된 콜" 문구를 그대로 보여준다.
                _actionError.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "수락하지 못했어요 · 잠시 후 다시 시도해 주세요"
            }
        }
    }

    fun decline(onDeclined: () -> Unit) {
        if (_submitting.value != Submitting.NONE) return
        viewModelScope.launch {
            _submitting.value = Submitting.DECLINE
            _actionError.value = null
            try {
                repository.declineCall(callId)
                onDeclined()
            } catch (e: Exception) {
                _submitting.value = Submitting.NONE
                _actionError.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "거절 처리에 실패했어요 · 잠시 후 다시 시도해 주세요"
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository, callId: String) = viewModelFactory {
            initializer { CallDetailViewModel(repository, callId) }
        }
    }
}

@Composable
fun CallDetailRoute(
    repository: DriverRepository,
    callId: String,
    onBack: () -> Unit,
    onAccepted: () -> Unit,
) {
    val viewModel: CallDetailViewModel = viewModel(
        key = "call-detail-$callId",
        factory = CallDetailViewModel.factory(repository, callId),
    )
    val uiState by viewModel.uiState.collectAsState()
    val submitting by viewModel.submitting.collectAsState()
    val actionError by viewModel.actionError.collectAsState()

    BackStateScaffold(title = "콜 상세", onBack = onBack) {
        when (val state = uiState) {
            is CallDetailViewModel.UiState.Loading -> LoadingBox()
            is CallDetailViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is CallDetailViewModel.UiState.Success -> CallDetailScreen(
                detail = state.detail,
                submitting = submitting,
                actionError = actionError,
                onAccept = { viewModel.accept(onAccepted) },
                onDecline = { viewModel.decline(onBack) },
                onExpired = onBack,
            )
        }
    }
}

@Composable
private fun CallDetailScreen(
    detail: CallDetail,
    submitting: CallDetailViewModel.Submitting,
    actionError: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onExpired: () -> Unit,
) {
    val isPool = detail.summary.type == CallType.POOL

    // 수락 제한 15초 카운트다운 — 만료 시 자동으로 뒤로.
    // 놓친 콜 기록은 더미 연동이라 생략 (실연동 시 서버가 미응답으로 집계해 D12에 기록).
    var remaining by rememberSaveable(detail.summary.id) { mutableIntStateOf(detail.countdownSeconds) }
    val currentSubmitting by rememberUpdatedState(submitting)
    val currentOnExpired by rememberUpdatedState(onExpired)
    LaunchedEffect(detail.summary.id) {
        while (remaining > 0) {
            delay(1_000)
            // 제출 중에는 타이머를 멈춰 수락 처리와 만료 이탈이 겹치지 않게 한다
            if (currentSubmitting == CallDetailViewModel.Submitting.NONE) remaining--
        }
        if (currentSubmitting == CallDetailViewModel.Submitting.NONE) currentOnExpired()
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 지도 — 픽업 · 경유 · 하차 경로
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                NaverMapView(modifier = Modifier.fillMaxSize(), center = GangnamCenter)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .background(MoyeotaColor.SurfaceCard)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "픽업 ${km(detail.summary.distanceToPickupKm)} · ${detail.etaToPickupMin}분",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.InkPrimary,
                    )
                }
            }

            // 콜 요약 + 카운트다운 카드
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.Primary500)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isPool) "합승 ${detail.summary.passengerCount}인 콜" else "단독 콜",
                        style = MoyeotaType.DisplayMd,
                        color = MoyeotaColor.TextOnDark,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (remaining > 0) "수락까지 ${remaining}초" else "시간 만료",
                        style = MoyeotaType.HeadingLg,
                        color = MoyeotaColor.TextOnDark,
                    )
                }
                Text(
                    text = if (isPool) {
                        "합승 보너스 +${won(detail.summary.poolBonus)} 포함 · 승객 ${detail.summary.passengerCount}명"
                    } else {
                        "단독 호출 · 승객 ${detail.summary.passengerCount}명"
                    },
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextOnDark,
                )
            }

            // 픽업 · 하차 경유 순서
            detail.stops.forEach { stop -> StopRow(stop) }

            // 픽업 거리
            InfoRow(
                title = "픽업 거리",
                subtitle = "도착까지 약 ${detail.etaToPickupMin}분",
                value = km(detail.summary.distanceToPickupKm),
            )

            // 예상 수익 = 미터기 + 호출료 + 보너스 합산 (수락 직전 마지막 고지)
            InfoRow(
                title = if (isPool) "합승 정산 예상 수익" else "예상 수익",
                subtitle = buildString {
                    append("미터기 ${won(detail.summary.expectedFare)} + 호출료 ${won(detail.summary.callFee)}")
                    if (detail.summary.poolBonus > 0) append(" + 보너스 ${won(detail.summary.poolBonus)}")
                },
                value = won(detail.summary.expectedTotal),
            )
        }

        // 하단 CTA — 거절(보조) / 수락(주). 수락은 카운트다운 동안만 활성
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            if (actionError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = actionError)
                Box(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "거절",
                    onClick = onDecline,
                    modifier = Modifier.width(112.dp),
                    enabled = submitting == CallDetailViewModel.Submitting.NONE,
                )
                PrimaryCtaButton(
                    text = "수락하기",
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                    enabled = remaining > 0,
                    loading = submitting == CallDetailViewModel.Submitting.ACCEPT,
                )
            }
        }
    }
}

@Composable
private fun StopRow(stop: RouteStop) {
    val (dotColor, label) = when (stop.kind) {
        StopKind.PICKUP -> MoyeotaColor.MarkerPickup to "픽업"
        StopKind.DROPOFF -> MoyeotaColor.MarkerDropoff to "하차"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceCard)
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(11.dp).background(dotColor, CircleShape))
        Text(
            text = stop.place,
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.InkPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$label · ${stop.passengerMaskedName}",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceCard)
            .heightIn(min = 84.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
            Text(text = subtitle, style = MoyeotaType.BodyLg, color = MoyeotaColor.TextBody)
        }
        Text(text = value, style = MoyeotaType.NumberMd, color = MoyeotaColor.InkPrimary)
    }
}
