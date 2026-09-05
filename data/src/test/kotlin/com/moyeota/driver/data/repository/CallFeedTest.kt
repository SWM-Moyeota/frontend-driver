package com.moyeota.driver.data.repository

import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FCM 콜 피드 병합 규칙 검증:
 * 피드 실콜(최신 도착 순)이 앞, 더미 목록이 뒤 + 중복 id 는 실콜이 이긴다.
 */
class CallFeedTest {

    private fun call(id: String, pickup: String = "출발-$id") = CallSummary(
        id = id, type = CallType.SOLO,
        pickupPlace = pickup, dropoffPlace = "도착-$id",
        distanceToPickupKm = 1.0, expectedFare = 10_000, callFee = 3_000, poolBonus = 0,
        passengerCount = 1, createdAtLabel = "방금 전",
    )

    @Test
    fun `빈 피드는 더미 목록을 그대로 돌려준다`() {
        val dummy = listOf(call("call-1"), call("call-2"))
        assertEquals(dummy, CallFeed().mergedWith(dummy))
    }

    @Test
    fun `피드 실콜이 더미 목록 앞에 온다 - 최신 도착이 맨 앞`() {
        val feed = CallFeed().apply {
            add(call("101"))
            add(call("102"))   // 나중 도착
        }
        val merged = feed.mergedWith(listOf(call("call-1")))
        assertEquals(listOf("102", "101", "call-1"), merged.map { it.id })
    }

    @Test
    fun `더미 쪽 중복 id 는 제거되고 실콜이 남는다`() {
        val feed = CallFeed().apply { add(call("101", pickup = "실콜 출발지")) }
        val merged = feed.mergedWith(listOf(call("101", pickup = "더미 출발지"), call("call-2")))
        assertEquals(listOf("101", "call-2"), merged.map { it.id })
        assertEquals("실콜 출발지", merged.first().pickupPlace)
    }

    @Test
    fun `같은 partyId 재수신은 최신 요약으로 교체되고 맨 앞으로 온다`() {
        val feed = CallFeed().apply {
            add(call("101", pickup = "구버전"))
            add(call("102"))
            add(call("101", pickup = "신버전"))   // 재수신
        }
        val merged = feed.mergedWith(emptyList())
        assertEquals(listOf("101", "102"), merged.map { it.id })
        assertEquals("신버전", merged.first().pickupPlace)
    }

    @Test
    fun `remove 후에는 병합 결과에서 사라진다 - CALL_CLOSED · 수락`() {
        val feed = CallFeed().apply {
            add(call("101"))
            add(call("102"))
            remove("101")
            remove("없는-id")   // no-op
        }
        assertEquals(listOf("102"), feed.mergedWith(emptyList()).map { it.id })
    }
}
