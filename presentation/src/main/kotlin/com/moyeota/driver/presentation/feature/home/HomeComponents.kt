package com.moyeota.driver.presentation.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import com.moyeota.core.designsystem.component.NoticeKind
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

// ── 위치 권한 (D06 영업 시작 게이트 · D07 영업 중 권한 회수 감지) ──────────────────
// 새 의존성 없이 플랫폼 API 로만 처리한다 (minSdk 24 → Context.checkSelfPermission 사용 가능).

/** 영업에 필요한 위치 권한 — 정확/대략 중 하나만 허용돼도 측위 자체는 가능하다. */
internal val LocationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

internal fun Context.hasLocationPermission(): Boolean =
    LocationPermissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/**
 * 앱 설정 화면(권한 항목)으로 보낸다.
 * 영구 거부 상태에서는 시스템 권한 다이얼로그가 더 이상 뜨지 않으므로 이 경로가 유일한 출구다.
 */
internal fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * 홈 전용 안내 배너 — 화면 전체를 에러로 덮는 대신 이 배너로만 알려 재시도 경로를 남긴다.
 * [actionText] 를 주면 배너 안에 처리 버튼(설정 이동 등)이 붙는다.
 *
 * designsystem 의 `NoticeBanner` 는 액션 버튼을 받지 못하고 본문이 13sp 라,
 * 거치대 시인성 규칙(본문 17sp · 터치 타깃 60dp)에 맞춰 home 안에서만 확장했다.
 */
@Composable
internal fun HomeNotice(
    kind: NoticeKind,
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (background, foreground) = when (kind) {
        NoticeKind.INFO -> MoyeotaColor.Primary50 to MoyeotaColor.Primary600
        NoticeKind.ERROR -> MoyeotaColor.Danger50 to MoyeotaColor.Danger600
        NoticeKind.SUCCESS -> MoyeotaColor.Success50 to MoyeotaColor.Success600
        NoticeKind.WAITING -> MoyeotaColor.Waiting50 to MoyeotaColor.Waiting600
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = text, style = MoyeotaType.BodyLg, color = foreground)
        if (actionText != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, foreground),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
            ) {
                Text(text = actionText, style = MoyeotaType.ButtonLg)
            }
        }
    }
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
