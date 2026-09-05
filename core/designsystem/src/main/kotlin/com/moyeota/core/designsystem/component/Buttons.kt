package com.moyeota.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 주 CTA. 필수 조건 미충족 시 enabled=false, 제출 중이면 loading=true (공통 규칙: 중복 제출 차단)
@Composable
fun PrimaryCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled && !loading,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MoyeotaColor.Primary500,
            contentColor = MoyeotaColor.TextOnDark,
            disabledContainerColor = MoyeotaColor.SurfaceSoft,
            disabledContentColor = MoyeotaColor.TextAsh,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text = text, style = MoyeotaType.ButtonLg)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, MoyeotaColor.Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MoyeotaColor.InkPrimary),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text = text, style = MoyeotaType.ButtonLg)
    }
}

// 비상 신고 전용 버튼 — Safety 색은 여기 외 사용 금지
@Composable
fun SafetyButton(
    text: String,
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 3_000,
    onShortPress: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 콜백은 최신 참조 유지 — pointerInput 의 key 재시작 없이 람다 교체를 흡수한다
    val currentOnHoldComplete by rememberUpdatedState(onHoldComplete)
    val currentOnShortPress by rememberUpdatedState(onShortPress)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(CircleShape)
            .background(MoyeotaColor.Safety600)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        var completed = false
                        val hold = scope.launch {
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            completed = true
                            currentOnHoldComplete()
                        }
                        val released = tryAwaitRelease()
                        if (!completed) {
                            hold.cancel()
                            if (released) currentOnShortPress()
                        }
                        progress.snapTo(0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 홀드 진행률 — 왼쪽부터 차오르는 게이지. 3초를 채워야 발화한다 (오신고 방지)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(MoyeotaColor.Safety500),
        )
        Text(text = text, style = MoyeotaType.ButtonLg, color = MoyeotaColor.TextOnDark)
    }
}
