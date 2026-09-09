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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.MoyeotaDefaultCamera
import com.moyeota.core.designsystem.component.NaverMapView
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap

// home 그룹 내부 공용 요소 — 2개 화면(D06·D07) 이상에서 쓰지만 home 전용이라 designsystem 승격 대상 아님.

internal fun formatWon(amount: Int): String = "%,d원".format(amount)

internal fun formatOnlineMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}

/**
 * D06·D07 공용 홈 지도 — 기사 현재 위치를 카메라 중심으로 잡고 내 위치 오버레이를 띄운다.
 * 측위 전(권한 미허용·GPS 미확정)에는 서면 기본 카메라로 폴백하고 오버레이는 숨긴다 —
 * 엉뚱한 지점에 "내 위치" 점을 찍는 것보다 없는 편이 덜 헷갈린다.
 */
@Composable
internal fun HomeMyLocationMap(
    myLocation: LatLng?,
    modifier: Modifier = Modifier,
) {
    // NaverMap 준비와 위치 갱신 중 어느 쪽이 먼저 와도 오버레이가 최신 좌표를 가리키도록
    // 지도 참조를 상태로 잡아 LaunchedEffect 키로 묶는다
    var map by remember { mutableStateOf<NaverMap?>(null) }

    NaverMapView(
        modifier = modifier,
        center = myLocation ?: MoyeotaDefaultCamera,
        onMapReady = { map = it },
    )

    LaunchedEffect(map, myLocation) {
        val overlay = map?.locationOverlay ?: return@LaunchedEffect
        if (myLocation != null) {
            overlay.position = myLocation
            overlay.isVisible = true
        } else {
            overlay.isVisible = false
        }
    }
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
