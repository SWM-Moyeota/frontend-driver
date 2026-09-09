package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartySummaryDto
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallDetail
import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.FareResult
import com.moyeota.driver.domain.model.RouteStop
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPassenger
import com.moyeota.driver.domain.model.TripPhase
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PartySummary(서버 콜/파티 응답) → 도메인 변환.
 *
 * 서버가 주지 않는 값의 채움 규칙 (더미와 동일한 앱 규칙 — 산출물에 기록):
 * - callFee: 서버 미제공 → 3,000원 고정
 * - poolBonus: 서버 미제공 → 합승(2인 이상)이면 1,500원, 단독이면 0
 * - distanceToPickupKm: 기사 최근 위치 ↔ 출발지 하버사인 거리 (기사 위치 미상이면 0.0)
 * - 승객 이름: 서버 미제공 → "승객1", "승객2" …
 * - CallSummary.id = partyId.toString() (partyId ↔ 콜 id 대응)
 */
object DispatchRules {
    const val CALL_FEE = 3_000
    const val POOL_BONUS = 1_500
    const val SERVICE_FEE_PERCENT = 5
    const val COUNTDOWN_SECONDS = 15
}

fun PartySummaryDto.toCallSummary(driverLat: Double? = null, driverLng: Double? = null): CallSummary {
    val type = if (memberCount >= 2) CallType.POOL else CallType.SOLO
    val distanceKm = haversineKmOrZero(driverLat, driverLng, departureLatitude, departureLongitude)
    return CallSummary(
        // 서버 파티 id 는 항상 존재해야 한다 — null 을 "0" 으로 뭉개면 partyId=0 으로 remote 경로를 타므로 즉시 실패시킨다
        id = checkNotNull(id) { "파티 id 누락 — 서버 응답 확인 필요" }.toString(),
        type = type,
        pickupPlace = departure ?: "출발지 미상",
        dropoffPlace = destination ?: "도착지 미상",
        distanceToPickupKm = distanceKm,
        expectedFare = estimatedFare ?: 0,
        callFee = DispatchRules.CALL_FEE,
        poolBonus = if (type == CallType.POOL) DispatchRules.POOL_BONUS else 0,
        passengerCount = memberCount.coerceAtLeast(1),
        createdAtLabel = "방금 전",
    )
}

/**
 * CALL_OPENED 푸시 데이터 → **임시** CallSummary.
 *
 * 푸시에는 출발지·도착지가 항상 실려 오고, 인원(memberCount)·예상 요금(estimatedFare)은 백엔드 버전에 따라
 * 없을 수 있다. 없는 값을 앱 규칙으로 지어내면(호출료 3,000 · 보너스 1,500) "예상 수익 4,500원" 같은
 * 거짓 금액이 뜨므로, 요금을 모를 때는 금액 계열을 전부 0 으로 두고 화면이 "확인 중"으로 표시하게 한다.
 *
 * - 인원 미상: passengerCount = 0 ([CallSummary.hasPassengerCount] false) → 화면은 "합승"만 표기
 * - 콜 종류: 인원이 1명으로 명시된 경우에만 SOLO, 미상·2명 이상은 합승 콜(POOL)로 본다 (파티 콜이 기본)
 * - 픽업 거리: 푸시에 좌표가 없어 0.0 (화면은 0 이면 거리 줄을 감춘다)
 */
fun pushCallSummary(
    partyId: String,
    departure: String?,
    destination: String?,
    memberCount: Int?,
    estimatedFare: Int?,
): CallSummary {
    val type = if (memberCount != null && memberCount < 2) CallType.SOLO else CallType.POOL
    val fareKnown = estimatedFare != null && estimatedFare > 0
    return CallSummary(
        id = partyId,
        type = type,
        pickupPlace = departure?.takeIf { it.isNotBlank() } ?: "출발지 미상",
        dropoffPlace = destination?.takeIf { it.isNotBlank() } ?: "도착지 미상",
        distanceToPickupKm = 0.0,
        expectedFare = if (fareKnown) estimatedFare else 0,
        callFee = if (fareKnown) DispatchRules.CALL_FEE else 0,
        poolBonus = if (fareKnown && type == CallType.POOL) DispatchRules.POOL_BONUS else 0,
        passengerCount = memberCount?.coerceAtLeast(0) ?: 0,
        createdAtLabel = "방금 전",
        provisional = true,
    )
}

fun PartySummaryDto.toCallDetail(driverLat: Double? = null, driverLng: Double? = null): CallDetail {
    val summary = toCallSummary(driverLat, driverLng)
    return CallDetail(
        summary = summary,
        stops = toStops(),
        countdownSeconds = DispatchRules.COUNTDOWN_SECONDS,
        etaToPickupMin = etaMinFromDistance(summary.distanceToPickupKm),
    )
}

fun PartySummaryDto.toActiveTrip(
    vehicleInfoLabel: String,
    driverLat: Double? = null,
    driverLng: Double? = null,
): ActiveTrip {
    val summary = toCallSummary(driverLat, driverLng)
    val passengers = List(summary.passengerCount) { i ->
        TripPassenger(
            id = "p-$i",
            maskedName = "승객${i + 1}",
            pickupPlace = summary.pickupPlace,
            dropoffPlace = summary.dropoffPlace,
            boarded = false,
            droppedOff = false,
            noShow = false,
        )
    }
    return ActiveTrip(
        id = summary.id,
        type = summary.type,
        phase = TripPhase.ASSIGNED,
        passengers = passengers,
        // 운행 화면은 스톱의 passengerMaskedName 으로 승객을 찾아 하차 처리한다 —
        // 집계 표기("승객 2인")를 쓰면 매칭이 실패해 하차 버튼이 영영 비활성이 되므로 승객별 스톱을 만든다.
        stops = toPassengerStops(passengers),
        nextStopIndex = 0,
        remainingKm = summary.distanceToPickupKm,
        remainingMin = etaMinFromDistance(summary.distanceToPickupKm),
        vehicleInfoLabel = vehicleInfoLabel,
        poolBonus = summary.poolBonus,
    )
}

/**
 * 운행용 스톱 — 승객 1명당 픽업 1 + 하차 1.
 * 서버 파티는 출발지·도착지가 각 1곳(합승 전원이 같은 구간)이지만, 운행 화면(D15)이
 * 하차 스톱 ↔ 승객을 [RouteStop.passengerMaskedName] 으로 연결하므로 승객 수만큼 펼친다.
 * 픽업 스톱이 모두 앞, 하차 스톱이 모두 뒤 — nextStopIndex 진행 순서와 일치한다.
 */
private fun PartySummaryDto.toPassengerStops(passengers: List<TripPassenger>): List<RouteStop> {
    val pickup = departure ?: "출발지 미상"
    val dropoff = destination ?: "도착지 미상"
    val pickups = passengers.mapIndexed { index, passenger ->
        RouteStop(order = index, kind = StopKind.PICKUP, place = pickup, passengerMaskedName = passenger.maskedName)
    }
    val dropoffs = passengers.mapIndexed { index, passenger ->
        RouteStop(
            order = passengers.size + index,
            kind = StopKind.DROPOFF,
            place = dropoff,
            passengerMaskedName = passenger.maskedName,
        )
    }
    return pickups + dropoffs
}

/** 콜 상세(D10) 표기용 스톱 — 출발/도착 각 1행, 합승은 인원을 묶어 보여준다 */
private fun PartySummaryDto.toStops(): List<RouteStop> {
    val name = if (memberCount >= 2) "승객 ${memberCount}인" else "승객1"
    return listOf(
        RouteStop(order = 0, kind = StopKind.PICKUP, place = departure ?: "출발지 미상", passengerMaskedName = name),
        RouteStop(order = 1, kind = StopKind.DROPOFF, place = destination ?: "도착지 미상", passengerMaskedName = name),
    )
}

/**
 * 요금 확정 로컬 계산 — 서버 complete 는 fare 만 받고 정산 내역을 돌려주지 않는다.
 * 기사 정산액 = (미터기 + 호출료) − 서비스 수수료(5%, 10원 단위 내림) + 합승 보너스
 */
fun calcFareResult(meterFare: Int, callFee: Int = DispatchRules.CALL_FEE, poolBonus: Int = 0): FareResult {
    val serviceFee = (meterFare + callFee) * DispatchRules.SERVICE_FEE_PERCENT / 100 / 10 * 10
    return FareResult(
        meterFare = meterFare,
        callFee = callFee,
        serviceFee = serviceFee,
        poolBonus = poolBonus,
        driverPayout = meterFare + callFee - serviceFee + poolBonus,
    )
}

/** 거리(km) → 픽업 ETA(분). 시속 30km 가정, 최소 1분. 거리 미상(0)은 5분 */
internal fun etaMinFromDistance(distanceKm: Double): Int =
    if (distanceKm <= 0.0) 5 else (distanceKm * 2).roundToInt().coerceAtLeast(1)

internal fun haversineKmOrZero(lat1: Double?, lng1: Double?, lat2: Double?, lng2: Double?): Double {
    if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return 0.0
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    val km = 2 * earthRadiusKm * asin(sqrt(a))
    return (km * 10).roundToInt() / 10.0   // 0.1km 단위
}
