package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartySummaryDto
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.GeoPoint
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
    fun `CallDetail - 출발·도착 좌표를 GeoPoint 로 매핑한다`() {
        val detail = poolParty.toCallDetail()

        assertEquals(GeoPoint(37.4980, 127.0276), detail.departurePoint)
        assertEquals(GeoPoint(37.3948, 127.1112), detail.destinationPoint)
    }

    @Test
    fun `CallDetail - 좌표 미제공이면 GeoPoint 는 null`() {
        val detail = PartySummaryDto(id = 7L).toCallDetail()

        assertNull(detail.departurePoint)
        assertNull(detail.destinationPoint)
    }

    @Test
    fun `CallDetail - 위경도 중 한쪽만 있으면 null (반쪽 좌표 방어)`() {
        val detail = poolParty.copy(
            departureLongitude = null,     // 출발지: 위도만 존재
            destinationLatitude = null,    // 도착지: 경도만 존재
        ).toCallDetail()

        assertNull(detail.departurePoint)
        assertNull(detail.destinationPoint)
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

    @Test
    fun `ActiveTrip - 출발·도착 좌표를 GeoPoint 로 매핑한다`() {
        val trip = poolParty.toActiveTrip(vehicleInfoLabel = "쏘나타 34가 1234")

        assertEquals(GeoPoint(37.4980, 127.0276), trip.departurePoint)
        assertEquals(GeoPoint(37.3948, 127.1112), trip.destinationPoint)
    }

    @Test
    fun `ActiveTrip - 좌표 미제공이면 GeoPoint 는 null`() {
        val trip = PartySummaryDto(id = 7L).toActiveTrip(vehicleInfoLabel = "쏘나타 34가 1234")

        assertNull(trip.departurePoint)
        assertNull(trip.destinationPoint)
    }

    @Test
    fun `ActiveTrip - 위경도 중 한쪽만 있으면 null (반쪽 좌표 방어)`() {
        val trip = poolParty.copy(
            departureLongitude = null,     // 출발지: 위도만 존재
            destinationLatitude = null,    // 도착지: 경도만 존재
        ).toActiveTrip(vehicleInfoLabel = "쏘나타 34가 1234")

        assertNull(trip.departurePoint)
        assertNull(trip.destinationPoint)
    }

    // ── 승객 닉네임 주입 ───────────────────────────────────────────────

    @Test
    fun `닉네임 주입 - passengers 와 stops 를 순서 매칭으로 함께 교체한다`() {
        val trip = poolParty.toActiveTrip(vehicleInfoLabel = "쏘나타 34가 1234")
            .withPassengerNicknames(listOf("승객검D417", "모여타짱"))

        assertEquals(listOf("승객검D417", "모여타짱"), trip.passengers.map { it.maskedName })
        // 운행(D15) 하차 매칭은 passengerMaskedName 문자열 대응 — 스톱 이름도 같이 바뀌어야 한다
        assertEquals(
            listOf("승객검D417", "모여타짱", "승객검D417", "모여타짱"),   // 픽업 2 + 하차 2
            trip.stops.map { it.passengerMaskedName },
        )
        // 이름 외 진행 상태·순서는 그대로
        assertEquals(TripPhase.ASSIGNED, trip.phase)
        assertEquals(0, trip.nextStopIndex)
        assertFalse(trip.passengers.any { it.boarded || it.droppedOff || it.noShow })
    }

    @Test
    fun `닉네임 주입 - null·공백·부족한 목록은 해당 승객만 승객N 유지`() {
        val trip = poolParty.copy(memberCount = 3).toActiveTrip(vehicleInfoLabel = "쏘나타")
            .withPassengerNicknames(listOf(null, "  "))   // 3인인데 2개, 그마저 무효

        assertEquals(listOf("승객1", "승객2", "승객3"), trip.passengers.map { it.maskedName })

        val partial = poolParty.copy(memberCount = 3).toActiveTrip(vehicleInfoLabel = "쏘나타")
            .withPassengerNicknames(listOf("승객검D417"))

        assertEquals(listOf("승객검D417", "승객2", "승객3"), partial.passengers.map { it.maskedName })
        assertEquals(
            listOf("승객검D417", "승객2", "승객3", "승객검D417", "승객2", "승객3"),
            partial.stops.map { it.passengerMaskedName },
        )
    }

    @Test
    fun `닉네임 주입 - 빈 목록이면 원본 그대로`() {
        val original = poolParty.toActiveTrip(vehicleInfoLabel = "쏘나타")

        assertEquals(original, original.withPassengerNicknames(emptyList()))
    }

    @Test
    fun `닉네임 주입 - 중복 닉네임은 뒤 승객이 승객N 을 유지한다 (스톱 매칭 보호)`() {
        val trip = poolParty.toActiveTrip(vehicleInfoLabel = "쏘나타")
            .withPassengerNicknames(listOf("모여타짱", "모여타짱"))

        assertEquals(listOf("모여타짱", "승객2"), trip.passengers.map { it.maskedName })
        // 스톱 이름이 전부 서로 다른 승객을 가리켜야 하차 매칭이 안전하다
        assertEquals(
            listOf("모여타짱", "승객2", "모여타짱", "승객2"),
            trip.stops.map { it.passengerMaskedName },
        )
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
