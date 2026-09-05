package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.RegisterDriverRequestDto
import com.moyeota.driver.data.remote.dto.RegisterFcmTokenRequestDto
import com.moyeota.driver.data.remote.dto.RegisterVehicleRequestDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * driver 도메인 API — ../backend .../driver/interfaces/DriverController.java 기준
 * (작업트리 신스펙 — **전 엔드포인트 토큰 기반**, path driverId 없음. 실서버 미검증: 구빌드 구동 중).
 *
 * - `@CurrentUser`(register·me): 토큰의 유저로 동작 — 클라이언트가 userId 를 보낼 필요가 없다.
 * - `@CurrentDriver`(나머지): 토큰 유저의 기사 레코드로 동작 — 미등록이면 404 DRIVER_NOT_REGISTERED.
 * - 204 No Content 응답은 Unit 반환으로 받는다.
 */
interface DriverApi {

    /**
     * 기사 등록 — 차량 정보가 body 에 통합됐다(별도 vehicle 호출 불필요).
     * 200 DriverResult / 409 DRIVER_ALREADY_REGISTERED
     */
    @POST("api/v1/drivers")
    suspend fun register(@Body body: RegisterDriverRequestDto): DriverResultDto

    /** 기사 자격 검증 — 204 / 404 DRIVER_NOT_REGISTERED / 409 DRIVER_NOT_PENDING(이미 검증됨) */
    @POST("api/v1/drivers/verify")
    suspend fun verify()

    /** 차량 등록(교체) — 204. register 에 통합돼 가입 플로우에선 불필요, 차량 변경(레거시 D04)용 */
    @POST("api/v1/drivers/vehicle")
    suspend fun registerVehicle(@Body body: RegisterVehicleRequestDto)

    /** 콜 수신 켜기 (driver.setting.callEnabled = true) — 204 */
    @POST("api/v1/drivers/call")
    suspend fun enableCall()

    /** 콜 수신 끄기 — 204 */
    @DELETE("api/v1/drivers/call")
    suspend fun disableCall()

    /**
     * 내 기사 정보 — 앱 진입 분기용. 200 DriverResult / 404 DRIVER_NOT_REGISTERED(기사 미등록).
     * dispatch API 는 여전히 path driverId 를 쓰므로 이 응답의 id 를 캐시한다.
     */
    @GET("api/v1/drivers/me")
    suspend fun getMe(): DriverResultDto

    @PUT("api/v1/drivers/fcm-token")
    suspend fun registerFcmToken(@Body body: RegisterFcmTokenRequestDto)

    @DELETE("api/v1/drivers/fcm-token")
    suspend fun removeFcmToken()
}
