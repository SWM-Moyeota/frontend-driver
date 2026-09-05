package com.moyeota.driver.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// home 그룹 내부 공용 요소 — 2개 화면(D06·D07) 이상에서 쓰지만 home 전용이라 designsystem 승격 대상 아님.

internal fun formatWon(amount: Int): String = "%,d원".format(amount)

internal fun formatOnlineMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}

// 합승 프로모션 배너 — D06·D07 상시 노출, 탭하면 D22 프로모션 안내 (터치 타깃 60dp 이상)
@Composable
internal fun PromotionBanner(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MoyeotaColor.SurfaceSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "🎁  $text",
            style = MoyeotaType.BodyLg,
            color = MoyeotaColor.TextBody,
        )
    }
}

// D07 상단 영업 토글 (46x26, 노브 20) — off 조작은 D08 확인 화면으로 위임
@Composable
internal fun DutyToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = if (checked) MoyeotaColor.Primary500 else MoyeotaColor.SurfaceSoft
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(onClick = onToggle)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(MoyeotaColor.InkDeep, CircleShape),
        )
    }
}
