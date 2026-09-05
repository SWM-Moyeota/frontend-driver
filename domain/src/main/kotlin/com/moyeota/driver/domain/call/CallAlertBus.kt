package com.moyeota.driver.domain.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 콜 인입 알림(CALL_OPENED)을 FCM 수신 레이어 → 화면으로 밀어 넣는 프로세스 전역 버스.
 *
 * 기사 앱에서 콜은 "기사가 찾아가는 목록"이 아니라 "즉시 응답해야 하는 인터럽트"다.
 * 서버 콜 TTL 이 짧아(수락 지연 시 409 CALL_CLOSED) 기사가 콜 목록을 수동으로 여는 동안 콜이 마감된다.
 * 그래서 CALL_OPENED 수신 즉시 [open] 으로 알림을 밀어 넣고, 화면(MainNavGraph)이 이를 구독해
 * 콜 상세(D10) 전체화면으로 자동 전환한다.
 *
 * - 앱이 포그라운드면: 구독 중인 NavHost 가 즉시 D10 으로 전환한다.
 * - 앱이 백그라운드/종료 상태면: full-screen intent 알림이 액티비티를 띄우고,
 *   MainActivity 가 인텐트 extra 로 같은 partyId 를 [open] 해 동일 경로로 합류한다.
 *
 * 화면은 진입에 성공하면 [consume] 으로 값을 비운다(같은 콜로 재진입하는 루프 방지).
 * 콜이 마감(CALL_CLOSED)되면 [close] 가 대기 중인 같은 콜 알림을 걷어낸다.
 *
 * 인메모리 · 프로세스 수명 — 앱이 죽으면 사라지지만, 그때는 알림 탭 경로가 대신 진입시킨다.
 */
object CallAlertBus {

    private val _pending = MutableStateFlow<CallAlert?>(null)

    /** 아직 화면에 반영되지 않은 콜 알림. null 이면 대기 중인 콜 없음 */
    val pending: StateFlow<CallAlert?> = _pending.asStateFlow()

    private val _handled = MutableStateFlow<String?>(null)

    /**
     * 화면 진입이 끝나 더 이상 알림이 필요 없는 partyId.
     * app 모듈이 구독해 같은 콜의 시스템 알림을 내린다 — 콜 화면을 보고 있는데
     * "새 콜 도착" 알림이 화면 위에 계속 떠 있는 상태를 막는다.
     */
    val handled: StateFlow<String?> = _handled.asStateFlow()

    /** CALL_OPENED 수신 · 알림 탭 — 최신 콜로 덮어쓴다(콜은 최신 것이 유효) */
    fun open(partyId: String, source: CallAlertSource) {
        _pending.value = CallAlert(partyId = partyId, source = source, receivedAt = System.currentTimeMillis())
    }

    /** 화면이 해당 콜로 진입 완료 — 대기 값 비움. 그 사이 다른 콜이 들어왔으면 그대로 둔다 */
    fun consume(partyId: String) {
        _pending.update { current -> if (current?.partyId == partyId) null else current }
        _handled.value = partyId
    }

    /** CALL_CLOSED 수신 — 아직 화면에 못 띄운 같은 콜 알림을 걷어낸다 */
    fun close(partyId: String) = consume(partyId)
}

/** 콜 알림 1건 — 같은 partyId 라도 재수신(receivedAt 갱신)되면 새 알림으로 취급된다 */
data class CallAlert(
    val partyId: String,
    val source: CallAlertSource,
    val receivedAt: Long,
)

enum class CallAlertSource {
    /** FCM data 메시지 직접 수신 (앱 프로세스 생존 중) */
    PUSH,

    /** 알림/전체화면 인텐트 탭으로 액티비티가 전달받음 */
    NOTIFICATION,
}
