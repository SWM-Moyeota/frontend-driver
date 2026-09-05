package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.CallResultRequestDto
import com.moyeota.driver.data.remote.dto.ReportRequestDto
import com.moyeota.driver.data.remote.dto.ReportResponseDto
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST

/**
 * 긴급 신고 API — ../backend .../report/interfaces/ReportController.java 기준.
 * 전 엔드포인트 토큰 기반(@CurrentUser) — 인증 클라이언트([NetworkModule.reportApi])로 생성한다.
 */
interface ReportApi {

    /** 긴급 신고 접수 — 서버가 신고자(토큰)·파티·위치를 저장한다. 200 `{reportId}` */
    @POST("api/v1/reports")
    suspend fun report(@Body body: ReportRequestDto): ReportResponseDto

    /** 다이얼 복귀 후 실제 통화 여부 기록 — 서버가 내 최근 신고에 반영한다. 204 */
    @PATCH("api/v1/reports/call-result")
    suspend fun confirmCallResult(@Body body: CallResultRequestDto)
}
