package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartyDetailDto
import com.moyeota.driver.data.remote.dto.PartyMemberDto
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.GeoPoint
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 매칭방 상세 → 복구 ActiveTrip 변환 검증.
 * 복구된 운행이 콜 수락 직후의 운행([PartySummaryDto.toActiveTrip])과 같은 규칙으로 보여야 한다.
 */
class PartyRecoveryMappersTest {

    private val party = PartyDetailDto(
        id = 42,
        departureLat = 37.4980,
        departureLng = 127.0276,
        destinationLat = 37.3948,
        destinationLng = 127.1112,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        currentMembers = 2,
        status = "DRIVER_ASSIGNED",
        members = listOf(PartyMemberDto("승객검D417"), PartyMemberDto("모여타짱")),
        estimateFare = 18_400,
        estimateTime = 25,
        taxiDriverId = 7,
    )

    // ── 상태 → 단계 판정 ──────────────────────────────────────────────

    @Test
    fun `복구 대상 상태만 운행 단계로 바뀐다`() {
        assertEquals(TripPhase.IN_TRIP, restorablePhase("IN_RIDE"))
        assertEquals(TripPhase.ASSIGNED, restorablePhase("DRIVER_ASSIGNED"))

        // 끝났거나 아직 배차 전 — 복구하지 않는다
        assertNull(restorablePhase("FINISHED"))
        assertNull(restorablePhase("CANCELED"))
        assertNull(restorablePhase("MATCHING"))
        assertNull(restorablePhase("ACTIVE"))
        assertNull(restorablePhase("COMPLETED"))
        assertNull(restorablePhase(null))
        assertNull(restorablePhase(""))
    }

    // ── 배차 후 · 탑승 전 (D13) ───────────────────────────────────────

    @Test
    fun `DRIVER_ASSIGNED 는 픽업 이동 단계로 복원된다`() {
        val trip = party.toRestoredTrip(
            partyId = 42,
            phase = TripPhase.ASSIGNED,
            vehicleInfoLabel = "쏘나타 34가 1234",
            driverLat = 37.4980,
            driverLng = 127.0276,
        )

        assertEquals("42", trip.id)
        assertEquals(TripPhase.ASSIGNED, trip.phase)
        assertEquals(CallType.POOL, trip.type)
        assertEquals("아직 아무도 타지 않았다", 0, trip.passengers.count { it.boarded })
        assertEquals("다음 목표는 첫 픽업지", 0, trip.nextStopIndex)
        assertEquals(StopKind.PICKUP, trip.stops[trip.nextStopIndex].kind)
        assertEquals(DispatchRules.POOL_BONUS, trip.poolBonus)
        assertEquals("쏘나타 34가 1234", trip.vehicleInfoLabel)
    }

    @Test
    fun `좌표와 실닉네임이 그대로 복원된다`() {
        val trip = party.toRestoredTrip(42, TripPhase.ASSIGNED, "쏘나타 34가 1234")

        assertEquals(GeoPoint(37.4980, 127.0276), trip.departurePoint)
        assertEquals(GeoPoint(37.3948, 127.1112), trip.destinationPoint)
        // 같은 응답에 members 가 함께 오므로 추가 호출 없이 닉네임을 채운다 (26번 규칙 재사용)
        assertEquals(listOf("승객검D417", "모여타짱"), trip.passengers.map { it.maskedName })
        // D15 하차 매칭(문자열 대응)을 위해 스톱 이름도 함께 바뀐다
        assertEquals(
            listOf("승객검D417", "모여타짱"),
            trip.stops.filter { it.kind == StopKind.DROPOFF }.map { it.passengerMaskedName },
        )
    }

    @Test
    fun `픽업 전에는 기사 위치에서 출발지까지가 남은 거리다`() {
        // 기사가 목적지(판교) 근처에 있어도, 아직 픽업 전이면 강남역까지의 거리를 봐야 한다
        val trip = party.toRestoredTrip(42, TripPhase.ASSIGNED, "차량", driverLat = 37.3948, driverLng = 127.1112)

        assertTrue("판교 → 강남 약 18km", trip.remainingKm > 10.0)
        // 시속 30km 가정 (etaMinFromDistance 규칙 — 콜 수락 경로와 동일)
        assertEquals(Math.round(trip.remainingKm * 2).toInt(), trip.remainingMin)
    }

    // ── 운행 중 (D15) ─────────────────────────────────────────────────

    @Test
    fun `IN_RIDE 는 전원 탑승·운행 중 단계로 복원된다`() {
        val trip = party.copy(status = "IN_RIDE").toRestoredTrip(
            partyId = 42,
            phase = TripPhase.IN_TRIP,
            vehicleInfoLabel = "쏘나타 34가 1234",
            driverLat = 37.4980,
            driverLng = 127.0276,
        )

        assertEquals(TripPhase.IN_TRIP, trip.phase)
        assertTrue("서버 IN_RIDE = 파티 전원 탑승", trip.passengers.all { it.boarded })
        assertFalse("아직 아무도 내리지 않았다", trip.passengers.any { it.droppedOff })
        // 픽업 스톱은 모두 지나갔다 — startRide 와 같은 규칙
        assertEquals(2, trip.nextStopIndex)
        assertEquals(StopKind.DROPOFF, trip.stops[trip.nextStopIndex].kind)
    }

    @Test
    fun `운행 중에는 기사 위치에서 목적지까지가 남은 거리다`() {
        val trip = party.toRestoredTrip(42, TripPhase.IN_TRIP, "차량", driverLat = 37.3948, driverLng = 127.1112)

        assertEquals("이미 목적지 부근 — 픽업지(18km)가 아니다", 0.0, trip.remainingKm, 1e-9)
    }

    // ── 방어 ──────────────────────────────────────────────────────────

    @Test
    fun `인원 정보가 없으면 members 수로 폴백하고 최소 1명을 보장한다`() {
        val byMembers = party.copy(currentMembers = null).toRestoredTrip(42, TripPhase.ASSIGNED, "차량")
        assertEquals(2, byMembers.passengers.size)

        val empty = party.copy(currentMembers = null, members = emptyList())
            .toRestoredTrip(42, TripPhase.ASSIGNED, "차량")
        assertEquals("승객 0명짜리 운행 화면은 만들지 않는다", 1, empty.passengers.size)
        assertEquals(CallType.SOLO, empty.type)
        assertEquals(0, empty.poolBonus)
    }

    @Test
    fun `장소·좌표가 비어도 화면이 뜰 수 있게 폴백한다`() {
        val trip = PartyDetailDto(id = 42, currentMembers = 1, status = "DRIVER_ASSIGNED", taxiDriverId = 7)
            .toRestoredTrip(42, TripPhase.ASSIGNED, "차량")

        assertEquals("출발지 미상", trip.stops.first().place)
        assertEquals("도착지 미상", trip.stops.last().place)
        assertNull(trip.departurePoint)
        assertNull(trip.destinationPoint)
        assertEquals(0.0, trip.remainingKm, 1e-9)
    }

    @Test
    fun `응답 id 가 비어도 운행 id 는 조회에 쓴 partyId 다`() {
        // startRide·submitFinalFare 가 trip.id 를 Long 으로 파싱해 서버를 호출하므로 반드시 채워야 한다
        val trip = party.copy(id = null).toRestoredTrip(42, TripPhase.ASSIGNED, "차량")

        assertEquals("42", trip.id)
        assertEquals(42L, trip.id.toLongOrNull())
    }
}
