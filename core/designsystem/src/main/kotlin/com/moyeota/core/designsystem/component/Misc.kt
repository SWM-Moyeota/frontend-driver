package com.moyeota.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 온보딩 페이지 인디케이터 — 활성은 20x8 pill, 비활성은 8x8 dot
@Composable
fun PageDots(count: Int, activeIndex: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            if (i == activeIndex) {
                Box(Modifier.size(20.dp, 8.dp).background(MoyeotaColor.Primary500, CircleShape))
            } else {
                Box(Modifier.size(8.dp).background(MoyeotaColor.TextAsh, CircleShape))
            }
        }
    }
}

enum class NoticeKind { INFO, ERROR, SUCCESS, WAITING }

// 공통 규칙: 화면 단위 오류는 CTA 위 NoticeBanner Kind=error
@Composable
fun NoticeBanner(kind: NoticeKind, text: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        NoticeKind.INFO -> MoyeotaColor.Primary50 to MoyeotaColor.Primary600
        NoticeKind.ERROR -> MoyeotaColor.Danger50 to MoyeotaColor.Danger600
        NoticeKind.SUCCESS -> MoyeotaColor.Success50 to MoyeotaColor.Success600
        NoticeKind.WAITING -> MoyeotaColor.Waiting50 to MoyeotaColor.Waiting600
    }
    Box(
        modifier = modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = text, style = MoyeotaType.BodySm, color = fg)
    }
}

// 상태 배지 (모집 중 · 매칭 완료 · 대기 등)
@Composable
fun StatusBadge(kind: NoticeKind, text: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        NoticeKind.INFO -> MoyeotaColor.Primary50 to MoyeotaColor.Primary600
        NoticeKind.ERROR -> MoyeotaColor.Danger50 to MoyeotaColor.Danger600
        NoticeKind.SUCCESS -> MoyeotaColor.Success50 to MoyeotaColor.Success600
        NoticeKind.WAITING -> MoyeotaColor.Waiting50 to MoyeotaColor.Waiting600
    }
    Box(modifier = modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text = text, style = MoyeotaType.CaptionMd, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

// 아바타 자리표시 (와이어프레임: 회색 원)
@Composable
fun AvatarCircle(size: Dp = 40.dp, modifier: Modifier = Modifier, label: String? = null) {
    Box(
        modifier = modifier.size(size).background(MoyeotaColor.SurfaceSoft, CircleShape).border(1.dp, MoyeotaColor.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(text = label, style = MoyeotaType.CaptionMd, color = MoyeotaColor.TextMute)
        }
    }
}

// 선택 칩 (매칭 조건 등)
@Composable
fun MoyeotaChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MoyeotaColor.Primary50 else MoyeotaColor.SurfaceCanvas
    val fg = if (selected) MoyeotaColor.Primary600 else MoyeotaColor.TextMute
    val border = if (selected) MoyeotaColor.Primary500 else MoyeotaColor.Hairline
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MoyeotaType.ButtonMd, color = fg)
    }
}

// 바텀시트 핸들
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 4.dp)
            .background(MoyeotaColor.TextAsh, CircleShape),
    )
}

// 지도 자리표시 — SDK 없이 와이어프레임처럼 격자 배경
@Composable
fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MoyeotaColor.SurfaceSoft)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .size(height = 2.dp, width = Dp.Unspecified)
                .background(MoyeotaColor.Hairline),
        )
        Text(
            text = "지도 영역",
            style = MoyeotaType.CaptionMd,
            color = MoyeotaColor.TextAsh,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
