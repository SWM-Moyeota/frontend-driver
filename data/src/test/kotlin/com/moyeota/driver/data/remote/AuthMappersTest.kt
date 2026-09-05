package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
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

class AuthMappersTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val form = DriverSignUpForm(
        phoneNumber = "01012345678",
        name = "박기사",
        loginId = "driver01",
        password = "test123!@",
        vehicleType = "쏘나타",
        seats = 4,
        plateNumber = "34가 1234",
        qualificationNumber = "서울-2024-001234",
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
            setOf("loginId", "password", "name", "birthDate", "phoneNumber", "gender", "email"),
            keys,
        )
        assertEquals("1990-01-01T00:00:00Z", element.jsonObject["birthDate"]!!.jsonPrimitive.content)
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
