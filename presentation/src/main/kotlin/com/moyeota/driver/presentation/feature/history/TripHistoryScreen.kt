package com.moyeota.driver.presentation.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.MoyeotaChip
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.TripHistoryItem
import com.moyeota.driver.domain.model.TripHistoryStatus
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── D20 운행 이력 목록 — 피그마 2522:907 ──────────────────────────────────
// 지난 운행을 유형별로 확인. 하단탭 노출 화면(마이 탭 소속).

enum class HistoryFilter(val label: String) {
    ALL("전체"), POOL("합승"), SOLO("단독"), CANCELED("취소");

    fun matches(item: TripHistoryItem): Boolean = when (this) {
        ALL -> true
        POOL -> item.status == TripHistoryStatus.COMPLETED && item.type == CallType.POOL
        SOLO -> item.status == TripHistoryStatus.COMPLETED && item.type == CallType.SOLO
        CANCELED -> item.status == TripHistoryStatus.CANCELED
    }
}

class TripHistoryViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val trips: List<TripHistoryItem>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getTripHistory())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("운행 이력을 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { TripHistoryViewModel(repository) }
        }
    }
}

@Composable
fun TripHistoryRoute(
    onTabSelect: (MoyeotaTab) -> Unit,
    onTripClick: (tripId: String) -> Unit,
    repository: DriverRepository,
) {
    val viewModel: TripHistoryViewModel = viewModel(factory = TripHistoryViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    TabStateScaffold(selectedTab = MoyeotaTab.MYPAGE, onTabSelect = onTabSelect) {
        when (val state = uiState) {
            is TripHistoryViewModel.UiState.Loading -> LoadingBox()
            is TripHistoryViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is TripHistoryViewModel.UiState.Success -> TripHistoryScreen(
                trips = state.trips,
                onTripClick = onTripClick,
            )
        }
    }
}

@Composable
fun TripHistoryScreen(
    trips: List<TripHistoryItem>,
    onTripClick: (tripId: String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val filtered = trips.filter(filter::matches)

    Column(modifier = Modifier.fillMaxSize()) {
        MoyeotaTopBar(title = "운행 이력")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HistoryFilter.entries.forEach { candidate ->
                MoyeotaChip(
                    text = candidate.label,
                    selected = candidate == filter,
                    onClick = { filter = candidate },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = "조건에 맞는 운행 이력이 없어요",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextMute,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(filtered, key = { it.id }) { item ->
                TripHistoryRow(item = item, onClick = { onTripClick(item.id) })
            }
            item {
                NoticeBanner(kind = NoticeKind.INFO, text = "운행 이력은 12개월까지 조회할 수 있어요")
            }
        }
    }
}

// 운행 한 건 — 일자 · 유형 배지 · 경로 · 요금 · 정산액. 취소 건은 회색 처리.
@Composable
private fun TripHistoryRow(item: TripHistoryItem, onClick: () -> Unit) {
    val canceled = item.status == TripHistoryStatus.CANCELED
    val titleColor = if (canceled) MoyeotaColor.TextMute else MoyeotaColor.InkPrimary
    val amountColor = if (canceled) MoyeotaColor.TextAsh else MoyeotaColor.InkPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    canceled -> StatusBadge(kind = NoticeKind.ERROR, text = "취소")
                    item.type == CallType.POOL -> StatusBadge(kind = NoticeKind.INFO, text = "합승")
                    else -> StatusBadge(kind = NoticeKind.SUCCESS, text = "단독")
                }
                Text(text = item.dateLabel, style = MoyeotaType.BodyMd, color = MoyeotaColor.TextMute)
            }
            Text(text = item.routeLabel, style = MoyeotaType.HeadingLg, color = titleColor)
            Text(
                text = if (canceled) "취소된 운행 · 위약금만 정산" else "요금 ${formatWon(item.fare)}",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = formatWon(item.payout), style = MoyeotaType.NumberMd, color = amountColor)
            Text(text = "정산액", style = MoyeotaType.BodySm, color = MoyeotaColor.TextMute)
        }
    }
}

internal fun formatWon(value: Int): String = "%,d원".format(value)
