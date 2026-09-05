package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartySummaryDto
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPhase
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchMappersTest {

    private val poolParty = PartySummaryDto(
        id = 42L,
        departureLatitude = 37.4980,
        departureLongitude = 127.0276,
        destinationLatitude = 37.3948,
        destinationLongitude = 127.1112,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        memberCount = 2,
        estimatedFare = 18400,
        estimatedTime = 25,
        driverId = null,
    )

    // ── CallSummary 매핑 ───────────────────────────────────────────────

    @Test
    fun `PartySummary - CallSummary 필드 매핑`() {
        val summary = poolParty.toCallSummary()

        assertEquals("42", summary.id)                       // partyId ↔ 콜 id
        assertEquals(CallType.POOL, summary.type)            // memberCount 2 → 합승
        assertEquals("강남역 2번 출구", summary.pickupPlace)
        assertEquals("판교역 1번 출구", summary.dropoffPlace)
        assertEquals(18400, summary.expectedFare)
        assertEquals(DispatchRules.CALL_FEE, summary.callFee)
        assertEquals(DispatchRules.POOL_BONUS, summary.poolBonus)
        assertEquals(2, summary.passengerCount)
    }

    @Test
    fun `단독 콜은 SOLO 타입에 합승 보너스 0`() {
        val summary = poolParty.copy(memberCount = 1).toCallSummary()

        assertEquals(CallType.SOLO, summary.type)
        assertEquals(0, summary.poolBonus)
        assertEquals(1, summary.passengerCount)
    }

    @Test
    fun `null 필드 방어 - 기본값으로 채운다`() {
        val summary = PartySummaryDto(id = 7L).toCallSummary()

        assertEquals("7", summary.id)
        assertEquals("출발지 미상", summary.pickupPlace)
        assertEquals("도착지 미상", summary.dropoffPlace)
        assertEquals(0, summary.expectedFare)
        assertEquals(0.0, summary.distanceToPickupKm, 0.0)
        assertEquals(1, summary.passengerCount)
    }

    @Test
    fun `id 누락은 partyId 0 으로 뭉개지 않고 즉시 실패한다`() {
        try {
            PartySummaryDto().toCallSummary()
            org.junit.Assert.fail("id 누락인데 예외가 발생하지 않음")
        } catch (expected: IllegalStateException) {
            // 통과 — null id 가 "0" 으로 remote 경로를 타는 것을 차단
        }
    }

    @Test
    fun `기사 위치가 있으면 픽업 거리 계산, 없으면 0`() {
        val without = poolParty.toCallSummary()
        assertEquals(0.0, without.distanceToPickupKm, 0.0)

        // 역삼역 근처 → 강남역: 약 0.7km
        val with = poolParty.toCallSummary(driverLat = 37.5006, driverLng = 127.0364)
        assertTrue("expected 0 < d < 2, was ${with.distanceToPickupKm}", with.distanceToPickupKm in 0.1..2.0)
    }

    // ── CallDetail · ActiveTrip 매핑 ───────────────────────────────────

    @Test
    fun `CallDetail - 스톱은 픽업 1 + 하차 1`() {
        val detail = poolParty.toCallDetail()

        assertEquals(2, detail.stops.size)
        assertEquals(StopKind.PICKUP, detail.stops[0].kind)
        assertEquals("강남역 2번 출구", detail.stops[0].place)
        assertEquals(StopKind.DROPOFF, detail.stops[1].kind)
        assertEquals("판교역 1번 출구", detail.stops[1].place)
        assertEquals(DispatchRules.COUNTDOWN_SECONDS, detail.countdownSeconds)
    }

    @Test
    fun `ActiveTrip - 승객 수만큼 생성, ASSIGNED 시작`() {
        val trip = poolParty.toActiveTrip(vehicleInfoLabel = "쏘나타 34가 1234")

        assertEquals("42", trip.id)
        assertEquals(TripPhase.ASSIGNED, trip.phase)
        assertEquals(2, trip.passengers.size)
        assertEquals("승객1", trip.passengers[0].maskedName)
        assertEquals("승객2", trip.passengers[1].maskedName)
        assertFalse(trip.passengers.any { it.boarded || it.droppedOff || it.noShow })
        assertEquals(0, trip.nextStopIndex)
        assertEquals("쏘나타 34가 1234", trip.vehicleInfoLabel)
        assertEquals(DispatchRules.POOL_BONUS, trip.poolBonus)
    }

    // ── 요금 계산 ──────────────────────────────────────────────────────

    @Test
    fun `요금 확정 - 수수료 5프로 10원 단위 내림, 정산액 공식`() {
        val result = calcFareResult(meterFare = 18400, callFee = 3000, poolBonus = 1500)

        // (18400 + 3000) * 5% = 1070
        assertEquals(1070, result.serviceFee)
        assertEquals(18400 + 3000 - 1070 + 1500, result.driverPayout)
        assertEquals(18400, result.meterFare)
        assertEquals(3000, result.callFee)
        assertEquals(1500, result.poolBonus)
    }

    @Test
    fun `요금 확정 - 수수료 10원 미만 절사`() {
        // (12345 + 3000) * 5% = 767.25 → 760
        val result = calcFareResult(meterFare = 12345)
        assertEquals(760, result.serviceFee)
    }

    // ── JSON 역직렬화 방어 (서버 케이스 그대로) ─────────────────────────

    @Test
    fun `서버 JSON 필드명 그대로 역직렬화된다`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val dto = json.decodeFromString<PartySummaryDto>(
            """
            {"id":7,"departureLatitude":37.49,"departureLongitude":127.02,
             "destinationLatitude":37.39,"destinationLongitude":127.11,
             "departure":"강남역","destination":"판교역","memberCount":2,
             "estimatedFare":15000,"estimatedTime":20,"driverId":null}
            """.trimIndent(),
        )

        assertEquals(7L, dto.id)
        assertEquals("강남역", dto.departure)
        assertEquals(2, dto.memberCount)
        assertEquals(15000, dto.estimatedFare)
        assertNull(dto.driverId)
    }

    @Test
    fun `누락 필드가 있어도 역직렬화가 깨지지 않는다`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val dto = json.decodeFromString<PartySummaryDto>("""{"id":9,"unknownField":"x"}""")

        assertEquals(9L, dto.id)
        assertNull(dto.departure)
        assertNull(dto.estimatedFare)
        assertEquals(1, dto.memberCount)

        val summary = dto.toCallSummary()
        assertEquals("9", summary.id)
        assertEquals(CallType.SOLO, summary.type)
    }
}
