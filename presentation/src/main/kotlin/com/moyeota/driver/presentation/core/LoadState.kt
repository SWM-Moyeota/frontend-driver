package com.moyeota.driver.presentation.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.MoyeotaBottomBar
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 승객 앱 LoadState.kt 포팅 — 기사 앱은 다크 고정이라 배경 토큰만 다크 값을 쓴다.

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MoyeotaColor.Primary500)
    }
}

@Composable
fun ErrorBox(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, style = MoyeotaType.BodyLg, color = MoyeotaColor.TextBody)
        SecondaryButton(
            text = "다시 시도",
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// 하단탭 화면(D06 · D07 · D09 · D12 · D17 · D19 · D20)의 골격.
// 로딩 · 에러 동안에도 탭바는 남겨 인앱 이동이 끊기지 않게 한다 (승객 앱 QA F-1 학습).
@Composable
fun TabStateScaffold(
    selectedTab: MoyeotaTab,
    onTabSelect: (MoyeotaTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        MoyeotaBottomBar(selected = selectedTab, onSelect = onTabSelect)
    }
}

// 탭바가 없는 화면의 골격 — 상태가 무엇이든 뒤로가기는 남긴다.
@Composable
fun BackStateScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = title, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}
