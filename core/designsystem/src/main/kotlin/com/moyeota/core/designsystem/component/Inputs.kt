package com.moyeota.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 텍스트 입력 — 토큰 text-input (h52, radius 12, 포커스 시 primary 테두리)
// 공통 규칙: 필드 단위 오류는 필드 하단 danger 문구
@Composable
fun MoyeotaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    errorText: String? = null,
    helperText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        errorText != null -> MoyeotaColor.Danger500
        focused -> MoyeotaColor.Primary500
        else -> MoyeotaColor.Hairline
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(text = label, style = MoyeotaType.BodySm, color = MoyeotaColor.TextBody)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MoyeotaType.BodyMd.copy(color = MoyeotaColor.InkPrimary),
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                singleLine = singleLine,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
            if (value.isEmpty()) {
                Text(text = placeholder, style = MoyeotaType.BodyMd, color = MoyeotaColor.TextAsh)
            }
            if (trailing != null) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MoyeotaType.CaptionMd,
                color = MoyeotaColor.Danger500,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (helperText != null) {
            Text(
                text = helperText,
                style = MoyeotaType.CaptionMd,
                color = MoyeotaColor.TextMute,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
