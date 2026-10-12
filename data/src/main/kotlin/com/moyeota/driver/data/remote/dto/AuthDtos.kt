package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 백엔드 인가(auth) DTO — 서버 shape 그대로.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/user/{auth,local,verification}/{presentation,dto} (소스 기준).
 * - AuthController: POST /api/v1/auth/{register,login,reissue,logout,phone/check,nickname/check}
 * - SmsVerificationController: POST /api/v1/auth/sms/{send,verify}
 * - LocalUserController: GET /api/v1/local/users/info
 * - AppConfigController: GET /api/v1/config
 *
 * 패키지 경로 주의: 백엔드가 `user/interfaces` → `user/{auth,local,verification}/presentation` 으로
 * 재배치됐다(구 주석의 경로는 더 이상 없다). 와이어 shape 자체는 그대로다.
 */

/**
 * POST /api/v1/auth/register 요청 — UserRegisterRequest.
 * 서버 @Pattern 검증:
 * - loginId `^[a-z][a-z0-9_]{3,19}$`
 * - password 8~64자 + 영문·숫자·특수문자 각 1개 이상
 * - phoneNumber `^01[016-9]-?\d{3,4}-?\d{4}$`
 * - birthDate 는 서버가 Instant 로 받으므로 ISO-8601 문자열(과거 시각)로 보낸다
 * - gender: MALE | FEMALE
 * - verificationToken @NotBlank — 문자 인증을 거치지 않으면 가입 자체가 불가능하다
 */
@Serializable
data class UserRegisterRequestDto(
    val loginId: String,
    val password: String,
    val nickname: String,
    val name: String,
    val birthDate: String,
    val phoneNumber: String,
    val gender: String,
    val email: String,
    /**
     * POST /api/v1/auth/sms/verify 응답의 토큰. 서버는 이 토큰에 묶인 전화번호 해시와
     * 요청 phoneNumber 가 **같은지까지** 확인하므로(SmsVerificationService.checkVerified),
     * 인증한 번호와 가입 번호가 어긋나면 400 USER207 이다.
     */
    val verificationToken: String,
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

/**
 * POST /api/v1/auth/sms/send 요청 — SmsSendRequest (permitAll, 204 No Content).
 * 서버 @Pattern `^01[016-9]-?\d{3,4}-?\d{4}$` — [com.moyeota.driver.data.remote.normalizePhoneNumber] 가 통과형을 만든다.
 *
 * 서버는 문자 비용을 쓰기 전에 **이미 가입된 번호를 409 USER104 로 먼저 거절**한다
 * (SmsVerificationService.send). 그 밖에 429 USER202(재전송 1분 대기)·429 USER203(일일 5회)·
 * 502 USER201(발송 실패)·503 USER208(인증 불가)이 가능하다.
 */
@Serializable
data class SmsSendRequestDto(
    val phoneNumber: String,
)

/**
 * POST /api/v1/auth/sms/verify 요청 — SmsVerifyRequest (permitAll).
 * code 는 서버 @Pattern `^\d{6}$` — 숫자 6자리. 코드 TTL 3분, 비교 시도 5회 제한.
 */
@Serializable
data class SmsVerifyRequestDto(
    val phoneNumber: String,
    val code: String,
)

/**
 * POST /api/v1/auth/sms/verify 응답 — SmsVerifyResponse.
 * 서버는 UUID 문자열을 돌려주고 10분간 보관한다(VERIFIED_TTL). 가입 1회에만 유효(consume).
 * 필드 누락 방어로 기본값 "" — 호출부가 공백 토큰을 실패로 처리한다.
 */
@Serializable
data class SmsVerifyResponseDto(
    val verificationToken: String = "",
)

/**
 * POST /api/v1/auth/nickname/check 요청 — NicknameCheckRequest (permitAll, @NotBlank).
 * 서버는 공백을 제거해 비교하므로 클라이언트도 trim 해 보낸다.
 */
@Serializable
data class NicknameCheckRequestDto(
    val nickname: String,
)

/** POST /api/v1/auth/nickname/check 응답 — NicknameCheckResponse */
@Serializable
data class NicknameCheckResponseDto(
    val exists: Boolean = false,
)

/**
 * GET /api/v1/config 응답 — AppConfigResponse (permitAll, 토큰 불필요).
 * 기본값 true: 필드가 없는 구서버 응답을 "서비스 열림"으로 읽어 기사를 앱 밖에 가두지 않는다.
 */
@Serializable
data class AppConfigResponseDto(
    val taxiEnabled: Boolean = true,
)
