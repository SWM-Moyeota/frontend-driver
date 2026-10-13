package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.AppConfigResponseDto
import com.moyeota.driver.data.remote.dto.NicknameCheckRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckResponseDto
import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
import com.moyeota.driver.data.remote.dto.SmsSendRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyResponseDto
import com.moyeota.driver.data.remote.dto.TokenRequestDto
import com.moyeota.driver.data.remote.dto.TokenResponseDto
import com.moyeota.driver.data.remote.dto.UserLoginRequestDto
import com.moyeota.driver.data.remote.dto.UserRegisterRequestDto
import com.moyeota.driver.data.remote.dto.UserResponseDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 인가 API — ../backend .../user/{auth,local,verification}/presentation/ 기준
 * (AuthController · SmsVerificationController · LocalUserController) + `_config/AppConfigController`.
 *
 * SecurityConfig 가 열어 두는 경로는 `/api/v1/auth/` 하위 전체(문자 인증 `/auth/sms/` 포함),
 * `/api/v1/config`, `/ws-chat/` 하위 전체, `/health`, `/prometheus`, `/info`, swagger 뿐이고
 * 나머지(기사·dispatch 포함)는 `anyRequest().authenticated()` — Bearer 토큰 필수다.
 * (옛 `POST /api/v1/local/users` 가입 API 는 백엔드에서 삭제됐다 — `/auth/register` 가 유일한 가입 경로다.)
 *
 * 이 인터페이스는 **Bearer 인터셉터·401 Authenticator 가 붙지 않은 기본 클라이언트**로 만든다
 * ([NetworkModule] 참조). 재발급 요청이 401 을 맞았을 때 다시 재발급을 트리거하면 무한 루프가 된다.
 * (getMyInfo 만 예외적으로 인증이 필요하지만, 로그인 직후 방금 받은 토큰을 명시 헤더로 싣는 쪽이
 * 재귀 위험 없이 단순하다 — [RemoteDriverRepository] 가 Authorization 헤더를 직접 넘긴다.)
 */
interface AuthApi {

    /** 201 UserResponse(uuid, name). 토큰은 없다 — 가입 직후 [login] 을 이어서 호출해야 한다. 409 LOGIN_ID_DUPLICATED */
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: UserRegisterRequestDto): UserResponseDto

    /** 200 TokenResponse / 401 USER102(아이디나 비밀번호가 다릅니다) */
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: UserLoginRequestDto): TokenResponseDto

    /**
     * 200 TokenResponse / 401(만료·회전됨·로그아웃). OkHttp Authenticator 는 non-suspend 컨텍스트라
     * 동기 [Call] 로 선언한다 — runBlocking 래핑은 디스패처 포화 시 교착 위험이 있다.
     */
    @POST("api/v1/auth/reissue")
    fun reissue(@Body body: TokenRequestDto): Call<TokenResponseDto>

    /** 204 No Content, 멱등 — 이미 무효화된 refreshToken 을 보내도 성공한다 */
    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: TokenRequestDto)

    /**
     * 휴대폰 번호 기가입 조회 — 200 `{exists}` (permitAll — 가입 1단계, 토큰 없이 호출).
     * 형식 불일치는 400 INVALID_PHONE_NUMBER.
     */
    @POST("api/v1/auth/phone/check")
    suspend fun checkPhone(@Body body: PhoneCheckRequestDto): PhoneCheckResponseDto

    /**
     * 인증번호 발송 — 204 No Content (permitAll, 가입 2단계).
     * 409 PHONE_NUMBER_DUPLICATED(USER104) / 429 USER202·USER203 / 400 USER105 / 502 USER201 / 503 USER208.
     */
    @POST("api/v1/auth/sms/send")
    suspend fun sendSmsCode(@Body body: SmsSendRequestDto)

    /**
     * 인증번호 확인 — 200 `{verificationToken}` (permitAll).
     * 400 USER204(만료)·USER205(불일치) / 429 USER206(입력 횟수 초과) / 503 USER208.
     */
    @POST("api/v1/auth/sms/verify")
    suspend fun verifySmsCode(@Body body: SmsVerifyRequestDto): SmsVerifyResponseDto

    /**
     * 닉네임 중복 확인 — 200 `{exists}` (permitAll, 가입 3단계 실시간 확인).
     * 형식 위반은 400 INVALID_NICKNAME(USER107) — 호출부가 "사용 불가"로 합친다.
     */
    @POST("api/v1/auth/nickname/check")
    suspend fun checkNickname(@Body body: NicknameCheckRequestDto): NicknameCheckResponseDto

    /**
     * 서버 설정 — 200 `{taxiEnabled}`.
     *
     * SecurityConfig 가 `/api/v1/config` 를 `/api/v1/auth/` 하위와 **함께 permitAll** 로 열어 두므로
     * 토큰이 필요 없다. 그래서 별도 Retrofit 인스턴스를 두지 않고 이 인터페이스(기본 클라이언트)에 얹는다 —
     * 인증 클라이언트에 얹으면 미로그인 시작 화면에서의 호출이 Authenticator 재발급을 헛되게 트리거한다.
     */
    @GET("api/v1/config")
    suspend fun getAppConfig(): AppConfigResponseDto

    /**
     * 내 프로필 — 200 UserResponse(uuid, name). 인증 필요 구간이라 Authorization 헤더를 직접 싣는다.
     * name 은 닉네임 미설정 시 가입 시 실명(name)으로 폴백된 값이다.
     * 주의: 내부 Long userId 는 이 응답에도 없다 (백엔드 미노출).
     */
    @GET("api/v1/local/users/info")
    suspend fun getMyInfo(
        @retrofit2.http.Header("Authorization") bearer: String,
    ): UserResponseDto
}
