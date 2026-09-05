package com.moyeota.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 실기에서는 edge-to-edge 로 시스템 상태바가 실제로 그려지므로 목업(가짜 시간·아이콘)을 그리면 겹쳐 보인다.
// 이름은 와이어프레임 시절 그대로 두고, 상태바 높이만큼 비우는 인셋 스페이서로만 동작한다.
@Composable
fun StatusBarMock(modifier: Modifier = Modifier) {
    Spacer(modifier.windowInsetsTopHeight(WindowInsets.statusBars))
}

// 상단 앱바: 뒤로가기 + 타이틀 (+우측 액션)
@Composable
fun MoyeotaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().height(56.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                BackArrowIcon()
            }
        }
        Text(
            text = title,
            style = MoyeotaType.HeadingMd,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

// 아이콘 라이브러리 없이 그리는 뒤로가기 화살표
@Composable
fun BackArrowIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val cx = size.width
        val cy = size.height / 2f
        drawLine(MoyeotaColor.InkPrimary, Offset(cx * 0.15f, cy), Offset(cx * 0.9f, cy), stroke, StrokeCap.Round)
        drawLine(MoyeotaColor.InkPrimary, Offset(cx * 0.15f, cy), Offset(cx * 0.5f, cy * 0.35f), stroke, StrokeCap.Round)
        drawLine(MoyeotaColor.InkPrimary, Offset(cx * 0.15f, cy), Offset(cx * 0.5f, cy * 1.65f), stroke, StrokeCap.Round)
    }
}

enum class MoyeotaTab(val label: String) {
    HOME("홈"), CALLS("콜"), SETTLEMENT("정산"), MYPAGE("마이")
}

// 하단탭 — 기사 앱 공통 규칙: D06·D07·D09·D12·D17·D19·D20 화면에서만 노출
@Composable
fun MoyeotaBottomBar(
    selected: MoyeotaTab,
    onSelect: (MoyeotaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MoyeotaColor.Hairline)
        Row(
            // navigationBarsPadding: 제스처 내비 바와 겹치지 않게 하단 인셋만큼 배경째 내려준다
            modifier = Modifier.fillMaxWidth().background(MoyeotaColor.SurfaceCanvas).navigationBarsPadding().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoyeotaTab.entries.forEach { tab ->
                val color = if (tab == selected) MoyeotaColor.Primary500 else MoyeotaColor.TextMute
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.clickable { onSelect(tab) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Box(Modifier.size(22.dp).background(color, RoundedCornerShape(6.dp)))
                    Text(text = tab.label, style = MoyeotaType.CaptionSm, color = color)
                }
            }
        }
    }
}
