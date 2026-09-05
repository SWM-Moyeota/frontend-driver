package com.moyeota.driver.presentation.feature.settlement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.SettlementDay
import com.moyeota.driver.domain.model.SettlementSummary
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── 공용 포맷터 (settlement 패키지 내부 전용) ─────────────────────────────

internal fun formatWon(amount: Int): String = "%,d원".format(amount)

// ─── D17 결제 정산 내역 — ViewModel ─────────────────────────────────────

class SettlementViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val summary: SettlementSummary) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val detail = repository.getSettlementDetail()
                _uiState.value = UiState.Success(detail.summary)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("정산 내역을 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { SettlementViewModel(repository) }
        }
    }
}

// ─── D17 Route ──────────────────────────────────────────────────────────

@Composable
fun SettlementRoute(
    repository: DriverRepository,
    onTabSelect: (MoyeotaTab) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val viewModel: SettlementViewModel = viewModel(factory = SettlementViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    TabStateScaffold(selectedTab = MoyeotaTab.SETTLEMENT, onTabSelect = onTabSelect) {
        when (val state = uiState) {
            is SettlementViewModel.UiState.Loading -> LoadingBox()
            is SettlementViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is SettlementViewModel.UiState.Success -> SettlementScreen(
                summary = state.summary,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

// ─── D17 Screen (스테이트리스) ──────────────────────────────────────────

@Composable
fun SettlementScreen(
    summary: SettlementSummary,
    onOpenDetail: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 탭 최상위 화면 — title-only 상단 바 (뒤로가기 없음)
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = "정산", style = MoyeotaType.HeadingXl, color = MoyeotaColor.InkPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PeriodFilterRow()
            PayoutCard(summary = summary)
            summary.days.forEach { day ->
                SettlementDayRow(day = day, onClick = onOpenDetail)
            }
            WeeklyDetailRow(onClick = onOpenDetail)
        }
    }
}

// 기간 필터 — 기간 선택 시트 미연결이라 「이번 주」 외 칩은 비활성 표기
@Composable
private fun PeriodFilterRow() {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PeriodChip(text = "이번 주", active = true)
        PeriodChip(text = "이번 달", active = false)
        PeriodChip(text = "지난달", active = false)
        PeriodChip(text = "기간 선택", active = false)
    }
}

// 미연결 칩은 탭 불가 + TextAsh 로 비활성 표기
@Composable
private fun PeriodChip(text: String, active: Boolean) {
    val bg = if (active) MoyeotaColor.Primary600 else MoyeotaColor.SurfaceCard
    val fg = if (active) MoyeotaColor.TextOnDark else MoyeotaColor.TextAsh
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MoyeotaType.BodyLg, fontWeight = FontWeight.Bold, color = fg)
    }
}

// 입금 예정 카드 — 총 정산액은 NumberXl (핵심 숫자 24~34)
@Composable
private fun PayoutCard(summary: SettlementSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.Primary600)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "입금 예정 · ${summary.weekLabel}",
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.TextOnDark,
        )
        Text(
            text = formatWon(summary.totalPayout),
            style = MoyeotaType.NumberXl,
            color = MoyeotaColor.TextOnDark,
        )
        Text(
            text = "${summary.payoutDateLabel} · ${summary.bankAccountLabel}",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextOnDark,
        )
        // 계좌 변경 미연결 — 비활성 표기 (탭 불가)
        Text(
            text = "계좌 변경 (준비 중)",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextOnDark.copy(alpha = 0.55f),
        )
    }
}

// 일자별 행 — 탭하면 주간 정산 상세(D18)로 이동
@Composable
private fun SettlementDayRow(day: SettlementDay, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoyeotaColor.SurfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = day.dateLabel,
                style = MoyeotaType.BodyLg,
                fontWeight = FontWeight.Bold,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = "운행 ${day.tripCount}건",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
        Text(
            text = formatWon(day.payout),
            style = MoyeotaType.NumberMd,
            color = MoyeotaColor.InkPrimary,
        )
    }
}

// 주간 정산 상세 보기 nav 행 → D18
@Composable
private fun WeeklyDetailRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoyeotaColor.SurfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "주간 정산 상세 보기",
                style = MoyeotaType.BodyLg,
                fontWeight = FontWeight.Bold,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = "수수료 · 차감 내역 포함",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
        Text(
            text = "›",
            style = MoyeotaType.NumberMd,
            color = MoyeotaColor.TextMute,
        )
    }
}
