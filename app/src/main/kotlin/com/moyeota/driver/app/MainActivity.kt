package com.moyeota.driver.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moyeota.core.designsystem.theme.MoyeotaTheme
import com.moyeota.driver.domain.call.CallAlertBus
import com.moyeota.driver.domain.call.CallAlertSource
import com.moyeota.driver.presentation.core.MainNavGraph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 거부해도 재요청하지 않는다. 콜 알림을 놓칠 수 있다는 경고는 화면 쪽에서 다룬다.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 위치 권한 — 허용 즉시 측위 구독을 시작해야 영업 시작 시점의 하트비트가 실좌표를 싣는다.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) locationSource.start()
        }

    private val locationSource: AndroidLocationSource
        get() = (application as DriverApplication).appContainer.locationSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 최초 생성에서만 요청 — 구성 변경 재생성 시 재요청하면 "거부 시 재요청 안 함" 정책이 깨진다.
        if (savedInstanceState == null) {
            requestNotificationPermissionIfNeeded()
            requestLocationPermissionIfNeeded()
        }
        locationSource.start()   // 이미 허용된 상태면 즉시 구독 시작
        cancelNotificationsForHandledCalls()
        publishCallAlert(intent)
        val container = (application as DriverApplication).appContainer
        setContent {
            MoyeotaTheme {
                MainNavGraph(repository = container.driverRepository)
            }
        }
    }

    // 이미 실행 중(singleTop/CLEAR_TOP)일 때 알림을 탭하거나 full-screen intent 가 발화하면 여기로 들어온다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishCallAlert(intent)
    }

    /**
     * 알림·full-screen intent 로 전달된 partyId 를 [CallAlertBus] 에 올려 콜 상세(D10) 자동 진입을 트리거한다.
     * 앱이 살아있을 때의 FCM 직접 수신 경로와 같은 버스를 쓰므로 진입 로직이 화면 한 곳에만 있다.
     */
    private fun publishCallAlert(intent: Intent?) {
        val partyId = intent?.getStringExtra(DriverFirebaseMessagingService.EXTRA_CALL_PARTY_ID) ?: return
        CallAlertBus.open(partyId, CallAlertSource.NOTIFICATION)
        // 같은 인텐트가 재생성(구성 변경 등)에서 다시 소비되지 않도록 extra 를 비운다
        intent.removeExtra(DriverFirebaseMessagingService.EXTRA_CALL_PARTY_ID)
    }

    /**
     * 콜 화면 진입이 끝난 콜의 시스템 알림을 내린다.
     * setAutoCancel 은 "알림을 탭했을 때"만 동작하므로, 포그라운드 자동 전환 경로에서는
     * 콜 화면 위에 "새 콜 도착" 알림이 계속 남는다 — 그 잔상을 없앤다.
     */
    private fun cancelNotificationsForHandledCalls() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallAlertBus.handled.collect { partyId ->
                    if (partyId != null) {
                        NotificationManagerCompat.from(this@MainActivity).cancel(partyId.hashCode())
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestLocationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
}
