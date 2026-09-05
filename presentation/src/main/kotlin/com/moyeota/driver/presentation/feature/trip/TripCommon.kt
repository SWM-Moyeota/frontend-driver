package com.moyeota.driver.presentation.feature.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.NaverMapView
import com.moyeota.driver.presentation.core.GangnamCenter
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 운행 플로우(D13~D16) 공용 요소 — trip 패키지 전용.

/** D13·D15 화면 꺼짐 방지 (피그마 공통 규칙: 운행 화면은 FLAG_KEEP_SCREEN_ON 유지) */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

internal fun formatWon(amount: Int): String = "%,d".format(amount)

internal fun formatKm(km: Double): String =
    if (km < 1.0) "${(km * 1000).toInt()}m" else "%.1fkm".format(km)

/** 지도 자리표시 + 좌상단 MapPill (경로 요약) — D13·D15 네비 영역 */
@Composable
internal fun TripMap(pillText: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {
        NaverMapView(modifier = Modifier.fillMaxSize(), center = GangnamCenter)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(MoyeotaColor.SurfaceCard, CircleShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(text = pillText, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
        }
    }
}
