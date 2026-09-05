package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// GRP/A 가입 · 로그인 화면들이 공유하는 auth 전용 요소.
// 2개 이상 feature 에서 쓰이게 되면 core:designsystem 승격 후보 (현재는 auth 전용이라 여기 둔다).

/** 가입 플로우(D02~D04) 진행률 — 승객 v15/StepProgress 대응. 표시 전용. */
@Composable
internal fun StepProgressBar(step: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "$step / $total",
            style = MoyeotaType.BodySm,
            color = MoyeotaColor.TextMute,
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MoyeotaColor.Hairline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(step.coerceAtMost(total) / total.toFloat())
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MoyeotaColor.Primary500),
            )
        }
    }
}

/** 조회 · 심사 항목 한 줄 — 승객 v15/ListRow(meta) 대응. D03b · D05 · D05b 공용. */
@Composable
internal fun ChecklistRow(
    title: String,
    subtitle: String,
    trailing: String,
    modifier: Modifier = Modifier,
    trailingColor: androidx.compose.ui.graphics.Color = MoyeotaColor.InkPrimary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 아이콘 자리표시 (와이어프레임 수준 — 실제 아이콘 대체)
        Box(Modifier.size(20.dp).background(MoyeotaColor.SurfaceSoft, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
            Text(text = subtitle, style = MoyeotaType.BodyLg, color = MoyeotaColor.TextBody)
        }
        Text(
            text = trailing,
            style = MoyeotaType.BodyLg.copy(fontWeight = FontWeight.Bold),
            color = trailingColor,
        )
    }
}

/** 약관 동의 체크 행 — 승객 v15/Checkbox 대응. 행 전체가 탭 영역, 터치 타깃 60dp. */
@Composable
internal fun AgreeCheckRow(
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) MoyeotaColor.Primary500 else MoyeotaColor.SurfaceCard)
                .border(1.5.dp, if (checked) MoyeotaColor.Primary500 else MoyeotaColor.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Box(Modifier.size(12.dp).background(MoyeotaColor.TextOnDark, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MoyeotaType.BodyLg,
            color = if (checked) MoyeotaColor.InkPrimary else MoyeotaColor.TextBody,
        )
    }
}

/** on/off 스위치 — 승객 v15/Toggle(46×26) 대응. 값 즉시 반영. */
@Composable
internal fun AuthToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) MoyeotaColor.Primary500 else MoyeotaColor.SurfaceSoft)
            .border(1.dp, if (checked) MoyeotaColor.Primary500 else MoyeotaColor.Hairline, CircleShape)
            .clickable { onToggle() }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(MoyeotaColor.TextOnDark, CircleShape))
    }
}

/** 단일 선택 라디오 행 — 승객 v15/Radio 대응. D04 개인 · 법인택시 선택. */
@Composable
internal fun RadioOption(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MoyeotaColor.SurfaceCard)
                .border(
                    width = if (selected) 6.dp else 1.5.dp,
                    color = if (selected) MoyeotaColor.Primary500 else MoyeotaColor.Hairline,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MoyeotaType.BodyLg,
            color = if (selected) MoyeotaColor.InkPrimary else MoyeotaColor.TextBody,
        )
    }
}
