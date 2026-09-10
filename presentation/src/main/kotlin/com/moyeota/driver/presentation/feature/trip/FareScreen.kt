package com.moyeota.driver.presentation.feature.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.navigation.NavHostController
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.FareResult
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D16 · 최종 요금 입력 · 운행 완료 (+ D16b 미터기 금액 입력 키패드 — 화면 내부 전환 뷰).
// 진입: D15 「운행 완료」. 확정 후 HOME 복귀(운행 스택 전부 제거).
// 운행 완료 후 요금 입력은 필수 단계 — 뒤로가기 차단.

/** 호출료 — 더미 리포지토리 기준값. 실서버 연동 시 FareResult(서버 계산)로 대체한다. */
private const val CALL_FEE = 3_000

/** 미터기 최대 입력 6자리 (999,990원) · 10원 단위 */
private const val MAX_METER_FARE = 999_990

class FareViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val trip: ActiveTrip,
            val submitting: Boolean = false,
            val submitError: String? = null,
            val result: FareResult? = null,
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

    fun submit(meterFare: Int) {
        val current = _uiState.value as? UiState.Success ?: return
        if (current.submitting || meterFare <= 0) return
        viewModelScope.launch {
            _uiState.update { (it as UiState.Success).copy(submitting = true, submitError = null) }
            try {
                val result = repository.submitFinalFare(current.trip.id, meterFare)
                _uiState.update { (it as UiState.Success).copy(submitting = false, result = result) }
            } catch (e: Exception) {
                // 네트워크 오류 시 입력값 보존 + 재시도 (공통 규칙)
                _uiState.update {
                    (it as UiState.Success).copy(
                        submitting = false,
                        submitError = "요금을 확정하지 못했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { FareViewModel(repository) }
        }
    }
}

@Composable
fun FareRoute(navController: NavHostController, repository: DriverRepository) {
    val viewModel: FareViewModel = viewModel(factory = FareViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()

    var meterFare by rememberSaveable { mutableIntStateOf(0) }
    var keypadOpen by rememberSaveable { mutableStateOf(false) }

    // 키패드가 열려 있으면 닫기, 아니면 뒤로가기 차단 (요금 입력은 필수 단계)
    BackHandler {
        if (keypadOpen) keypadOpen = false
    }

    // 확정 완료 → HOME 복귀 (popUpTo HOME inclusive=false — 운행 스택 전부 제거)
    val done = (state as? FareViewModel.UiState.Success)?.result != null
    LaunchedEffect(done) {
        if (done) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    when (val s = state) {
        is FareViewModel.UiState.Loading -> LoadingBox()
        is FareViewModel.UiState.Error -> ErrorBox(message = s.message, onRetry = viewModel::refresh)
        is FareViewModel.UiState.Success -> {
            if (keypadOpen) {
                // D16b · 미터기 금액 입력 키패드 (D16 내부 전환 뷰)
                FareKeypadScreen(
                    initialAmount = meterFare,
                    onConfirm = { amount ->
                        meterFare = amount
                        keypadOpen = false
                    },
                    onCancel = { keypadOpen = false },
                )
            } else {
                FareScreen(
                    trip = s.trip,
                    meterFare = meterFare,
                    submitting = s.submitting,
                    submitError = s.submitError,
                    onOpenKeypad = { keypadOpen = true },
                    onSubmit = { viewModel.submit(meterFare) },
                )
            }
        }
    }
}

// ── D16 본 화면 ─────────────────────────────────────────────────────────

@Composable
private fun FareScreen(
    trip: ActiveTrip,
    meterFare: Int,
    submitting: Boolean,
    submitError: String?,
    onOpenKeypad: () -> Unit,
    onSubmit: () -> Unit,
) {
    val entered = meterFare > 0
    val serviceFee = (meterFare + CALL_FEE) * 5 / 100 / 10 * 10
    val passengerTotal = meterFare + CALL_FEE
    val driverPayout = meterFare + CALL_FEE - serviceFee + trip.poolBonus

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "운행 완료")

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "미터기 요금을 입력해 주세요",
                style = MoyeotaType.DisplayMd,
                color = MoyeotaColor.InkPrimary,
            )

            // 금액 필드 — 탭하면 D16b 키패드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.SurfaceCard)
                    .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenKeypad)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "미터기 요금", style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (entered) "${formatWon(meterFare)}원" else "탭해서 입력",
                    style = MoyeotaType.NumberMd,
                    color = if (entered) MoyeotaColor.InkPrimary else MoyeotaColor.TextAsh,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", style = MoyeotaType.HeadingLg, color = MoyeotaColor.TextMute)
            }

            if (entered) {
                // 승객 청구 총액 (미터기 + 호출료)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MoyeotaColor.SurfaceSoft, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "승객 청구 총액",
                        style = MoyeotaType.HeadingLg,
                        color = MoyeotaColor.TextBody,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "${formatWon(passengerTotal)}원", style = MoyeotaType.NumberMd, color = MoyeotaColor.InkPrimary)
                }

                // 요금 구성: 미터기 + 호출료 − 수수료 5% + 보너스
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
                        .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                        .padding(vertical = 4.dp),
                ) {
                    FareRow(title = "미터기 요금", sub = "기사 입력값", amount = "${formatWon(meterFare)}원")
                    FareRow(title = "호출료", sub = "콜 이용 요금", amount = "${formatWon(CALL_FEE)}원")
                    if (trip.type == CallType.POOL && trip.poolBonus > 0) {
                        FareRow(title = "합승 보너스", sub = "8월 프로모션", amount = "+${formatWon(trip.poolBonus)}원")
                    }
                    FareRow(title = "서비스 수수료", sub = "청구액의 5%", amount = "-${formatWon(serviceFee)}원")
                    FareRow(title = "기사 정산액", sub = "수수료 차감 후", amount = "${formatWon(driverPayout)}원", emphasize = true)
                }
            }

            if (trip.type == CallType.POOL && trip.poolBonus > 0) {
                NoticeBanner(
                    kind = NoticeKind.INFO,
                    text = "합승 보너스는 이번 주 정산에 자동 반영돼요",
                )
            }
            if (submitError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = submitError)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
        ) {
            PrimaryCtaButton(
                text = "요금 확정하기",
                onClick = onSubmit,
                enabled = entered,
                loading = submitting,
                modifier = Modifier.height(60.dp),
            )
        }
    }
}

@Composable
private fun FareRow(title: String, sub: String, amount: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MoyeotaType.BodyLg,
                color = if (emphasize) MoyeotaColor.Primary500 else MoyeotaColor.InkPrimary,
            )
            Text(text = sub, style = MoyeotaType.BodyMd, color = MoyeotaColor.TextMute)
        }
        Text(
            text = amount,
            style = if (emphasize) MoyeotaType.NumberMd else MoyeotaType.HeadingLg,
            color = if (emphasize) MoyeotaColor.Primary500 else MoyeotaColor.InkPrimary,
        )
    }
}

// ── D16b · 미터기 금액 입력 키패드 ──────────────────────────────────────

@Composable
private fun FareKeypadScreen(
    initialAmount: Int,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var amount by rememberSaveable(initialAmount) { mutableIntStateOf(initialAmount) }

    fun appendDigit(digit: Int) {
        val next = amount * 10 + digit
        if (next <= MAX_METER_FARE) amount = next
        // 초과 입력은 무시 (스펙: 진동 피드백은 미구현 항목)
    }

    val confirmable = amount > 0 && amount % 10 == 0

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "미터기 요금 입력", onBack = onCancel)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 입력 금액 표시 — 핵심 숫자 NumberXl(34sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.SurfaceSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = "미터기에 표시된 금액", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextMute)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = formatWon(amount), style = MoyeotaType.NumberXl, color = MoyeotaColor.InkPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "원",
                        style = MoyeotaType.NumberMd,
                        color = MoyeotaColor.TextMute,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    text = if (amount > 0 && amount % 10 != 0) "10원 단위로만 입력할 수 있어요" else "10원 단위 입력 · 최대 999,990원",
                    style = MoyeotaType.BodyMd,
                    color = if (amount > 0 && amount % 10 != 0) MoyeotaColor.Danger500 else MoyeotaColor.TextBody,
                )
            }

            // 빠른 가산 — 미터기 요금은 대부분 백 원 단위
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickKey(label = "+1,000", modifier = Modifier.weight(1f)) {
                    if (amount + 1_000 <= MAX_METER_FARE) amount += 1_000
                }
                QuickKey(label = "+500", modifier = Modifier.weight(1f)) {
                    if (amount + 500 <= MAX_METER_FARE) amount += 500
                }
                QuickKey(label = "+100", modifier = Modifier.weight(1f)) {
                    if (amount + 100 <= MAX_METER_FARE) amount += 100
                }
                QuickKey(label = "전체 지우기", modifier = Modifier.weight(1f)) { amount = 0 }
            }

            // 숫자 키패드 — 키 높이 74, 폭 3분할 (차 안 흔들림 기준 최소 터치 타깃)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("00", "0", "⌫"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            NumberKey(
                                label = key,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    when (key) {
                                        "⌫" -> amount /= 10
                                        "00" -> {
                                            appendDigit(0)
                                            appendDigit(0)
                                        }
                                        else -> appendDigit(key.toInt())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // CTA는 입력값을 그대로 보여줌
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
        ) {
            PrimaryCtaButton(
                text = if (confirmable) "${formatWon(amount)}원으로 확정" else "금액을 입력해 주세요",
                onClick = { onConfirm(amount) },
                enabled = confirmable,
                modifier = Modifier.height(60.dp),
            )
        }
    }
}

@Composable
private fun QuickKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MoyeotaColor.SurfaceCard)
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MoyeotaType.HeadingMd, color = MoyeotaColor.TextBody)
    }
}

@Composable
private fun NumberKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoyeotaColor.SurfaceSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MoyeotaType.NumberXl,
            color = if (label == "⌫") MoyeotaColor.TextMute else MoyeotaColor.InkPrimary,
        )
    }
}
