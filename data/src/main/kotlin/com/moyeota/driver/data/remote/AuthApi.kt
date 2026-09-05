package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
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
 * 인가 API — ../backend .../user/interfaces/{AuthController,LocalUserController}.java 기준.
 *
 * SecurityConfig 가 열어 두는 경로는 `/api/v1/auth/` 하위 전체, `POST /api/v1/local/users`,
 * `/ws-chat/` 하위 전체뿐이고 나머지(기사·dispatch 포함)는 `anyRequest().authenticated()` — Bearer 토큰 필수다.
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
     * 형식 불일치는 400 INVALID_PHONE_NUMBER. 실서버 미검증(구동 중인 서버는 구빌드 — 404).
     */
    @POST("api/v1/auth/phone/check")
    suspend fun checkPhone(@Body body: PhoneCheckRequestDto): PhoneCheckResponseDto

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
