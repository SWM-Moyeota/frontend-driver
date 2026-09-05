package com.moyeota.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// 기사 앱은 다크 모드 고정 (피그마 공통 규칙: 야간 · 거치대 시인성)
private val DarkColorScheme = darkColorScheme(
    primary = MoyeotaColor.Primary500,
    onPrimary = MoyeotaColor.TextOnDark,
    primaryContainer = MoyeotaColor.Primary50,
    onPrimaryContainer = MoyeotaColor.Primary500,
    error = MoyeotaColor.Danger500,
    onError = MoyeotaColor.TextOnDark,
    background = MoyeotaColor.SurfaceCanvas,
    onBackground = MoyeotaColor.InkPrimary,
    surface = MoyeotaColor.SurfaceCanvas,
    onSurface = MoyeotaColor.InkPrimary,
    surfaceVariant = MoyeotaColor.SurfaceSoft,
    onSurfaceVariant = MoyeotaColor.TextMute,
    outline = MoyeotaColor.Hairline,
)

@Composable
fun MoyeotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
