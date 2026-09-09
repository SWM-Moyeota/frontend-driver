package com.moyeota.driver.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.moyeota.driver.domain.call.CallAlertBus
import com.moyeota.driver.domain.call.CallAlertSource
import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM 수신 진입점 — 백엔드 dispatch/infrastructure/FcmCallNotifier 의 data 페이로드를 처리한다.
 * - CALL_OPENED `{type, partyId, departure, destination}` → 콜 피드 추가 + "새 콜 도착" 알림
 * - CALL_CLOSED `{type, partyId}` → 콜 피드 제거 + 해당 알림 취소
 * - 그 외(title/body 페이로드) → 일반 알림 폴백 (승객 앱 패턴)
 *
 * 알림 채널은 [DriverApplication.onCreate] 에서 미리 생성한다.
 */
class DriverFirebaseMessagingService : FirebaseMessagingService() {

    private val repository: DriverRepository
        get() = (application as DriverApplication).appContainer.driverRepository

    // SDK 25.x 에서 deprecated 되었으나 NEW_TOKEN 액션은 여전히 이 콜백으로만 전달된다.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        handleToken(application, token)
    }

    // onNewToken 의 후속 콜백. FCM_REGISTERED 액션은 이쪽으로만 전달되어 둘 다 필요하다.
    override fun onRegistered(token: String) {
        super.onRegistered(token)
        handleToken(application, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        when (message.data["type"]) {
            "CALL_OPENED" -> onCallOpened(message.data)
            "CALL_CLOSED" -> onCallClosed(message.data)
            else -> showFallbackNotification(message)
        }
    }

    /**
     * CALL_OPENED: 푸시 데이터로 임시 요약을 먼저 세운 뒤 파티 상세 조회로 확정하고 알림을 띄운다.
     *
     * 순서가 중요하다 — 화면 전환(CallAlertBus)보다 **먼저** [DriverRepository.seedCallPreview] 로 콜 피드를
     * 채운다. 상세 조회는 네트워크 왕복(느릴 땐 수 초)이라 그것을 기다리면 콜 상세(D10)가 열리는 순간
     * 보여줄 정보가 없고, 예전엔 그 공백을 더미 콜(강남역·판교역)이 메워 실제와 다른 콜이 떴다.
     * 임시 요약은 푸시에 실려 온 실제 출발지·도착지·인원·예상 요금이라 상세 조회가 실패해도 정보가 남는다.
     */
    private fun onCallOpened(data: Map<String, String>) {
        val partyId = data["partyId"] ?: return
        val departure = data["departure"] ?: "출발지 미상"
        val destination = data["destination"] ?: "도착지 미상"
        // 백엔드가 아직 안 보낼 수 있는 값 — 없으면 없는 대로 둔다(화면이 "합승"·"요금 확인 중"으로 표기)
        val memberCount = data["memberCount"]?.trim()?.toIntOrNull()
        val estimatedFare = data["estimatedFare"]?.trim()?.toIntOrNull()

        val preview = repository.seedCallPreview(partyId, departure, destination, memberCount, estimatedFare)

        // 화면 자동 전환 신호를 **상세 조회보다 먼저** 보낸다 — 앱이 떠 있으면 네트워크 왕복을 기다리지 않고
        // 콜 화면이 즉시 열리고, 위 임시 요약이 그 화면의 첫 정보가 된다(콜 TTL 이 짧아 1초가 아깝다).
        CallAlertBus.open(partyId, CallAlertSource.PUSH)

        // 포그라운드면 위 자동 전환이 곧 콜 화면을 띄우므로 알림은 생략한다 —
        // 콜 화면 위에 같은 내용의 헤드업 알림이 겹쳐 뜨는 중복을 막는다.
        val needsNotification = !(application as DriverApplication).isForeground

        messagingScope.launch {
            val summary = runCatching { repository.handleCallOpened(partyId) }.getOrNull() ?: preview
            // 앱이 백그라운드/종료 상태면 콜을 놓치지 않도록 full-screen intent 알림을 띄운다
            if (needsNotification) showCallNotification(partyId, departure, destination, summary)
        }
    }

    /** CALL_CLOSED: 피드 제거 + 대기 중 콜 알림 해제 + 같은 partyId 로 띄웠던 알림 취소 */
    private fun onCallClosed(data: Map<String, String>) {
        val partyId = data["partyId"] ?: return
        CallAlertBus.close(partyId)
        messagingScope.launch { runCatching { repository.handleCallClosed(partyId) } }
        NotificationManagerCompat.from(this).cancel(partyId.hashCode())
    }

    private fun showCallNotification(
        partyId: String,
        departure: String,
        destination: String,
        summary: CallSummary?,
    ) {
        val body = buildString {
            append("출발 $departure → $destination")
            // 인원·금액은 아는 것만 붙인다 — 임시 요약은 둘 다 비어 있을 수 있다
            if (summary != null && summary.hasPassengerCount) append(" · 합승 ${summary.passengerCount}명")
            if (summary != null && summary.hasFareEstimate) append(" · 예상 ${"%,d".format(summary.expectedTotal)}원")
        }
        // 탭 → MainActivity 에 partyId 전달 (콜 상세 D10 진입은 MainNavGraph 가 담당)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_PARTY_ID, partyId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            partyId.hashCode(),   // 콜별로 requestCode 분리 — extras 덮어쓰기 방지
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("새 콜 도착")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 콜은 전화와 같은 즉시 응답 인터럽트 — 잠금화면·다른 앱 위로 콜 화면을 띄운다.
            // (Android 14+ 는 비통화 앱의 full-screen intent 를 헤드업 알림으로 강등할 수 있어
            //  setCategory(CALL) 로 의도를 명시하고, 강등돼도 최상단 헤드업으로는 보이게 한다.)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notify(partyId.hashCode(), notification)
    }

    /** CALL 타입이 아닌 페이로드 — 승객 앱과 같은 일반 알림 폴백 */
    private fun showFallbackNotification(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(this)
        // Android 13+ 에서 POST_NOTIFICATIONS 미허용이면 notify 가 무시되므로 미리 걸러낸다.
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "알림 권한 미허용 상태로 표시 생략")
            return
        }
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "알림 표시 실패", it) }
    }

    companion object {
        private const val TAG = "MoyeotaDriverFcm"

        const val CHANNEL_ID = "moyeota_driver_default"
        const val CHANNEL_NAME = "모여타 기사 알림"

        /** 알림 탭 인텐트 extra — MainActivity → MainNavGraph 로 전달돼 콜 상세(D10) 진입 */
        const val EXTRA_CALL_PARTY_ID = "callPartyId"

        // 프로세스 수명 스코프 — FirebaseMessagingService 는 콜백 반환 직후 파괴될 수 있어
        // 서비스 인스턴스 스코프로는 상세 조회·토큰 전송이 중간에 취소된다.
        private val messagingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 토큰 처리 단일 경로 — 콜백(onNewToken/onRegistered)과 앱 시작 시 명시 조회(DriverApplication)
         * 가 모두 이곳으로 들어온다. Repository 가 미로그인 pending 보관·로그인 후 자동 전송을 책임진다.
         */
        fun handleToken(application: Application, token: String) {
            val repository = (application as DriverApplication).appContainer.driverRepository
            messagingScope.launch {
                runCatching { repository.registerFcmToken(token) }
                    .onFailure { Log.w(TAG, "FCM 토큰 등록 실패", it) }
            }
        }
    }
}
