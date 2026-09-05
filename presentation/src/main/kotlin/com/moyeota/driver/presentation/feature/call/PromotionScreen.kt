package com.moyeota.driver.presentation.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.Promotion
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.BackStateScaffold
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── D22 합승 프로모션 안내 ──────────────────────────────────────────────

class PromotionViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val promotion: Promotion) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getPromotion())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("프로모션 정보를 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { PromotionViewModel(repository) }
        }
    }
}

@Composable
fun PromotionRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onGoCalls: () -> Unit,
    onAccruedClick: () -> Unit,
) {
    val viewModel: PromotionViewModel = viewModel(factory = PromotionViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    BackStateScaffold(title = "합승 프로모션", onBack = onBack) {
        when (val state = uiState) {
            is PromotionViewModel.UiState.Loading -> LoadingBox()
            is PromotionViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is PromotionViewModel.UiState.Success -> PromotionScreen(
                promotion = state.promotion,
                onGoCalls = onGoCalls,
                onAccruedClick = onAccruedClick,
            )
        }
    }
}

@Composable
private fun PromotionScreen(
    promotion: Promotion,
    onGoCalls: () -> Unit,
    onAccruedClick: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 히어로 카드 — 금액 · 기간은 서버 값 단일 소스 (D09 · D10과 동일 값)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.Primary500)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = promotion.title,
                    style = MoyeotaType.HeadingXl,
                    color = MoyeotaColor.TextOnDark,
                )
                Text(
                    text = "건당 +${won(promotion.bonusPerPool)}",
                    style = MoyeotaType.NumberXl,
                    color = MoyeotaColor.TextOnDark,
                )
                Text(
                    text = "${promotion.periodLabel} · 수락하면 자동 적용",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextOnDark,
                )
            }

            // 지급 조건 — 서버가 내려주는 조건 문구 전체 노출
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.SurfaceCard)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "지급 조건",
                    style = MoyeotaType.HeadingLg,
                    color = MoyeotaColor.InkPrimary,
                )
                promotion.conditions.forEach { condition ->
                    Text(
                        text = "· $condition",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextBody,
                    )
                }
            }

            // 이번 주 적립 현황 — 탭 시 정산 내역(D17)으로. 주간 정산 합산 지급 명시
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.SurfaceCard)
                    .clickable { onAccruedClick() }
                    .heightIn(min = 84.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "이번 주 적립",
                        style = MoyeotaType.HeadingLg,
                        color = MoyeotaColor.InkPrimary,
                    )
                    Text(
                        text = "합승 ${promotion.accruedCount}건 달성 · 주간 정산에 합산 지급",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextBody,
                    )
                }
                Text(
                    text = won(promotion.accruedAmount),
                    style = MoyeotaType.NumberMd,
                    color = MoyeotaColor.InkPrimary,
                )
                Text(
                    text = "›",
                    style = MoyeotaType.HeadingXl,
                    color = MoyeotaColor.TextMute,
                )
            }

            NoticeBanner(
                kind = NoticeKind.WAITING,
                text = "⚠ 노쇼 · 취소 건은 보너스가 지급되지 않아요",
            )

            Spacer(Modifier.heightIn(min = 4.dp))
        }

        // 「합승 콜 받기 켜기」 토글은 Repository에 설정 메서드가 없어 「콜 보러 가기」로 대체 (스펙: 이미 on이면 이 CTA)
        PrimaryCtaButton(
            text = "콜 보러 가기",
            onClick = onGoCalls,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}
