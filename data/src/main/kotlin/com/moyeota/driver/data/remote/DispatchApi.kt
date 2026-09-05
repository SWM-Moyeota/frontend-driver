package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.PartySummaryDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * dispatch 도메인 API — ../backend .../dispatch/interfaces/{DriverLocationController,DispatchCallController,RideController}.java
 * 기준. 신스펙: driverId 는 전부 토큰(@CurrentDriver)으로 해석 — 경로·쿼리에서 제거, partyId 만 path 로 남는다.
 * 204 No Content 응답은 Unit 반환으로 받는다.
 */
interface DispatchApi {

    // ── 영업 상태 · 위치 (DriverLocationController) ──────────────────────

    @POST("api/v1/dispatch/online")
    suspend fun goOnline(@Body body: LocationReportRequestDto)

    @DELETE("api/v1/dispatch/online")
    suspend fun goOffline()

    @POST("api/v1/dispatch/location")
    suspend fun reportLocation(@Body body: LocationReportRequestDto)

    // ── 콜 (DispatchCallController) ─────────────────────────────────────

    @POST("api/v1/dispatch/calls/{partyId}/accept")
    suspend fun acceptCall(@Path("partyId") partyId: Long)

    @POST("api/v1/dispatch/calls/{partyId}/reject")
    suspend fun rejectCall(@Path("partyId") partyId: Long)

    @GET("api/v1/dispatch/calls/{partyId}/status")
    suspend fun callStatus(@Path("partyId") partyId: Long): CallStatusResponseDto

    /** 콜 상세 — 해당 기사가 콜 후보일 때만 200, 아니면 409 CALL_CLOSED */
    @GET("api/v1/dispatch/calls/{partyId}")
    suspend fun getPartyDetail(@Path("partyId") partyId: Long): PartySummaryDto

    // ── 운행 (RideController) ───────────────────────────────────────────

    @POST("api/v1/dispatch/rides/{partyId}/arrive")
    suspend fun arrive(@Path("partyId") partyId: Long)

    @POST("api/v1/dispatch/rides/{partyId}/board")
    suspend fun board(@Path("partyId") partyId: Long)

    @POST("api/v1/dispatch/rides/{partyId}/complete")
    suspend fun complete(
        @Path("partyId") partyId: Long,
        @Body body: CompleteRideRequestDto,
    )
}
