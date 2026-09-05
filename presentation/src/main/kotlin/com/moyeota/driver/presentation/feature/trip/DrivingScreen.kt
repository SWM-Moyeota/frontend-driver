package com.moyeota.driver.presentation.feature.trip

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SafetyButton
import com.moyeota.core.designsystem.component.SecondaryButton
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
import kotlinx.coroutines.delay
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

    // ── 긴급 신고 (D15) ─────────────────────────────────────────────
    // 상태 전이: IDLE → (3초 홀드) REPORTED → (다이얼 복귀 ON_RESUME) AWAITING_CALL_RESULT → (통화 여부 기록) DONE
    enum class EmergencyPhase { IDLE, REPORTED, AWAITING_CALL_RESULT, DONE }

    data class EmergencyUiState(
        val phase: EmergencyPhase = EmergencyPhase.IDLE,
        val reportSaveFailed: Boolean = false,   // 신고 저장 실패 — 통화 여부 기록은 계속 진행
        val confirming: Boolean = false,          // confirmEmergencyCall 제출 중
        val confirmError: String? = null,         // 제출 실패 — 다이얼로그 유지 + 재선택
        val showReportedBanner: Boolean = false,  // 기록 완료 후 "신고가 접수됐어요" 잠깐 표시
    )

    private val _emergency = MutableStateFlow(EmergencyUiState())
    val emergency: StateFlow<EmergencyUiState> = _emergency.asStateFlow()

    /** 3초 홀드 완료 — 신고 저장을 발사한다. 112 다이얼러는 호출부가 즉시 연다 (통화 최우선, 저장 성패 무관) */
    fun onEmergencyHold(tripId: String) {
        val phase = _emergency.value.phase
        if (phase == EmergencyPhase.REPORTED || phase == EmergencyPhase.AWAITING_CALL_RESULT) return
        _emergency.value = EmergencyUiState(phase = EmergencyPhase.REPORTED)
        viewModelScope.launch {
            try {
                repository.reportEmergency(tripId) // 성공 시 조용히 — 위치는 데이터 계층이 채운다
            } catch (e: Exception) {
                _emergency.update { it.copy(reportSaveFailed = true) }
            }
        }
    }

    /** 다이얼 복귀 감지(ON_RESUME) — 신고 직후 복귀면 통화 여부 질문으로 전환 */
    fun onScreenResumed() {
        _emergency.update {
            if (it.phase == EmergencyPhase.REPORTED) it.copy(phase = EmergencyPhase.AWAITING_CALL_RESULT) else it
        }
    }

    fun confirmEmergencyCall(called: Boolean) {
        if (_emergency.value.confirming) return
        viewModelScope.launch {
            _emergency.update { it.copy(confirming = true, confirmError = null) }
            try {
                repository.confirmEmergencyCall(called)
                _emergency.update {
                    it.copy(phase = EmergencyPhase.DONE, confirming = false, showReportedBanner = true)
                }
            } catch (e: Exception) {
                _emergency.update {
                    it.copy(confirming = false, confirmError = "통화 여부를 저장하지 못했어요 · 다시 선택해 주세요")
                }
            }
        }
    }

    fun dismissReportedBanner() {
        _emergency.update { it.copy(showReportedBanner = false) }
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
    val emergency by viewModel.emergency.collectAsState()
    val context = LocalContext.current

    BackHandler { /* 운행 플로우 — 뒤로가기 차단 */ }
    KeepScreenOn()

    // 다이얼 복귀 감지 — ON_RESUME 시점에 신고 직후(REPORTED)면 통화 여부 질문으로 전환
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 짧은 탭 안내 — 배너로 잠시 표시 후 자동 소멸 (Toast 금지)
    var holdHintTick by remember { mutableIntStateOf(0) }
    var holdHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(holdHintTick) {
        if (holdHintTick > 0) {
            holdHintVisible = true
            delay(2_500)
            holdHintVisible = false
        }
    }

    // "신고가 접수됐어요" 배너 — 잠깐 표시 후 자동 소멸
    LaunchedEffect(emergency.showReportedBanner) {
        if (emergency.showReportedBanner) {
            delay(3_000)
            viewModel.dismissReportedBanner()
        }
    }

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
            emergency = emergency,
            holdHintVisible = holdHintVisible,
            onDropoff = viewModel::completeDropoff,
            onEmergencyHold = {
                viewModel.onEmergencyHold(s.trip.id)
                // 신고 저장 성패와 무관하게 112 다이얼러를 즉시 연다 (통화 최우선)
                // ACTION_DIAL — 권한 불필요, 번호 입력된 다이얼 화면. 통화 버튼은 사용자가 누른다
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            },
            onEmergencyShortPress = { holdHintTick++ },
            onConfirmCall = viewModel::confirmEmergencyCall,
        )
    }
}

@Composable
private fun DrivingScreen(
    trip: ActiveTrip,
    processing: Boolean,
    actionError: String?,
    emergency: DrivingViewModel.EmergencyUiState,
    holdHintVisible: Boolean,
    onDropoff: (passengerId: String) -> Unit,
    onEmergencyHold: () -> Unit,
    onEmergencyShortPress: () -> Unit,
    onConfirmCall: (called: Boolean) -> Unit,
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
            // 신고 버튼 짧은 탭 안내 (Toast 금지 — 화면 내 배너로 일시 표시)
            if (holdHintVisible) {
                NoticeBanner(kind = NoticeKind.INFO, text = "3초간 길게 누르면 신고돼요")
            }
            // 통화 여부 기록 완료 — 잠깐 표시
            if (emergency.showReportedBanner) {
                NoticeBanner(kind = NoticeKind.SUCCESS, text = "신고가 접수됐어요")
            }
        }

        // 하단 CTA — 좌: 긴급 신고(3초 홀드 → 신고 저장 + 112 다이얼), 우: 하차 처리
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SafetyButton(
                text = "긴급 · 신고",
                onHoldComplete = onEmergencyHold,
                onShortPress = onEmergencyShortPress,
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

    // 다이얼 복귀 후 통화 여부 확인 — 응답 전 임의 닫기 차단, 화면 전이(운행 완료) 시엔 함께 소멸
    if (emergency.phase == DrivingViewModel.EmergencyPhase.AWAITING_CALL_RESULT) {
        EmergencyCallResultDialog(
            reportSaveFailed = emergency.reportSaveFailed,
            confirming = emergency.confirming,
            confirmError = emergency.confirmError,
            onConfirm = onConfirmCall,
        )
    }
}

/** 통화 여부 확인 다이얼로그 — 다크 토큰 기반 커스텀 (Material AlertDialog 미사용, 프로젝트 관례) */
@Composable
private fun EmergencyCallResultDialog(
    reportSaveFailed: Boolean,
    confirming: Boolean,
    confirmError: String?,
    onConfirm: (called: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = { /* 뒤로가기·외부 탭으로 닫히지 않음 */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "112와 실제로 통화하셨나요?",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.InkPrimary,
            )
            if (reportSaveFailed) {
                NoticeBanner(
                    kind = NoticeKind.ERROR,
                    text = "신고 정보 저장에 실패했어요 · 통화 여부는 기록됩니다",
                )
            }
            if (confirmError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = confirmError)
            }
            PrimaryCtaButton(
                text = "통화했어요",
                onClick = { onConfirm(true) },
                loading = confirming,
                modifier = Modifier.height(60.dp),
            )
            SecondaryButton(
                text = "통화 안 했어요",
                onClick = { onConfirm(false) },
                enabled = !confirming,
                modifier = Modifier.height(60.dp),
            )
        }
    }
}
