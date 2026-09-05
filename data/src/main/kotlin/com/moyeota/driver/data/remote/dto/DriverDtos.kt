package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 백엔드 driver 도메인 DTO — 서버 shape 그대로.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/driver/application/dto 하위 (컨트롤러 소스 기준, 실서버 미검증)
 */

/**
 * POST /api/v1/drivers 요청 — RegisterDriverRequest.
 * userId 는 body 에 없다 — 서버가 토큰(@CurrentUser)에서 해석한다.
 * 차량 정보(vehicle)가 등록 요청에 통합됐다 (@Valid @NotNull — 필수).
 */
@Serializable
data class RegisterDriverRequestDto(
    val qualificationNumber: String,
    val bankName: String,
    val accountNumber: String,
    val vehicle: VehicleInfoDto,
) {
    /** RegisterDriverRequest.VehicleInfo — seats @Min(2) */
    @Serializable
    data class VehicleInfoDto(
        val seats: Int,
        val plateNumber: String,
        val type: String,
    )
}

/** POST /api/v1/drivers · GET /api/v1/drivers/me 응답 — DriverResult */
@Serializable
data class DriverResultDto(
    val id: Long? = null,
    val userId: Long? = null,
    val status: String? = null,          // PENDING | VERIFIED
    val callEnabled: Boolean = false,
)

/** POST /api/v1/drivers/vehicle 요청 — RegisterVehicleRequest (토큰 기반 @CurrentDriver) */
@Serializable
data class RegisterVehicleRequestDto(
    val seats: Int,
    val plateNumber: String,
    val type: String,
)

/** PUT /api/v1/drivers/fcm-token 요청 — RegisterFcmTokenRequest (토큰 기반 @CurrentDriver) */
@Serializable
data class RegisterFcmTokenRequestDto(
    val token: String,
)
