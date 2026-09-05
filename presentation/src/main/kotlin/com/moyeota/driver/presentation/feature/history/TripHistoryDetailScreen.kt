package com.moyeota.driver.presentation.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.RouteStop
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripHistoryDetail
import com.moyeota.driver.domain.model.TripHistoryStatus
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.BackStateScaffold
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── D21 운행 이력 상세 — 피그마 2522:974 ──────────────────────────────────
// 운행 1건의 경로·요금·정산 결과를 한 화면에서 확인. BackStateScaffold(탭 없음).

class TripHistoryDetailViewModel(
    private val repository: DriverRepository,
    private val tripId: String,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val detail: TripHistoryDetail) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getTripDetail(tripId))
            } catch (e: Exception) {
                _uiState.value = UiState.Error("운행 상세를 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository, tripId: String) = viewModelFactory {
            initializer { TripHistoryDetailViewModel(repository, tripId) }
        }
    }
}

@Composable
fun TripHistoryDetailRoute(
    tripId: String,
    onBack: () -> Unit,
    repository: DriverRepository,
) {
    val viewModel: TripHistoryDetailViewModel =
        viewModel(key = "trip-detail-$tripId", factory = TripHistoryDetailViewModel.factory(repository, tripId))
    val uiState by viewModel.uiState.collectAsState()

    BackStateScaffold(title = "운행 상세", onBack = onBack) {
        when (val state = uiState) {
            is TripHistoryDetailViewModel.UiState.Loading -> LoadingBox()
            is TripHistoryDetailViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is TripHistoryDetailViewModel.UiState.Success -> TripHistoryDetailScreen(detail = state.detail)
        }
    }
}

@Composable
fun TripHistoryDetailScreen(detail: TripHistoryDetail) {
    val canceled = detail.item.status == TripHistoryStatus.CANCELED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TripSummaryCard(detail = detail)

        if (canceled) {
            // 취소·노쇼 건은 위약금·사유만 표시하고 요금 구성은 숨김
            NoticeBanner(
                kind = NoticeKind.ERROR,
                text = "취소된 운행이에요. 위약금 ${formatWon(detail.item.payout)}만 정산됩니다",
            )
        } else {
            StopTimelineCard(stops = detail.stops)
            FareBreakdownCard(detail = detail)
        }

        // 영수증 상세는 미연결 — 비활성 표기
        SecondaryButton(text = "영수증 보기", onClick = {}, enabled = false)
        Text(
            text = "영수증 상세는 준비 중이에요",
            style = MoyeotaType.BodyMd,
            color = MoyeotaColor.TextAsh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// 상단 요약 — 일시 · 유형 · 인원 · 운행 시간 · 거리
@Composable
private fun TripSummaryCard(detail: TripHistoryDetail) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                detail.item.status == TripHistoryStatus.CANCELED ->
                    StatusBadge(kind = NoticeKind.ERROR, text = "취소")
                detail.item.type == CallType.POOL ->
                    StatusBadge(kind = NoticeKind.INFO, text = "합승 ${detail.passengerCount}인")
                else -> StatusBadge(kind = NoticeKind.SUCCESS, text = "단독")
            }
            Text(text = detail.item.dateLabel, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
        }
        Text(text = detail.item.routeLabel, style = MoyeotaType.HeadingXl, color = MoyeotaColor.InkDeep)
        Text(
            text = "운행 ${detail.durationMin}분 · ${detail.distanceKm}km · 운행 번호 ${detail.item.id}",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )
    }
}

// 경유 정차 타임라인 — 탑승(초록) · 하차(주황) 점 + 세로 연결선
@Composable
private fun StopTimelineCard(stops: List<RouteStop>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "경유 정차",
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.InkPrimary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        stops.sortedBy { it.order }.forEachIndexed { index, stop ->
            StopRow(stop = stop, isLast = index == stops.lastIndex)
        }
    }
}

@Composable
private fun StopRow(stop: RouteStop, isLast: Boolean) {
    val dotColor = if (stop.kind == StopKind.PICKUP) MoyeotaColor.MarkerPickup else MoyeotaColor.MarkerDropoff
    val kindLabel = if (stop.kind == StopKind.PICKUP) "탑승" else "하차"
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(11.dp)
                    .background(dotColor, CircleShape),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(44.dp)
                        .background(MoyeotaColor.Hairline),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, bottom = if (isLast) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = stop.place, style = MoyeotaType.BodyLg, color = MoyeotaColor.InkPrimary)
            Text(
                text = "${stop.passengerMaskedName} $kindLabel",
                style = MoyeotaType.BodyMd,
                color = MoyeotaColor.TextMute,
            )
        }
    }
}

// 요금 구성 — 미터기 + 호출료 − 수수료 + 보너스 = 내 정산액
@Composable
private fun FareBreakdownCard(detail: TripHistoryDetail) {
    val fare = detail.fareResult
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "요금 구성", style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
        FareLine(label = "미터기 요금", sub = "기사 입력값", amount = formatWon(fare.meterFare))
        FareLine(label = "호출료", sub = null, amount = "+${formatWon(fare.callFee)}")
        FareLine(
            label = "서비스 수수료",
            sub = null,
            amount = "−${formatWon(fare.serviceFee)}",
            amountColor = MoyeotaColor.Danger500,
        )
        if (fare.poolBonus > 0) {
            FareLine(
                label = "합승 보너스",
                sub = "주간 정산 합산 지급",
                amount = "+${formatWon(fare.poolBonus)}",
                amountColor = MoyeotaColor.Success500,
            )
        }
        HorizontalDivider(color = MoyeotaColor.Hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "내 정산액", style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
            Text(text = formatWon(fare.driverPayout), style = MoyeotaType.NumberLg, color = MoyeotaColor.InkDeep)
        }
    }
}

@Composable
private fun FareLine(
    label: String,
    sub: String?,
    amount: String,
    amountColor: androidx.compose.ui.graphics.Color = MoyeotaColor.InkPrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MoyeotaType.BodyLg, color = MoyeotaColor.TextBody)
            if (sub != null) {
                Text(text = sub, style = MoyeotaType.BodySm, color = MoyeotaColor.TextMute)
            }
        }
        Text(text = amount, style = MoyeotaType.NumberMd, color = amountColor)
    }
}
