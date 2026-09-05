package com.moyeota.core.designsystem.component

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap

/** 부산 서면 — MVP1 기본 카메라 위치 */
val MoyeotaDefaultCamera: LatLng = LatLng(35.1578, 129.0594)

/**
 * 네이버 지도 뷰 (공용 컴포넌트).
 *
 * 네이버는 공식 Compose API를 제공하지 않아 AndroidView 로 MapView 를 감싼다.
 * MapView 는 Activity 생명주기 콜백을 직접 받아야 하므로(onCreate~onDestroy 미호출 시
 * 지도가 렌더링되지 않거나 네이티브 리소스가 누수된다) DisposableEffect 로 전달한다.
 *
 * @param center 카메라 중심. **변경 시 지도에 반영된다** (동적 좌표 화면 지원)
 * @param zoom 줌 레벨. center 와 동일하게 변경이 반영된다
 * @param contentPadding 지도 위에 겹쳐지는 UI(시트·바) 영역. 이 값을 빼고 남은
 *   가시 영역을 기준으로 카메라 중심이 잡히므로, 마커가 시트에 가려지지 않는다
 * @param onMapReady 지도 준비 완료 콜백. 마커·오버레이 추가는 여기서 한다
 */
@Composable
fun NaverMapView(
    modifier: Modifier = Modifier,
    center: LatLng = MoyeotaDefaultCamera,
    zoom: Double = 14.0,
    contentPadding: PaddingValues = PaddingValues(),
    onMapReady: (NaverMap) -> Unit = {},
) {
    // @Preview / 레이아웃 인스펙터에서는 MapView 가 네이티브 초기화에 실패하므로 대체 배경만 그린다
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(MoyeotaColor.SurfaceSoft))
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<NaverMap?>(null) }
    // 콜백은 최신 참조를 유지 — 이펙트 key 에 넣으면 람다 재생성마다 이펙트가 재실행된다
    val currentOnMapReady by rememberUpdatedState(onMapReady)

    // 탭 전환·뒤로가기로 재진입해도 사용자가 보던 위치를 유지한다.
    // CameraPosition 이 Parcelable 이라 기본 saver 로 저장된다.
    var savedCamera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    // 보존된 카메라 복원은 최초 1회만. 이후 center/zoom 변경은 그대로 반영돼야 한다
    var cameraInitialized by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        // onCreate 이후에 getMapAsync 를 걸어야 콜백이 정상 큐잉된다
        mapView.onCreate(Bundle())
        mapView.getMapAsync { map = it }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            // RESUMED 상태에서 컴포저블이 dispose 되면(탭 전환 등) 생명주기 이벤트가
            // 오지 않으므로, DESTROYED 로 점프하지 않도록 현재 상태에서 직접 되감는다
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
            map = null
        }
    }

    // 오버레이에 가려지는 영역을 지도에 알려 카메라 중심을 가시 영역으로 보정한다.
    // 카메라 적용보다 먼저 반영돼야 중심 계산이 어긋나지 않는다.
    LaunchedEffect(map, contentPadding, density, layoutDirection) {
        val naverMap = map ?: return@LaunchedEffect
        with(density) {
            naverMap.setContentPadding(
                contentPadding.calculateStartPadding(layoutDirection).roundToPx(),
                contentPadding.calculateTopPadding().roundToPx(),
                contentPadding.calculateEndPadding(layoutDirection).roundToPx(),
                contentPadding.calculateBottomPadding().roundToPx(),
            )
        }
    }

    // 카메라 적용을 생명주기 이펙트에서 분리 — center/zoom 이 바뀌면 지도에 반영된다
    LaunchedEffect(map, center, zoom) {
        val naverMap = map ?: return@LaunchedEffect
        val restored = savedCamera
        naverMap.cameraPosition =
            if (!cameraInitialized && restored != null) restored else CameraPosition(center, zoom)
        cameraInitialized = true
    }

    // 사용자가 움직인 카메라를 즉시 보존한다. dispose 시점에 읽으면
    // rememberSaveable 저장 순서에 의존하게 되므로 이동이 멈출 때마다 기록한다.
    //
    // 단, **초기 카메라를 적용한 뒤에만**(cameraInitialized) 기록해야 한다.
    // 이 이펙트는 apply 단계에서 등록되어 위 카메라 LaunchedEffect 본문보다 먼저 살아나는데,
    // SDK 가 지도 준비 직후 자체 기본 카메라(서울 경복궁)로 idle 을 한 번 쏜다.
    // 게이팅이 없으면 그 이벤트가 복원해야 할 값을 덮어써서 카메라 보존이 통째로 무력화된다.
    DisposableEffect(map, cameraInitialized) {
        val naverMap = map
        val listener = if (naverMap != null && cameraInitialized) {
            NaverMap.OnCameraIdleListener { savedCamera = naverMap.cameraPosition }
                .also(naverMap::addOnCameraIdleListener)
        } else {
            null
        }
        onDispose {
            if (naverMap != null && listener != null) {
                naverMap.removeOnCameraIdleListener(listener)
            }
        }
    }

    LaunchedEffect(map) {
        map?.let { currentOnMapReady(it) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
