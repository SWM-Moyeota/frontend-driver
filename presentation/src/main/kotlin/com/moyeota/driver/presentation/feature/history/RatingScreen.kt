package com.moyeota.driver.presentation.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.RatingSummary
import com.moyeota.driver.domain.model.ReviewComment
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── D19 내 평점 (승객 평가) — 피그마 2521:916 ─────────────────────────────
// 승객이 매긴 평점·코멘트 조회 전용. 하단탭 노출 화면(임시 마이 탭).

class RatingViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val summary: RatingSummary) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(repository.getRatingSummary())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("평점 정보를 불러오지 못했어요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { RatingViewModel(repository) }
        }
    }
}

@Composable
fun RatingRoute(
    onTabSelect: (MoyeotaTab) -> Unit,
    onCommentClick: (tripId: String) -> Unit,
    onHistoryClick: () -> Unit,
    repository: DriverRepository,
) {
    val viewModel: RatingViewModel = viewModel(factory = RatingViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    TabStateScaffold(selectedTab = MoyeotaTab.MYPAGE, onTabSelect = onTabSelect) {
        when (val state = uiState) {
            is RatingViewModel.UiState.Loading -> LoadingBox()
            is RatingViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is RatingViewModel.UiState.Success -> RatingScreen(
                summary = state.summary,
                onCommentClick = onCommentClick,
                onHistoryClick = onHistoryClick,
            )
        }
    }
}

@Composable
fun RatingScreen(
    summary: RatingSummary,
    onCommentClick: (tripId: String) -> Unit,
    onHistoryClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MoyeotaTopBar(title = "내 평점")

        AverageScoreCard(summary = summary)

        // 최근 100건 기준 4.5 미만이면 warning 배너 + 교육 안내
        if (summary.totalCount >= 5 && summary.average < 4.5) {
            NoticeBanner(
                kind = NoticeKind.WAITING,
                text = "최근 평점이 4.5 미만이에요. 서비스 교육 안내를 확인해 주세요",
            )
        }

        DistributionSection(summary = summary)

        HistoryEntryRow(onClick = onHistoryClick)

        Text(
            text = "최근 승객 코멘트",
            style = MoyeotaType.HeadingLg,
            color = MoyeotaColor.TextMute,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (summary.comments.isEmpty()) {
            Text(
                text = "아직 코멘트가 없어요",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        } else {
            summary.comments.forEach { comment ->
                CommentCard(comment = comment, onClick = { onCommentClick(comment.tripId) })
            }
        }
    }
}

@Composable
private fun AverageScoreCard(summary: RatingSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (summary.totalCount < 5) {
                    // 평가 5건 미만이면 점수 대신 표기
                    Text(
                        text = "평가 모으는 중",
                        style = MoyeotaType.NumberMd,
                        color = MoyeotaColor.InkPrimary,
                    )
                } else {
                    Text(
                        text = String.format("%.2f", summary.average),
                        style = MoyeotaType.NumberXl,
                        color = MoyeotaColor.InkDeep,
                    )
                }
                Text(
                    text = "최근 100건 평균 · 총 ${formatCount(summary.totalCount)}건",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextBody,
                )
            }
            if (summary.totalCount >= 5 && summary.average >= 4.5) {
                StatusBadge(kind = NoticeKind.INFO, text = "우수 기사")
            }
        }
    }
}

@Composable
private fun DistributionSection(summary: RatingSummary) {
    val total = summary.distribution.values.sum().coerceAtLeast(1)
    val fiveCount = summary.distribution[5] ?: 0
    val fourCount = summary.distribution[4] ?: 0
    val lowCount = (1..3).sumOf { summary.distribution[it] ?: 0 }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DistributionRow(label = "별 5개", count = fiveCount, total = total)
        DistributionRow(label = "별 4개", count = fourCount, total = total)
        DistributionRow(label = "별 3개 이하", count = lowCount, total = total)
    }
}

// 별점 분포 한 줄 — 건수 + 비율 바 + 퍼센트
@Composable
private fun DistributionRow(label: String, count: Int, total: Int) {
    val fraction = count.toFloat() / total
    val percent = (fraction * 100).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
                Text(text = "$percent%", style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
            }
            Text(text = "${count}건", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextMute)
            // 비율 바
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MoyeotaColor.SurfaceSoft, CircleShape),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(MoyeotaColor.Primary500, CircleShape),
                    )
                }
            }
        }
    }
}

// D20 운행 이력으로 가는 진입 행
@Composable
private fun HistoryEntryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "운행 이력 전체 보기", style = MoyeotaType.BodyLg, color = MoyeotaColor.InkPrimary)
        Text(text = "›", style = MoyeotaType.HeadingLg, color = MoyeotaColor.TextMute)
    }
}

// 코멘트 카드 — 탭하면 해당 운행 상세(D21)로 이동
@Composable
private fun CommentCard(comment: ReviewComment, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "\"${comment.text}\"",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )
        Text(
            text = "${comment.dateLabel} · 별 ${comment.rating}개",
            style = MoyeotaType.BodyMd,
            color = MoyeotaColor.TextMute,
        )
    }
}

private fun formatCount(value: Int): String = "%,d".format(value)
