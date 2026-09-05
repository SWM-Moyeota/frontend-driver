package com.moyeota.driver.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.HomeSummary
import com.moyeota.driver.domain.model.TripPhase
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.BackStateScaffold
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D08 · 영업 종료 · 휴무 전환. 피그마 2514:607 — 종료 전 오늘 실적 확인 + 휴무 전환 확정.

class OffDutyConfirmViewModel(private val repository: DriverRepository) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState

        data class Success(
            val summary: HomeSummary,
            val hasActiveTrip: Boolean,       // 배차~요금 입력 진행 중이면 종료 차단
            val submitting: Boolean = false,  // 종료 제출 중 (중복 제출 차단)
            val submitError: String? = null,  // 화면 단위 오류 — CTA 위 error 배너
        ) : UiState

        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val summary = repository.getHomeSummary()
                val activeTrip = repository.getActiveTrip()
                _uiState.value = UiState.Success(
                    summary = summary,
                    hasActiveTrip = activeTrip != null && activeTrip.phase != TripPhase.COMPLETED,
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("오늘 실적을 불러오지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    // 「영업 종료」 확정 — 성공 시 HOME(휴무)으로 복귀
    fun endDuty(onSuccess: () -> Unit) {
        val current = _uiState.value
        if (current !is UiState.Success || current.submitting || current.hasActiveTrip) return
        _uiState.update { (it as UiState.Success).copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                repository.setDutyStatus(online = false)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    (it as UiState.Success).copy(
                        submitting = false,
                        submitError = "영업을 종료하지 못했어요. 다시 시도해 주세요.",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { OffDutyConfirmViewModel(repository) }
        }
    }
}

@Composable
fun OffDutyConfirmRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onDutyEnded: () -> Unit,
    onNavigateSettlement: () -> Unit,
) {
    val viewModel: OffDutyConfirmViewModel = viewModel(factory = OffDutyConfirmViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    BackStateScaffold(title = "영업 종료", onBack = onBack) {
        when (val state = uiState) {
            is OffDutyConfirmViewModel.UiState.Loading -> LoadingBox()
            is OffDutyConfirmViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is OffDutyConfirmViewModel.UiState.Success -> OffDutyConfirmScreen(
                summary = state.summary,
                hasActiveTrip = state.hasActiveTrip,
                submitting = state.submitting,
                submitError = state.submitError,
                onConfirmEnd = { viewModel.endDuty(onSuccess = onDutyEnded) },
                onContinueDuty = onBack,
                onEarningsClick = onNavigateSettlement,
            )
        }
    }
}

@Composable
private fun OffDutyConfirmScreen(
    summary: HomeSummary,
    hasActiveTrip: Boolean,
    submitting: Boolean,
    submitError: String?,
    onConfirmEnd: () -> Unit,
    onContinueDuty: () -> Unit,
    onEarningsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "오늘 운행을 마칠까요?",
                style = MoyeotaType.DisplayXl,
                color = MoyeotaColor.InkPrimary,
            )

            // 오늘 수익 하이라이트 — 탭하면 정산 내역(D17)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MoyeotaColor.SurfaceSoft)
                    .clickable(onClick = onEarningsClick)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "오늘 수익",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextBody,
                )
                Text(
                    text = formatWon(summary.todayEarnings),
                    style = MoyeotaType.NumberLg,
                    color = MoyeotaColor.InkPrimary,
                )
            }

            SummaryRow(
                title = "운행 완료",
                subtitle = "오늘 완료한 운행",
                value = "${summary.todayTripCount}건",
            )
            SummaryRow(
                title = "온라인 시간",
                subtitle = "오늘 영업 시간",
                value = formatOnlineMinutes(summary.onlineMinutes),
            )

            if (hasActiveTrip) {
                NoticeBanner(
                    kind = NoticeKind.WAITING,
                    text = "⚠  진행 중인 콜이 있으면 종료할 수 없어요",
                )
            }
        }

        // 하단 dual CTA — 좌 보조(조금 더 운행) / 우 주 액션(영업 종료)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (submitError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = submitError)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (submitError != null) 12.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    text = "조금 더 운행",
                    onClick = onContinueDuty,
                    modifier = Modifier.weight(1f).height(60.dp),
                )
                PrimaryCtaButton(
                    text = "영업 종료",
                    onClick = onConfirmEnd,
                    enabled = !hasActiveTrip,
                    loading = submitting,
                    modifier = Modifier.weight(1.4f).height(60.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    title: String,
    subtitle: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MoyeotaType.HeadingMd, color = MoyeotaColor.InkPrimary)
            Text(text = subtitle, style = MoyeotaType.BodyMd, color = MoyeotaColor.TextBody)
        }
        Text(text = value, style = MoyeotaType.NumberMd, color = MoyeotaColor.InkPrimary)
    }
}
