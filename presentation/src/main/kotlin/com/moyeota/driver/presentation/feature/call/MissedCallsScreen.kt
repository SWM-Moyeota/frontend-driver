package com.moyeota.driver.presentation.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.moyeota.driver.domain.model.MissedCall
import com.moyeota.driver.domain.model.MissedReason
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── D12 놓친 콜 · 거절 이력 ─────────────────────────────────────────────

class MissedCallsViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val calls: List<MissedCall>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getMissedCalls())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("놓친 콜 이력을 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { MissedCallsViewModel(repository) }
        }
    }
}

private enum class MissedFilter(val label: String) {
    ALL("전체"), TIMEOUT_ONLY("미응답만"), DECLINED_ONLY("거절만")
}

@Composable
fun MissedCallsRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onTabSelect: (MoyeotaTab) -> Unit,
) {
    val viewModel: MissedCallsViewModel = viewModel(factory = MissedCallsViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    TabStateScaffold(selectedTab = MoyeotaTab.CALLS, onTabSelect = onTabSelect) {
        Column(Modifier.fillMaxSize()) {
            MoyeotaTopBar(title = "놓친 콜", onBack = onBack)
            when (val state = uiState) {
                is MissedCallsViewModel.UiState.Loading -> LoadingBox()
                is MissedCallsViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
                is MissedCallsViewModel.UiState.Success -> MissedCallsScreen(calls = state.calls)
            }
        }
    }
}

@Composable
private fun MissedCallsScreen(calls: List<MissedCall>) {
    var filter by rememberSaveable { mutableStateOf(MissedFilter.ALL) }
    val filtered = when (filter) {
        MissedFilter.ALL -> calls
        MissedFilter.TIMEOUT_ONLY -> calls.filter { it.reason == MissedReason.TIMEOUT }
        MissedFilter.DECLINED_ONLY -> calls.filter { it.reason == MissedReason.DECLINED }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MissedFilter.entries.forEach { f ->
                    MoyeotaChip(text = f.label, selected = filter == f, onClick = { filter = f })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "놓친 콜이 없어요",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextMute,
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { call ->
                MissedCallRow(call)
            }
        }
        item {
            // 배정 축소 경고 — 축소 상태 판단은 서버 정책 TBD라 상시 안내 문구로 노출
            NoticeBanner(
                kind = NoticeKind.WAITING,
                text = "⚠ 연속 3회 미응답이면 콜 배정이 줄어들 수 있어요",
            )
        }
    }
}

// 재수락 불가, 읽기 전용 행 (콜 요약 상세는 스펙 미연결 항목)
@Composable
private fun MissedCallRow(call: MissedCall) {
    val (badgeKind, badgeText, reasonLabel) = when (call.reason) {
        MissedReason.TIMEOUT -> Triple(NoticeKind.ERROR, "미응답", "15초 초과")
        MissedReason.DECLINED -> Triple(NoticeKind.WAITING, "거절", "직접 거절")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceCard)
            .heightIn(min = 84.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${call.pickupPlace} → ${call.dropoffPlace}",
                style = MoyeotaType.HeadingLg,
                color = MoyeotaColor.InkPrimary,
            )
            Text(
                text = "${call.missedAtLabel} · $reasonLabel · 예상 ${won(call.expectedTotal)}",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
        }
        StatusBadge(kind = badgeKind, text = badgeText)
    }
}
