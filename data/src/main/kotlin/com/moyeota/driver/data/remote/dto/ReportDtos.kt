package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * report 도메인 DTO — ../backend .../report/application/dto/ 의 record 필드명 그대로.
 * 컨트롤러 소스 기준(ReportController.java), 실서버는 401 응답으로 기동만 확인(미검증).
 */

/**
 * POST /api/v1/reports 요청 — ReportRequest(partyId, latitude, longitude) 전 필드 nullable.
 * 더미 트립(파티 없음)에서는 partyId 없이 위치만 남긴다. 기본값 필드는 직렬화에서 생략되며
 * 서버(Jackson)는 누락 필드를 null 로 받는다.
 */
@Serializable
data class ReportRequestDto(
    val partyId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** POST /api/v1/reports 200 응답 — ReportResponse(reportId). 누락 방어용 nullable */
@Serializable
data class ReportResponseDto(
    val reportId: Long? = null,
)

/** PATCH /api/v1/reports/call-result 요청 — CallResultRequest(@NotNull called) */
@Serializable
data class CallResultRequestDto(
    val called: Boolean,
)
