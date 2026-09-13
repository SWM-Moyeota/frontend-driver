package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartyDetailDto
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPhase

/**
 * 매칭방 상세(PartyDetailResult) → 복구용 ActiveTrip 변환.
 *
 * 콜 수락 경로([PartySummaryDto.toActiveTrip])와 **같은 앱 규칙**을 쓴다 — 호출료 3,000 ·
 * 합승 보너스 1,500([DispatchRules]) · 승객별 픽업/하차 스톱 · "승객N" 폴백 후 닉네임 주입.
 * 복구된 운행이 수락 직후의 운행과 다르게 보이면 기사가 다른 콜로 오해하기 때문이다.
 *
 * 응답 필드명이 dispatch 콜 상세와 다르다(`departureLat` ↔ `departureLatitude`) — DTO 주석 참고.
 */

/** 복구 판정에 쓰는 서버 파티 상태 (../backend .../matching/domain/enums/PartyStatus.java) */
object PartyStatuses {
    const val DRIVER_ASSIGNED = "DRIVER_ASSIGNED"
    const val IN_RIDE = "IN_RIDE"
}

/**
 * 서버 파티 상태 → 복구할 운행 단계. 복구 대상이 아니면 null.
 *
 * - `IN_RIDE`(탑승 완료 후) → [TripPhase.IN_TRIP] — 운행 화면(D15)
 * - `DRIVER_ASSIGNED`(배차 후 탑승 전) → [TripPhase.ASSIGNED] — 픽업 이동(D13)
 *   ※ 서버 상태로는 D11(배차 확정)과 D13(픽업 이동)을 구분할 수 없다. D11 은 스쳐 지나가는 확인
 *     화면이므로 현장 기사에게 의미 있는 D13 으로 복원한다.
 * - `FINISHED`·`CANCELED`·`MATCHING`·모집 중(ACTIVE/COMPLETED) → 복구 대상 아님(null) — 저장을 지운다.
 */
fun restorablePhase(status: String?): TripPhase? = when (status?.trim()?.uppercase()) {
    PartyStatuses.IN_RIDE -> TripPhase.IN_TRIP
    PartyStatuses.DRIVER_ASSIGNED -> TripPhase.ASSIGNED
    else -> null
}

/**
 * 진행 중이던 운행 복원.
 *
 * @param partyId 조회에 쓴 파티 id — 응답 id 가 비어도 운행 id 는 반드시 partyId 여야 한다
 *   (`startRide`·`submitFinalFare` 가 이 값을 Long 으로 파싱해 서버를 호출한다).
 * @param phase [restorablePhase] 판정 결과. [TripPhase.IN_TRIP] 이면 전원 탑승 + 다음 스톱을 첫 하차지로 둔다.
 * @param driverLat·driverLng 기사 현재 좌표 — 남은 거리/시간 계산용. ASSIGNED 는 픽업지까지,
 *   IN_TRIP 은 이미 픽업을 지났으므로 목적지까지를 남은 거리로 본다.
 */
fun PartyDetailDto.toRestoredTrip(
    partyId: Long,
    phase: TripPhase,
    vehicleInfoLabel: String,
    driverLat: Double? = null,
    driverLng: Double? = null,
): ActiveTrip {
    val pickupPlace = departure ?: "출발지 미상"
    val dropoffPlace = destination ?: "도착지 미상"
    val passengerCount = (currentMembers ?: members.size).coerceAtLeast(1)
    val type = if (passengerCount >= 2) CallType.POOL else CallType.SOLO
    val inTrip = phase == TripPhase.IN_TRIP

    val remainingKm = if (inTrip) {
        haversineKmOrZero(driverLat, driverLng, destinationLat, destinationLng)
    } else {
        haversineKmOrZero(driverLat, driverLng, departureLat, departureLng)
    }

    val passengers = buildPassengers(
        count = passengerCount,
        pickupPlace = pickupPlace,
        dropoffPlace = dropoffPlace,
        boarded = inTrip,
    )
    val stops = passengerStops(pickupPlace, dropoffPlace, passengers)

    return ActiveTrip(
        id = (id ?: partyId).toString(),
        type = type,
        phase = phase,
        passengers = passengers,
        stops = stops,
        // IN_TRIP 은 픽업 스톱을 모두 지났다 — 첫 하차 스톱이 다음 목표 (startRide 와 같은 규칙)
        nextStopIndex = if (inTrip) stops.indexOfFirst { it.kind == StopKind.DROPOFF }.coerceAtLeast(0) else 0,
        remainingKm = remainingKm,
        remainingMin = etaMinFromDistance(remainingKm),
        vehicleInfoLabel = vehicleInfoLabel,
        poolBonus = if (type == CallType.POOL) DispatchRules.POOL_BONUS else 0,
        departurePoint = geoPointOrNull(departureLat, departureLng),
        destinationPoint = geoPointOrNull(destinationLat, destinationLng),
    ).withPassengerNicknames(members.map { it.nickname })
}
