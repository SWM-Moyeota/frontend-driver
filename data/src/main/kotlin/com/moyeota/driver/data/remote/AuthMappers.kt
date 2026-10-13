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
        nickname = nickname.trim(),
        name = name,
        birthDate = MvpProfileDefaults.BIRTH_DATE,
        phoneNumber = normalizePhoneNumber(phoneNumber),
        gender = MvpProfileDefaults.GENDER,
        email = "$loginId@${MvpProfileDefaults.EMAIL_DOMAIN}",
        // 서버가 토큰의 전화번호 해시와 이 phoneNumber 를 대조하므로, 둘은 같은 정규화를 거쳐야 한다
        // (문자 인증 요청도 normalizePhoneNumber 로 보낸다 — 서버가 하이픈을 제거해 해시하니 결과는 동일하다)
        verificationToken = verificationToken,
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
 * HttpException 의 에러 body 를 `{code, message}` 로 파싱한다. 형식이 다르거나 body 가 없으면 null.
 *
 * **errorBody 는 1회성이라 한 예외에 대해 이 함수(또는 [serverMessage]·[errorCode])를 단 한 번만
 * 호출할 수 있다.** code 와 message 를 모두 써야 하는 분기에서는 이 함수를 한 번 불러
 * 결과를 재사용한다 — [serverMessage] 를 부른 뒤 [errorCode] 를 부르면 두 번째는 null 이다.
 *
 * code 의 정체는 백엔드 도메인마다 다르다 (common/exception/ErrorResponse.java):
 * - user 도메인: `USER101` 식의 고정 코드 (UserErrorCode 가 customCode 를 갖는다)
 * - driver·dispatch·matching 도메인: enum 이름 그대로 (`DRIVER_NOT_REGISTERED`, `CALL_CLOSED` …)
 * - 요청 검증 실패: `INVALID_REQUEST`
 * - **매핑되지 않은 경로(구서버·잘못된 baseUrl)의 404 는 Spring 기본 body 라 code 가 없다** → null.
 *   이 구분이 [ServerErrorCodes.isDriverMissing] 류 판정의 존재 이유다.
 */
fun retrofit2.HttpException.serverError(): ErrorResponseDto? =
    runCatching {
        response()?.errorBody()?.string()
            ?.takeIf { it.isNotBlank() }
            ?.let { errorJson.decodeFromString(ErrorResponseDto.serializer(), it) }
    }.getOrNull()

/**
 * 서버 한국어 message 만 꺼낸다. 형식이 다르면 null.
 * errorBody 1회성 제약은 [serverError] 와 같다.
 */
fun retrofit2.HttpException.serverMessage(): String? =
    serverError()?.message?.takeIf { it.isNotBlank() }

/**
 * 서버 에러 code 만 꺼낸다. body 가 없거나 code 가 없으면(= 매핑되지 않은 경로의 404 등) null.
 * errorBody 1회성 제약은 [serverError] 와 같다.
 */
fun retrofit2.HttpException.errorCode(): String? =
    serverError()?.code?.takeIf { it.isNotBlank() }

/**
 * 기사 앱이 **분기에 쓰는** 서버 에러 코드.
 *
 * 상태코드만으로는 판정할 수 없는 경우에만 코드를 본다. 특히 404 는 두 가지가 섞여 있다 —
 * 서버가 의도적으로 돌려준 "없음"(code 있음)과, 경로 자체가 없는 404(code 없음)다.
 * 후자를 전자로 오독하면 저장된 세션·운행을 멋대로 지운다.
 */
object ServerErrorCodes {
    // driver 도메인 (DriverErrorCode — code = enum 이름)
    const val DRIVER_NOT_REGISTERED = "DRIVER_NOT_REGISTERED"
    const val DRIVER_NOT_FOUND = "DRIVER_NOT_FOUND"

    // matching·dispatch 도메인
    const val PARTY_NOT_FOUND = "PARTY_NOT_FOUND"

    // user 도메인 (UserErrorCode — customCode)
    const val LOGIN_ID_DUPLICATED = "USER101"
    const val PHONE_NUMBER_DUPLICATED = "USER104"
    const val INVALID_PHONE_NUMBER = "USER105"
    const val INVALID_NICKNAME = "USER107"
    const val NICKNAME_DUPLICATED = "USER108"
    const val SMS_NOT_VERIFIED = "USER207"

    /**
     * "이 계정에는 기사 레코드가 없다"가 **확인된** 404인가.
     *
     * `GET /api/v1/drivers/me` 가 미등록 유저에게 주는 코드는 [DRIVER_NOT_REGISTERED] 다.
     * 이 코드일 때만 확인된 종료로 보고 저장된 세션을 지운다.
     *
     * 나머지 404 는 전부 "모름"이다:
     * - code 가 없는 404 → 핸들러 자체가 없다. 서버가 `moyeota.taxi.enabled=false` 로 기사·배차
     *   컨트롤러를 등록하지 않았거나(@ConditionalOnProperty), 구버전 배포이거나, baseUrl 이 틀렸다.
     *   배포 서버가 실제로 이 상태라 — 이걸 미등록으로 읽으면 **매 실행 로그아웃**된다.
     * - 다른 code 의 404 → 기사 레코드 존재 여부를 말해 주지 않는다.
     */
    fun isDriverMissing(code: String?): Boolean = code == DRIVER_NOT_REGISTERED

    /** "그 파티는 서버에 없다"가 확인된 404인가 — 저장된 활성 partyId 를 지워도 되는 유일한 경우 */
    fun isPartyMissing(code: String?): Boolean = code == PARTY_NOT_FOUND
}

/**
 * 인가·문자 인증 실패를 사용자에게 보여줄 한국어 한 줄로 바꾼다.
 *
 * **서버 message 를 최우선으로 쓴다** — 백엔드 ErrorCode 의 message 는 이미 사용자 노출용 한국어이고
 * (예: "인증번호는 1분에 한 번만 요청할 수 있습니다."), 클라이언트에 같은 문구를 복제하면
 * 서버가 정책을 바꿀 때 두 벌이 어긋난다. 코드는 **분기**에만 쓰고 문구는 서버를 따른다.
 *
 * body 가 비어 있을 때(프록시 502·게이트웨이 타임아웃 등)만 상태코드별 폴백 문구를 쓴다.
 */
fun authFailureMessage(error: ErrorResponseDto?, httpCode: Int, fallback: String): String {
    error?.message?.takeIf { it.isNotBlank() }?.let { return it }
    return when (httpCode) {
        409 -> "이미 가입된 정보예요 · 로그인해 주세요"
        429 -> "요청이 너무 많아요 · 잠시 후 다시 시도해 주세요"
        502, 503 -> "지금은 인증을 진행할 수 없어요 · 잠시 후 다시 시도해 주세요"
        else -> "$fallback · 서버 오류($httpCode)"
    }
}
