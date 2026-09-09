package com.moyeota.driver.presentation.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
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
import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.Promotion
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── D09 콜 리스트 ───────────────────────────────────────────────────────

class CallListViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val calls: List<CallSummary>, val promotion: Promotion?) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val calls = repository.getCalls()
                // 프로모션 배너 금액은 서버 값 단일 소스 — 실패해도 목록은 보여준다
                val promotion = runCatching { repository.getPromotion() }.getOrNull()
                _uiState.value = UiState.Success(calls, promotion)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("콜 목록을 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { CallListViewModel(repository) }
        }
    }
}

private enum class CallFilter(val label: String) {
    ALL("전체"), POOL_ONLY("합승만"), NEAR("1km 이내")
}

@Composable
fun CallListRoute(
    repository: DriverRepository,
    onCallClick: (String) -> Unit,
    onMissedClick: () -> Unit,
    onPromotionClick: () -> Unit,
    onTabSelect: (MoyeotaTab) -> Unit,
) {
    val viewModel: CallListViewModel = viewModel(factory = CallListViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    TabStateScaffold(selectedTab = MoyeotaTab.CALLS, onTabSelect = onTabSelect) {
        Column(Modifier.fillMaxSize()) {
            MoyeotaTopBar(
                title = "합승 콜",
                actions = {
                    // 놓친 콜 진입점 (D12)
                    Text(
                        text = "놓친 콜",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.Link,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onMissedClick() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                },
            )
            when (val state = uiState) {
                is CallListViewModel.UiState.Loading -> LoadingBox()
                is CallListViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
                is CallListViewModel.UiState.Success -> CallListScreen(
                    calls = state.calls,
                    promotion = state.promotion,
                    onCallClick = onCallClick,
                    onPromotionClick = onPromotionClick,
                )
            }
        }
    }
}

@Composable
private fun CallListScreen(
    calls: List<CallSummary>,
    promotion: Promotion?,
    onCallClick: (String) -> Unit,
    onPromotionClick: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(CallFilter.ALL) }
    val filtered = when (filter) {
        CallFilter.ALL -> calls
        CallFilter.POOL_ONLY -> calls.filter { it.type == CallType.POOL }
        // 거리 미상(푸시 임시 요약, 0.0)도 남긴다 — 방금 들어온 실콜을 필터가 삼키면 콜을 놓친다.
        // 상세가 도착하면 실거리로 다시 판정된다.
        CallFilter.NEAR -> calls.filter { it.distanceToPickupKm <= 1.0 }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CallFilter.entries.forEach { f ->
                    MoyeotaChip(text = f.label, selected = filter == f, onClick = { filter = f })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "지금 받을 수 있는 콜이 없어요",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextMute,
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { call ->
                CallCard(call = call, onClick = { onCallClick(call.id) })
            }
        }
        if (promotion != null) {
            item {
                // 프로모션 배너 — 탭 시 D22 합승 프로모션 안내
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPromotionClick() },
                ) {
                    NoticeBanner(
                        kind = NoticeKind.INFO,
                        text = "🎁 합승 콜 수락 시 1건당 보너스 ${won(promotion.bonusPerPool)} · 자세히 보기",
                    )
                }
            }
        }
    }
}

// 합승 카드는 보너스를 병기해 단독 콜과의 금액 비교가 카드 안에서 끝나게 한다 (피그마 고지 규칙)
@Composable
private fun CallCard(call: CallSummary, onClick: () -> Unit) {
    val isPool = call.type == CallType.POOL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceCard)
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .heightIn(min = 60.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${call.pickupPlace} → ${call.dropoffPlace}",
                style = MoyeotaType.HeadingXl,
                color = MoyeotaColor.InkPrimary,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            when {
                // 인원은 푸시 임시 요약에서 비어 있을 수 있다 — 모르면 "합승"까지만 말한다
                isPool && call.hasPassengerCount ->
                    StatusBadge(kind = NoticeKind.SUCCESS, text = "합승 ${call.passengerCount}인")
                isPool -> StatusBadge(kind = NoticeKind.SUCCESS, text = "합승")
                else -> StatusBadge(kind = NoticeKind.WAITING, text = "단독")
            }
        }
        Text(
            // 거리·보너스는 서버 상세가 채우는 값 — 푸시만 받은 콜에서는 "픽업 0.0km · 보너스 +0원" 같은
            // 없는 정보를 지어내지 않고 생략한다(상세가 도착하면 같은 카드가 실값으로 다시 그려진다).
            text = buildString {
                if (call.distanceToPickupKm > 0.0) append("픽업 ${km(call.distanceToPickupKm)} · ")
                if (isPool && call.poolBonus > 0) append("보너스 +${won(call.poolBonus)} · ")
                append(call.createdAtLabel)
            },
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "예상 수익 ",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.Link,
            )
            Text(
                text = if (call.hasFareEstimate) won(call.expectedTotal) else "확인 중",
                style = MoyeotaType.NumberMd,
                color = MoyeotaColor.Link,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "자세히 ›",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
    }
}
