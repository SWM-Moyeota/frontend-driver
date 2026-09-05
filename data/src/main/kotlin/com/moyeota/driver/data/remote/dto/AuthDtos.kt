package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 백엔드 인가(auth) DTO — 서버 shape 그대로.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/user/{interfaces,application/dto} (소스 기준).
 * - AuthController: POST /api/v1/auth/{register,login,reissue,logout}
 * - LocalUserController: GET /api/v1/local/users/info
 */

/**
 * POST /api/v1/auth/register 요청 — UserRegisterRequest.
 * 서버 @Pattern 검증:
 * - loginId `^[a-z][a-z0-9_]{3,19}$`
 * - password 8~64자 + 영문·숫자·특수문자 각 1개 이상
 * - phoneNumber `^01[016-9]-?\d{3,4}-?\d{4}$`
 * - birthDate 는 서버가 Instant 로 받으므로 ISO-8601 문자열(과거 시각)로 보낸다
 * - gender: MALE | FEMALE
 */
@Serializable
data class UserRegisterRequestDto(
    val loginId: String,
    val password: String,
    val name: String,
    val birthDate: String,
    val phoneNumber: String,
    val gender: String,
    val email: String,
)

/**
 * POST /api/v1/auth/register (201) · GET /api/v1/local/users/info (200) 응답 — UserResponse.
 * 주의: 내부 Long userId 는 **어떤 응답에도 없다**. uuid 는 publicId(토큰 sub 와 동일)다.
 * 가입 직후에는 nickname 미설정이라 register 응답의 name 이 null 일 수 있다.
 */
@Serializable
data class UserResponseDto(
    val uuid: String? = null,
    val name: String? = null,
)

/** POST /api/v1/auth/login 요청 — UserLoginRequest */
@Serializable
data class UserLoginRequestDto(
    val loginId: String,
    val password: String,
)

/** POST /api/v1/auth/{reissue,logout} 요청 — TokenRequest */
@Serializable
data class TokenRequestDto(
    val refreshToken: String,
)

/** POST /api/v1/auth/{login,reissue} 응답 — TokenResponse. 회전 방식이라 refreshToken 도 매번 갱신된다 */
@Serializable
data class TokenResponseDto(
    val accessToken: String = "",
    val refreshToken: String = "",
)

/**
 * POST /api/v1/auth/phone/check 요청 — PhoneCheckRequest (permitAll).
 * 서버는 하이픈을 제거해 정규화(PhoneNumber record)하므로 하이픈 유무는 무관하지만,
 * 형식 불일치(`^01[016-9]\d{7,8}$` 실패)는 400 INVALID_PHONE_NUMBER 다.
 */
@Serializable
data class PhoneCheckRequestDto(
    val phoneNumber: String,
)

/** POST /api/v1/auth/phone/check 응답 — PhoneCheckResponse */
@Serializable
data class PhoneCheckResponseDto(
    val exists: Boolean = false,
)
