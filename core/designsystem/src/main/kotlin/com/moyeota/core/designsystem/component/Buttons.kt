package com.moyeota.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MoyeotaColor.Safety500,
            contentColor = MoyeotaColor.TextOnDark,
        ),
    ) {
        Text(text = text, style = MoyeotaType.ButtonLg)
    }
}
