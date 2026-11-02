package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.AppConfigResponseDto
import com.moyeota.driver.data.remote.dto.NicknameCheckRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckResponseDto
import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
import com.moyeota.driver.data.remote.dto.SmsSendRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyResponseDto
import com.moyeota.driver.data.remote.dto.TokenResponseDto
import com.moyeota.driver.data.remote.dto.UserLoginRequestDto
import com.moyeota.driver.data.remote.dto.UserRegisterRequestDto
import com.moyeota.driver.data.remote.dto.UserResponseDto
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.model.DriverSignUpForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class AuthMappersTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val form = DriverSignUpForm(
        phoneNumber = "01012345678",
        name = "박기사",
        nickname = "번개기사",
        loginId = "driver01",
        password = "test123!@",
        vehicleType = "쏘나타",
        seats = 4,
        plateNumber = "34가 1234",
        qualificationNumber = "서울-2024-001234",
        verificationToken = "3f2b8c1e-7a44-4b0d-9c21-5e6f70818293",
    )

    // ── DriverStatus → DriverAccountStatus ─────────────────────────────────

    @Test
    fun `VERIFIED 는 APPROVED 로 매핑된다`() {
        assertEquals(DriverAccountStatus.APPROVED, driverAccountStatus("VERIFIED"))
    }

    @Test
    fun `PENDING 과 미상 값은 PENDING_REVIEW 로 매핑된다`() {
        assertEquals(DriverAccountStatus.PENDING_REVIEW, driverAccountStatus("PENDING"))
        assertEquals(DriverAccountStatus.PENDING_REVIEW, driverAccountStatus(null))
        assertEquals(DriverAccountStatus.PENDING_REVIEW, driverAccountStatus("UNKNOWN_FUTURE"))
    }

    // ── 가입 요청 매핑 (MVP 기본값) ─────────────────────────────────────────

    @Test
    fun `가입 요청은 폼 값과 MVP 기본값으로 채워진다`() {
        val request = form.toRegisterRequest()

        assertEquals("driver01", request.loginId)
        assertEquals("test123!@", request.password)
        assertEquals("박기사", request.name)
        assertEquals(MvpProfileDefaults.BIRTH_DATE, request.birthDate)
        // 화면이 숫자만 전달해도 하이픈 표준형으로 정규화된다 (phone/check 조회와 동일 규칙)
        assertEquals("010-1234-5678", request.phoneNumber)
        assertEquals(MvpProfileDefaults.GENDER, request.gender)
        assertEquals("driver01@moyeota.local", request.email)
    }

    // ── 휴대폰 번호 정규화 (register ↔ phone/check 일원화) ──────────────────

    @Test
    fun `11자리·10자리 번호는 하이픈 표준형으로 정규화된다`() {
        assertEquals("010-1234-5678", normalizePhoneNumber("01012345678"))
        assertEquals("010-1234-5678", normalizePhoneNumber("010-1234-5678"))
        assertEquals("010-1234-5678", normalizePhoneNumber("010 1234 5678"))
        assertEquals("011-123-4567", normalizePhoneNumber("0111234567"))
    }

    @Test
    fun `정규화 결과는 백엔드 phoneNumber 패턴을 통과한다`() {
        val serverPattern = Regex("^01[016-9]-?\\d{3,4}-?\\d{4}\$")
        assertTrue(normalizePhoneNumber("01012345678").matches(serverPattern))
        assertTrue(normalizePhoneNumber("0161234567").matches(serverPattern))
    }

    @Test
    fun `10·11자리가 아닌 입력은 숫자만 남겨 그대로 보낸다 - 서버 400 검증에 위임`() {
        assertEquals("0101234", normalizePhoneNumber("0101234"))
        assertEquals("", normalizePhoneNumber("abc"))
    }

    /** MVP 기본값이 백엔드 UserRegisterRequest 의 검증 규칙(@Pattern 등)을 통과하는지 고정한다 */
    @Test
    fun `MVP 기본값은 백엔드 검증 패턴을 통과한다`() {
        // birthDate: ISO-8601 Instant + 과거
        assertTrue(MvpProfileDefaults.BIRTH_DATE.endsWith("Z"))
        // gender: MALE | FEMALE
        assertTrue(MvpProfileDefaults.GENDER in setOf("MALE", "FEMALE"))
        // name: @NotBlank, 20자 이하
        assertTrue(MvpProfileDefaults.NAME.isNotBlank() && MvpProfileDefaults.NAME.length <= 20)
        // email: 단순 형식 (loginId 는 영소문자·숫자·_ 뿐이라 로컬 파트로 안전)
        assertTrue("driver01@${MvpProfileDefaults.EMAIL_DOMAIN}".matches(Regex("^[a-z0-9_]+@[a-z.]+$")))
    }

    @Test
    fun `클라 검증 규칙과 같은 백엔드 패턴 - loginId·password 예시 값 확인`() {
        assertTrue(form.loginId.matches(Regex("^[a-z][a-z0-9_]{3,19}$")))
        assertTrue(
            form.password.matches(
                Regex("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+\$"),
            ),
        )
    }

    // ── DTO 직렬화 (서버 필드명 고정) ───────────────────────────────────────

    @Test
    fun `가입 요청 직렬화 필드명이 서버 UserRegisterRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(UserRegisterRequestDto.serializer(), form.toRegisterRequest())
        val keys = element.jsonObject.keys
        assertEquals(
            setOf(
                "loginId", "password", "nickname", "name", "birthDate", "phoneNumber", "gender", "email",
                // 서버 @NotBlank — 빠지면 400 으로 가입 자체가 불가능하다
                "verificationToken",
            ),
            keys,
        )
        assertEquals("번개기사", element.jsonObject["nickname"]!!.jsonPrimitive.content)
        assertEquals("1990-01-01T00:00:00Z", element.jsonObject["birthDate"]!!.jsonPrimitive.content)
    }

    /**
     * 문자 인증 토큰이 가입 요청에 **그대로** 실리는지 — 서버는 이 토큰에 묶인 전화번호 해시와
     * 요청 phoneNumber 를 대조하므로(SmsVerificationService.checkVerified), 토큰이 빠지거나
     * 바뀌면 400 USER207(휴대폰 인증이 필요합니다)로 떨어진다.
     */
    @Test
    fun `가입 요청은 문자 인증 토큰을 그대로 싣는다`() {
        val request = form.toRegisterRequest()

        assertEquals("3f2b8c1e-7a44-4b0d-9c21-5e6f70818293", request.verificationToken)
        // 인증에 쓴 번호와 가입 번호가 같은 정규화를 거쳐야 서버 대조가 통과한다
        assertEquals(normalizePhoneNumber(form.phoneNumber), request.phoneNumber)
    }

    @Test
    fun `로그인 요청 직렬화 필드명이 서버 UserLoginRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            UserLoginRequestDto.serializer(),
            UserLoginRequestDto(loginId = "driver01", password = "test123!@"),
        )
        assertEquals(setOf("loginId", "password"), element.jsonObject.keys)
    }

    @Test
    fun `토큰 응답을 서버 shape 그대로 파싱한다`() {
        val parsed = json.decodeFromString(
            TokenResponseDto.serializer(),
            """{"accessToken":"a.b.c","refreshToken":"d.e.f"}""",
        )
        assertEquals("a.b.c", parsed.accessToken)
        assertEquals("d.e.f", parsed.refreshToken)
    }

    @Test
    fun `가입 응답의 name null(닉네임 미설정)을 방어한다`() {
        val parsed = json.decodeFromString(
            UserResponseDto.serializer(),
            """{"uuid":"0198f6a2-1111-7000-8000-000000000000","name":null}""",
        )
        assertEquals("0198f6a2-1111-7000-8000-000000000000", parsed.uuid)
        assertNull(parsed.name)
    }

    @Test
    fun `휴대폰 조회 요청 직렬화 필드명이 서버 PhoneCheckRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            PhoneCheckRequestDto.serializer(),
            PhoneCheckRequestDto(phoneNumber = normalizePhoneNumber("01012345678")),
        )
        assertEquals(setOf("phoneNumber"), element.jsonObject.keys)
        assertEquals("010-1234-5678", element.jsonObject["phoneNumber"]!!.jsonPrimitive.content)
    }

    @Test
    fun `휴대폰 조회 응답을 서버 PhoneCheckResponse shape 그대로 파싱한다`() {
        assertTrue(json.decodeFromString(PhoneCheckResponseDto.serializer(), """{"exists":true}""").exists)
        assertFalse(json.decodeFromString(PhoneCheckResponseDto.serializer(), """{"exists":false}""").exists)
        // 필드 누락 방어 — 기본 false(미등록 취급이 아닌, 파싱 안전장치)
        assertFalse(json.decodeFromString(PhoneCheckResponseDto.serializer(), """{}""").exists)
    }

    // ── 문자 인증 DTO 직렬화 (SmsVerificationController 계약) ────────────────

    @Test
    fun `인증번호 발송 요청 직렬화 필드명이 서버 SmsSendRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            SmsSendRequestDto.serializer(),
            SmsSendRequestDto(phoneNumber = normalizePhoneNumber("01012345678")),
        )
        assertEquals(setOf("phoneNumber"), element.jsonObject.keys)
        assertEquals("010-1234-5678", element.jsonObject["phoneNumber"]!!.jsonPrimitive.content)
    }

    /** 서버 SmsSendRequest 의 @Pattern 을 정규화 결과가 통과하는지 못 박는다 */
    @Test
    fun `발송 요청 번호는 서버 SmsSendRequest 패턴을 통과한다`() {
        val serverPattern = Regex("^01[016-9]-?\\d{3,4}-?\\d{4}$")
        assertTrue(normalizePhoneNumber("01012345678").matches(serverPattern))
        assertTrue(normalizePhoneNumber("010 9876 5432").matches(serverPattern))
        assertTrue(normalizePhoneNumber("0161234567").matches(serverPattern))
    }

    @Test
    fun `인증번호 확인 요청 직렬화 필드명이 서버 SmsVerifyRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            SmsVerifyRequestDto.serializer(),
            SmsVerifyRequestDto(phoneNumber = normalizePhoneNumber("01012345678"), code = "123456"),
        )
        assertEquals(setOf("phoneNumber", "code"), element.jsonObject.keys)
        // 서버 @Pattern ^\d{6}$
        assertTrue(element.jsonObject["code"]!!.jsonPrimitive.content.matches(Regex("^\\d{6}$")))
    }

    @Test
    fun `인증번호 확인 응답에서 verificationToken 을 꺼낸다`() {
        val parsed = json.decodeFromString(
            SmsVerifyResponseDto.serializer(),
            """{"verificationToken":"3f2b8c1e-7a44-4b0d-9c21-5e6f70818293"}""",
        )
        assertEquals("3f2b8c1e-7a44-4b0d-9c21-5e6f70818293", parsed.verificationToken)
    }

    /** 필드 누락(구서버·필드명 변경) 방어 — 빈 토큰은 호출부가 실패로 처리한다 */
    @Test
    fun `인증번호 확인 응답에 토큰이 없으면 빈 문자열로 방어한다`() {
        assertEquals("", json.decodeFromString(SmsVerifyResponseDto.serializer(), """{}""").verificationToken)
    }

    // ── 닉네임 중복 확인 · 서버 설정 ────────────────────────────────────────

    @Test
    fun `닉네임 확인 요청 직렬화 필드명이 서버 NicknameCheckRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            NicknameCheckRequestDto.serializer(),
            NicknameCheckRequestDto(nickname = "길동이"),
        )
        assertEquals(setOf("nickname"), element.jsonObject.keys)
    }

    @Test
    fun `닉네임 확인 응답을 서버 NicknameCheckResponse shape 그대로 파싱한다`() {
        assertTrue(json.decodeFromString(NicknameCheckResponseDto.serializer(), """{"exists":true}""").exists)
        assertFalse(json.decodeFromString(NicknameCheckResponseDto.serializer(), """{"exists":false}""").exists)
        assertFalse(json.decodeFromString(NicknameCheckResponseDto.serializer(), """{}""").exists)
    }

    @Test
    fun `서버 설정 응답의 taxiEnabled 를 읽고 누락 시 열림으로 본다`() {
        assertTrue(json.decodeFromString(AppConfigResponseDto.serializer(), """{"taxiEnabled":true}""").taxiEnabled)
        assertFalse(json.decodeFromString(AppConfigResponseDto.serializer(), """{"taxiEnabled":false}""").taxiEnabled)
        // 필드 없는 구서버 응답 — 기사를 앱 밖에 가두지 않도록 열림(true)
        assertTrue(json.decodeFromString(AppConfigResponseDto.serializer(), """{}""").taxiEnabled)
    }

    // ── 에러 코드 판정 (404 두 종류 구분) ───────────────────────────────────

    @Test
    fun `기사 미등록은 DRIVER_NOT_REGISTERED 코드일 때만 확인된 종료다`() {
        assertTrue(ServerErrorCodes.isDriverMissing("DRIVER_NOT_REGISTERED"))
    }

    /**
     * 핸들러가 없는 404(서버 taxiEnabled=false → 컨트롤러 미등록, 구버전 배포, baseUrl 오설정)는
     * Spring 기본 body 라 code 가 없다. 이걸 "기사 미등록"으로 읽으면 매 실행 로그아웃된다.
     */
    @Test
    fun `code 없는 404 와 다른 코드의 404 는 기사 미등록이 아니다`() {
        assertFalse(ServerErrorCodes.isDriverMissing(null))
        assertFalse(ServerErrorCodes.isDriverMissing(""))
        assertFalse(ServerErrorCodes.isDriverMissing("USER103"))         // 유저 없음 — 기사 레코드 판정이 아니다
        assertFalse(ServerErrorCodes.isDriverMissing("DRIVER_NOT_FOUND"))
        assertFalse(ServerErrorCodes.isDriverMissing("PARTY_NOT_FOUND"))
    }

    @Test
    fun `파티 없음은 PARTY_NOT_FOUND 코드일 때만 확인된 종료다`() {
        assertTrue(ServerErrorCodes.isPartyMissing("PARTY_NOT_FOUND"))
        assertFalse(ServerErrorCodes.isPartyMissing(null))
        assertFalse(ServerErrorCodes.isPartyMissing("CALL_CLOSED"))
    }

    @Test
    fun `에러 body 의 code 와 message 를 한 번에 꺼낸다`() {
        val error = httpException(409, """{"code":"USER104","message":"이미 가입된 전화번호입니다."}""").serverError()

        assertEquals(ServerErrorCodes.PHONE_NUMBER_DUPLICATED, error?.code)
        assertEquals("이미 가입된 전화번호입니다.", error?.message)
    }

    @Test
    fun `매핑되지 않은 경로의 404 body 에서는 code 가 null 이다`() {
        // Spring 기본 에러 body — code 필드가 없다
        val exception = httpException(
            404,
            """{"timestamp":"2026-10-13T00:00:00.000+00:00","status":404,"error":"Not Found","path":"/api/v1/drivers/me"}""",
        )

        assertNull(exception.errorCode())
    }

    @Test
    fun `에러 body 가 비어도 코드 추출이 터지지 않는다`() {
        assertNull(httpException(500, "").errorCode())
        assertNull(httpException(502, "<html>Bad Gateway</html>").errorCode())
    }

    // ── 사용자 문구 변환 ────────────────────────────────────────────────────

    /** 서버 message 가 이미 사용자 노출용 한국어다 — 클라이언트 문구로 덮지 않는다 */
    @Test
    fun `서버 message 가 있으면 그대로 사용자 문구로 쓴다`() {
        assertEquals(
            "인증번호는 1분에 한 번만 요청할 수 있습니다.",
            authFailureMessage(
                ErrorResponseDto(code = "USER202", message = "인증번호는 1분에 한 번만 요청할 수 있습니다."),
                429,
                "인증번호를 보내지 못했어요",
            ),
        )
    }

    @Test
    fun `body 가 없으면 상태코드별 폴백 문구를 쓴다`() {
        assertTrue(authFailureMessage(null, 429, "실패").contains("잠시 후"))
        assertTrue(authFailureMessage(null, 503, "실패").contains("잠시 후"))
        assertTrue(authFailureMessage(null, 409, "실패").contains("로그인"))
        // 분류되지 않은 상태코드는 호출부 문구 + 상태코드를 노출해 원인 추적을 돕는다
        assertEquals("실패 · 서버 오류(500)", authFailureMessage(null, 500, "실패"))
        // message 가 공백뿐인 body 도 폴백으로 떨어진다
        assertEquals("실패 · 서버 오류(500)", authFailureMessage(ErrorResponseDto(code = "X", message = "  "), 500, "실패"))
    }

    /** 에러 바디를 담은 HttpException 조립 — Retrofit 이 실제로 만드는 모양과 같다 */
    private fun httpException(code: Int, body: String) = HttpException(
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaTypeOrNull())),
    )

    @Test
    fun `공통 에러 body를 파싱한다`() {
        val parsed = json.decodeFromString(
            ErrorResponseDto.serializer(),
            """{"code":"USER102","message":"아이디나 비밀번호가 다릅니다."}""",
        )
        assertEquals("USER102", parsed.code)
        assertEquals("아이디나 비밀번호가 다릅니다.", parsed.message)
        assertFalse(parsed.message.isNullOrBlank())
    }
}
