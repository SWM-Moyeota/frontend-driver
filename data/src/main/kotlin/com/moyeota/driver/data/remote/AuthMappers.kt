package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.UserRegisterRequestDto
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.model.DriverSignUpForm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 서버 DriverStatus → 도메인 DriverAccountStatus.
 * VERIFIED → APPROVED(즉시 영업 가능), 그 외(PENDING·미상) → PENDING_REVIEW(심사 대기).
 */
fun driverAccountStatus(status: String?): DriverAccountStatus =
    if (status == "VERIFIED") DriverAccountStatus.APPROVED else DriverAccountStatus.PENDING_REVIEW

/**
 * 휴대폰 번호를 하이픈 표준형(010-1234-5678 / 011-123-4567)으로 정규화한다.
 * 서버는 하이픈을 제거해 저장·비교(PhoneNumber record)하므로 형식 자체는 자유지만,
 * 가입(register)과 기가입 조회(phone/check)가 **같은 정규화**를 거치도록 데이터 계층에서 일원화한다
 * — 포맷 차이로 조회는 미등록·가입은 중복(409)이 되는 오탐을 막는다.
 * 10·11자리가 아닌 입력은 숫자만 남겨 그대로 보낸다(서버 400 검증에 맡김).
 */
fun normalizePhoneNumber(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when (digits.length) {
        11 -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
        10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
        else -> digits
    }
}

/**
 * MVP 간소 가입 — 화면이 받지 않는 프로필 항목은 백엔드 @Pattern 을 통과하는 고정 기본값으로 채운다
 * (_workspace/01_contract.md 인가 계약). 값과 서버 검증 규칙의 대응은 [MvpProfileDefaults] 참조.
 * phoneNumber 는 [normalizePhoneNumber] 로 정규화 — phone/check 조회와 같은 형식을 보장한다.
 */
fun DriverSignUpForm.toRegisterRequest(): UserRegisterRequestDto =
    UserRegisterRequestDto(
        loginId = loginId,
        password = password,
        name = name,
        birthDate = MvpProfileDefaults.BIRTH_DATE,
        phoneNumber = normalizePhoneNumber(phoneNumber),
        gender = MvpProfileDefaults.GENDER,
        email = "$loginId@${MvpProfileDefaults.EMAIL_DOMAIN}",
    )

/** MVP 가입 기본값 — 각 값은 UserRegisterRequest 의 서버 검증(@Pattern·@Past·@Email)을 통과해야 한다 */
object MvpProfileDefaults {
    /** @NotBlank, max 20 */
    const val NAME = "기사"

    /** Instant(@Past) — ISO-8601 고정 과거 시각 */
    const val BIRTH_DATE = "1990-01-01T00:00:00Z"

    /** Gender enum: MALE | FEMALE */
    const val GENDER = "MALE"

    /** @Email — `{loginId}@moyeota.local` 형태로 계정마다 유일 */
    const val EMAIL_DOMAIN = "moyeota.local"
}

/** 백엔드 공통 4xx body — common/exception/ErrorResponse.java `{code, message}` (message 는 한국어) */
@Serializable
data class ErrorResponseDto(
    val code: String? = null,
    val message: String? = null,
)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * HttpException 의 에러 body 에서 서버 한국어 message 를 꺼낸다. 형식이 다르면 null.
 * errorBody 는 1회성 스트림이라 이 함수도 호출당 1회만 유효하다.
 */
fun retrofit2.HttpException.serverMessage(): String? =
    runCatching {
        response()?.errorBody()?.string()
            ?.let { errorJson.decodeFromString(ErrorResponseDto.serializer(), it) }
            ?.message
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
