package com.moyeota.driver.data.repository

import com.moyeota.driver.domain.model.CallSummary

/**
 * FCM(CALL_OPENED/CALL_CLOSED)으로 도착한 실콜의 인메모리 피드.
 *
 * 백엔드에 기사 기준 "열린 콜 목록" 조회 API 가 없어(CallCandidates 가 partyId 키의 Redis set)
 * 실콜은 푸시로만 도착한다 — 이 피드가 D09 콜 목록의 실콜 원천이 된다.
 *
 * 동시성: FCM 서비스(IO 스코프)의 추가/제거와 화면(ViewModel 코루틴)의 조회가 경합하므로
 * 모든 연산을 [lock] 으로 직렬화한다. 프로세스 생존 동안만 유지(디스크 저장 없음).
 */
class CallFeed {

    private val lock = Any()

    /** partyId(문자열) → 콜 요약. 삽입 순서 유지 — 나중에 도착한 콜이 뒤에 쌓인다 */
    private val calls = LinkedHashMap<String, CallSummary>()

    /** CALL_OPENED — 같은 partyId 재수신 시 최신 요약으로 교체하고 맨 뒤(최신)로 옮긴다 */
    fun add(call: CallSummary) {
        synchronized(lock) {
            calls.remove(call.id)
            calls[call.id] = call
        }
    }

    /** CALL_CLOSED · 콜 수락 성공 — 피드에서 제거. 없는 id 는 no-op */
    fun remove(callId: String) {
        synchronized(lock) { calls.remove(callId) }
    }

    /** 피드의 실콜을 최신 도착 순(나중에 들어온 콜이 앞)으로 돌려준다 */
    fun snapshot(): List<CallSummary> = synchronized(lock) { calls.values.toList() }.asReversed()

    /**
     * 피드의 실콜(최신 도착 순)을 앞에, [others](더미 목록 등)를 뒤에 병합한다.
     * [others] 쪽의 중복 id 는 제거 — 실콜이 항상 이긴다.
     */
    fun mergedWith(others: List<CallSummary>): List<CallSummary> {
        val feed = snapshot()
        val feedIds = feed.mapTo(HashSet()) { it.id }
        return feed + others.filter { it.id !in feedIds }
    }
}
