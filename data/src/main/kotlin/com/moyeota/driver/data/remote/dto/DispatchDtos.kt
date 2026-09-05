package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 백엔드 dispatch 도메인 DTO — 서버 shape 그대로.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/dispatch/application/dto 하위
 *       ../backend/src/main/java/team/codingforest/moyeota/matching/api/PartySummary.java
 * (컨트롤러 소스 기준, 실서버 미검증)
 */

/** POST /api/v1/dispatch/online/{driverId} · /dispatch/location/{driverId} 요청 — LocationReportRequest */
@Serializable
data class LocationReportRequestDto(
    val latitude: Double,
    val longitude: Double,
)

/** GET /api/v1/dispatch/calls/{partyId}/status 응답 — CallStatusResponse */
@Serializable
data class CallStatusResponseDto(
    val open: Boolean = false,
)

/** GET /api/v1/dispatch/calls/{partyId}/{driverId} 응답 — matching.api.PartySummary */
@Serializable
data class PartySummaryDto(
    val id: Long? = null,
    val departureLatitude: Double? = null,
    val departureLongitude: Double? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val departure: String? = null,
    val destination: String? = null,
    val memberCount: Int = 1,
    val estimatedFare: Int? = null,      // 서버 Integer — null 가능
    val estimatedTime: Int? = null,      // 서버 Integer — null 가능 (분)
    val driverId: Long? = null,          // 배정 전 null
)

/** POST /api/v1/dispatch/rides/{partyId}/complete/{driverId} 요청 — CompleteRideRequest */
@Serializable
data class CompleteRideRequestDto(
    val fare: Int,
)

/** GET /api/v1/dispatch/rides/{partyId}/{memberId} 응답 — DriverLocationResponse (승객용 기사 위치) */
@Serializable
data class DriverLocationResponseDto(
    val longitude: Double = 0.0,
    val latitude: Double = 0.0,
)

/** 공통 4xx 응답 — common.exception.ErrorResponse */
@Serializable
data class ErrorResponseDto(
    val code: String? = null,
    val message: String? = null,
)
