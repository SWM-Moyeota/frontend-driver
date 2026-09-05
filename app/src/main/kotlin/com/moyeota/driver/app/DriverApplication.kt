package com.moyeota.driver.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicInteger

class DriverApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    /**
     * 화면이 떠 있는 액티비티 수. 콜 인입 시 "지금 화면을 보고 있는가"를 판정한다 —
     * 포그라운드면 콜 화면으로 즉시 전환하므로 시스템 알림까지 띄우면 화면을 가리는 중복이 된다.
     */
    private val startedActivities = AtomicInteger(0)

    val isForeground: Boolean get() = startedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        createNotificationChannel()
        trackForeground()
        fetchFcmToken()
    }

    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) { startedActivities.decrementAndGet() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    // onNewToken/onRegistered 콜백은 토큰이 신규 발급되거나 회전될 때만 발화한다.
    // 최초 설치 시점을 지난 기존 설치분은 콜백이 오지 않으므로 앱 시작마다 명시 조회한다.
    //
    // getToken() 은 SDK 25.x 에서 deprecated 이나 후속 API 인 register() 는
    // 매니페스트 meta-data opt-in 없이는 IllegalStateException 을 던지므로 현행 유지한다 (승객 앱과 동일 판단).
    @Suppress("DEPRECATION")
    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                val token = task.result
                if (!task.isSuccessful || token == null) {
                    Log.w(TAG, "FCM 토큰 조회 실패", task.exception)
                    return@addOnCompleteListener
                }
                DriverFirebaseMessagingService.handleToken(this, token)
            }
    }

    // 콜 푸시 알림 표시에 필요한 기본 채널. O 미만은 채널 개념이 없어 생략한다.
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            DriverFirebaseMessagingService.CHANNEL_ID,
            DriverFirebaseMessagingService.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "MoyeotaDriverFcm"
    }
}
