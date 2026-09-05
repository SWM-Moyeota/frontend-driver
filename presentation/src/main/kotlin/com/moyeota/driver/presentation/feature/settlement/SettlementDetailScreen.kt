package com.moyeota.driver.presentation.feature.settlement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.SettlementDetail
import com.moyeota.driver.domain.model.SettlementTripRow
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.BackStateScaffold
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── D18 정산 상세 (건별 · 주간) — ViewModel ────────────────────────────

class SettlementDetailViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val detail: SettlementDetail) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getSettlementDetail())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("정산 상세를 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { SettlementDetailViewModel(repository) }
        }
    }
}

// ─── D18 Route ──────────────────────────────────────────────────────────

@Composable
fun SettlementDetailRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
) {
    val viewModel: SettlementDetailViewModel =
        viewModel(factory = SettlementDetailViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    BackStateScaffold(title = "정산 상세", onBack = onBack) {
        when (val state = uiState) {
            is SettlementDetailViewModel.UiState.Loading -> LoadingBox()
            is SettlementDetailViewModel.UiState.Error ->
                ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is SettlementDetailViewModel.UiState.Success ->
                SettlementDetailScreen(detail = state.detail)
        }
    }
}

// ─── D18 Screen (스테이트리스) ──────────────────────────────────────────

@Composable
fun SettlementDetailScreen(detail: SettlementDetail) {
    val summary = detail.summary
    // 기사 정산액 = 승객 청구 총액 − 서비스 수수료(5%) ± 보너스·차감
    // 차감액은 공식 잔차로 도출해 항목 합계가 항상 최종 지급액과 일치하게 한다.
    val deduction = summary.totalFare + summary.totalBonus - summary.totalServiceFee - summary.totalPayout

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 주간 헤더 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MoyeotaColor.SurfaceCard)
                .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = summary.weekLabel,
                style = MoyeotaType.HeadingXl,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = "${summary.payoutDateLabel} · ${summary.bankAccountLabel}",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }

        // 주간 요약 — 운행 수입 + 합승 보너스 − 수수료 − 차감 = 최종 지급액
        SummaryRow(
            title = "운행 수입",
            sub = "승객 청구 총액",
            amountText = formatWon(summary.totalFare),
            amountColor = MoyeotaColor.InkPrimary,
        )
        SummaryRow(
            title = "합승 보너스",
            sub = "합승 완주 건 적립",
            amountText = "+${formatWon(summary.totalBonus)}",
            amountColor = MoyeotaColor.Success600,
        )
        SummaryRow(
            title = "서비스 수수료",
            sub = "청구액의 5%",
            amountText = "-${formatWon(summary.totalServiceFee)}",
            amountColor = MoyeotaColor.Danger500,
        )
        if (deduction != 0) {
            SummaryRow(
                title = "취소 · 노쇼 차감",
                sub = "차감 내역",
                amountText = "-${formatWon(deduction)}",
                amountColor = MoyeotaColor.Danger500,
            )
        }
        SummaryRow(
            title = "최종 지급액",
            sub = "세전 기준",
            amountText = formatWon(summary.totalPayout),
            amountColor = MoyeotaColor.InkPrimary,
            emphasized = true,
        )

        // 건별 목록
        Text(
            text = "건별 내역",
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.InkPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        detail.trips.forEach { trip ->
            SettlementTripRowItem(trip = trip)
        }

        // 명세서 PDF 미연결 — 비활성 표기
        SecondaryButton(
            text = "명세서 내려받기",
            onClick = {},
            enabled = false,
        )
        Text(
            text = "명세서 PDF는 준비 중입니다",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextAsh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// 주간 요약 한 줄 — 제목/보조설명 + 우측 금액
@Composable
private fun SummaryRow(
    title: String,
    sub: String,
    amountText: String,
    amountColor: Color,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoyeotaColor.SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MoyeotaType.BodyLg,
                fontWeight = FontWeight.Bold,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = sub,
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
        Text(
            text = amountText,
            style = if (emphasized) MoyeotaType.NumberLg else MoyeotaType.NumberMd,
            color = amountColor,
        )
    }
}

// 건별 행 — 합승 건은 보너스 병기
@Composable
private fun SettlementTripRowItem(trip: SettlementTripRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoyeotaColor.SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (trip.type == CallType.POOL) {
                    StatusBadge(kind = NoticeKind.INFO, text = "합승")
                }
                Text(
                    text = trip.routeLabel,
                    style = MoyeotaType.BodyLg,
                    fontWeight = FontWeight.Bold,
                    color = MoyeotaColor.InkPrimary,
                )
            }
            Text(
                text = if (trip.type == CallType.POOL) {
                    "${trip.timeLabel} · 요금 ${formatWon(trip.fare)} · 보너스 +${formatWon(trip.bonus)}"
                } else {
                    "${trip.timeLabel} · 요금 ${formatWon(trip.fare)}"
                },
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
        Text(
            text = formatWon(trip.payout),
            style = MoyeotaType.NumberMd,
            color = MoyeotaColor.InkPrimary,
        )
    }
}
