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
import androidx.compose.material3.CircularProgressIndicator
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
import com.moyeota.driver.domain.model.CallException
import com.moyeota.driver.domain.model.CallSummary
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
import kotlin.coroutines.cancellation.CancellationException

// ── D10 콜 상세 · 수락 · 거절 ───────────────────────────────────────────

class CallDetailViewModel(
    private val repository: DriverRepository,
    private val callId: String,
) : ViewModel() {
    /**
     * 로딩·에러 상태가 푸시 임시 요약([preview])을 함께 나른다.
     * 상세 조회가 늦거나 실패해도 기사에게 출발지·도착지·합승 인원은 남겨야 하기 때문이다 —
     * 빈 로딩 화면이나 "불러오지 못했어요" 한 줄만 띄우면 콜을 받을지 판단할 근거가 사라진다.
     */
    sealed interface UiState {
        data class Loading(val preview: CallSummary?) : UiState
        data class Success(val detail: CallDetail) : UiState
        data class Error(val message: String, val preview: CallSummary?) : UiState
    }

    /** 상세 조회 실패 1건 — 사용자 문구와 자동 재시도 가치 */
    private data class LoadFailure(val message: String, val retryable: Boolean)

    enum class Submitting { NONE, ACCEPT, DECLINE }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading(null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _submitting = MutableStateFlow(Submitting.NONE)
    val submitting: StateFlow<Submitting> = _submitting.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // 푸시(CALL_OPENED)로 이미 받아 둔 실제 출발·도착·인원 — 상세가 오기 전/실패해도 이건 보여준다
            val preview = repository.peekCallSummary(callId)
            _uiState.value = UiState.Loading(preview)

            var failure = fetchDetail()
            if (failure != null && failure.retryable) {
                // 배포 서버는 유휴 후 첫 응답이 수 초 걸려 한 번은 통째로 타임아웃된다.
                // 콜 카운트다운이 도는 동안 기사가 직접 재시도 버튼을 누르게 하는 대신 조용히 한 번 더 시도한다.
                failure = fetchDetail()
            }
            if (failure != null) _uiState.value = UiState.Error(failure.message, preview)
        }
    }

    /** 성공하면 Success 로 전이하고 null, 실패하면 실패 정보를 돌려준다 */
    private suspend fun fetchDetail(): LoadFailure? = try {
        _uiState.value = UiState.Success(repository.getCallDetail(callId))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadFailure(
            message = e.message?.takeIf { it.isNotBlank() } ?: "콜 정보를 불러오지 못했어요",
            // 마감된 콜·인증 만료는 다시 물어도 답이 같다 — 그 외(네트워크·서버)만 재시도한다
            retryable = (e as? CallException)?.retryable ?: true,
        )
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
            // 임시 요약이 있으면 로딩·에러에서도 콜 정보를 띄운다. 없을 때만 기존 로딩/에러 화면으로 떨어진다.
            is CallDetailViewModel.UiState.Loading -> state.preview?.let { preview ->
                CallPreviewScreen(
                    summary = preview,
                    loading = true,
                    errorMessage = null,
                    submitting = submitting,
                    actionError = actionError,
                    onReload = viewModel::refresh,
                    onAccept = { viewModel.accept(onAccepted) },
                    onDecline = { viewModel.decline(onBack) },
                )
            } ?: LoadingBox()

            is CallDetailViewModel.UiState.Error -> state.preview?.let { preview ->
                CallPreviewScreen(
                    summary = preview,
                    loading = false,
                    errorMessage = state.message,
                    submitting = submitting,
                    actionError = actionError,
                    onReload = viewModel::refresh,
                    onAccept = { viewModel.accept(onAccepted) },
                    onDecline = { viewModel.decline(onBack) },
                )
            } ?: ErrorBox(message = state.message, onRetry = viewModel::refresh)

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
        CallActionBar(
            submitting = submitting,
            actionError = actionError,
            acceptEnabled = remaining > 0,
            onAccept = onAccept,
            onDecline = onDecline,
        )
    }
}

/**
 * D10 대체 화면 — 서버 상세가 아직/끝내 오지 않았을 때 **푸시로 받은 실제 콜 정보**를 보여준다.
 *
 * 예전에는 이 자리에서 더미 콜 상세(강남역 2번 출구 · 김*진 · 판교역)가 떴다. 조회 실패를 조용히
 * 더미로 메우면 기사는 존재하지 않는 승객·목적지를 보고 수락 여부를 판단하게 된다 — 그래서 지어낸
 * 정보 대신 CALL_OPENED 푸시에 실려 온 출발지·도착지·합승 인원만 띄우고, 모르는 값은 비워 둔다.
 *
 * 수락·거절 버튼은 상세 없이도 눌린다. 콜의 유효성은 서버가 판정하므로(마감이면 409 → "이미 마감된 콜"),
 * 조회 실패를 이유로 수락 기회까지 막을 이유가 없다.
 */
@Composable
private fun CallPreviewScreen(
    summary: CallSummary,
    loading: Boolean,
    errorMessage: String?,
    submitting: CallDetailViewModel.Submitting,
    actionError: String?,
    onReload: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val isPool = summary.type == CallType.POOL
    val passengerLabel = if (summary.hasPassengerCount) "승객 ${summary.passengerCount}인" else "승객"

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 콜 요약 — 인원을 모르면 "합승 콜"까지만, 요금을 모르면 금액 대신 "확인 중"
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
                        text = when {
                            isPool && summary.hasPassengerCount -> "합승 ${summary.passengerCount}인 콜"
                            isPool -> "합승 콜"
                            else -> "단독 콜"
                        },
                        style = MoyeotaType.DisplayMd,
                        color = MoyeotaColor.TextOnDark,
                        modifier = Modifier.weight(1f),
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MoyeotaColor.TextOnDark,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Text(
                    text = if (summary.hasFareEstimate) {
                        "예상 수익 ${won(summary.expectedTotal)}"
                    } else {
                        "예상 수익 확인 중"
                    },
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextOnDark,
                )
            }

            // 출발 · 도착 — 푸시 payload 의 실제 값
            StopRow(RouteStop(order = 0, kind = StopKind.PICKUP, place = summary.pickupPlace, passengerMaskedName = passengerLabel))
            StopRow(RouteStop(order = 1, kind = StopKind.DROPOFF, place = summary.dropoffPlace, passengerMaskedName = passengerLabel))

            if (errorMessage != null) {
                NoticeBanner(
                    kind = NoticeKind.ERROR,
                    text = "$errorMessage · 위 내용은 콜 알림으로 받은 정보예요",
                )
                SecondaryButton(text = "다시 불러오기", onClick = onReload, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = "픽업 거리 · 경유 순서를 불러오는 중이에요",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextMute,
                )
            }
        }

        CallActionBar(
            submitting = submitting,
            actionError = actionError,
            // 상세를 못 받은 상태에서도 수락은 열어 둔다 — 마감 여부는 서버가 답한다
            acceptEnabled = true,
            onAccept = onAccept,
            onDecline = onDecline,
        )
    }
}

/** 하단 CTA — 거절(보조) / 수락(주). 상세 화면과 임시 요약 화면이 같은 바를 쓴다 */
@Composable
private fun CallActionBar(
    submitting: CallDetailViewModel.Submitting,
    actionError: String?,
    acceptEnabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
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
                enabled = acceptEnabled && submitting == CallDetailViewModel.Submitting.NONE,
                loading = submitting == CallDetailViewModel.Submitting.ACCEPT,
            )
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
