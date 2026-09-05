package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.RegisterDriverRequestDto
import com.moyeota.driver.data.remote.dto.RegisterVehicleRequestDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * driver DTO 직렬화 — 백엔드 신스펙(작업트리 DriverController + RegisterDriverRequest record)의
 * 필드명·중첩 구조를 고정한다. 서버 record 필드명이 바뀌면 여기서 먼저 깨진다.
 */
class DriverDtosTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `기사 등록 요청 직렬화 - userId 없음 + vehicle 중첩 구조가 서버 record 와 일치한다`() {
        val element = json.encodeToJsonElement(
            RegisterDriverRequestDto.serializer(),
            RegisterDriverRequestDto(
                qualificationNumber = "서울-2024-001234",
                bankName = "국민",
                accountNumber = "000000000000",
                vehicle = RegisterDriverRequestDto.VehicleInfoDto(seats = 4, plateNumber = "34가 1234", type = "쏘나타"),
            ),
        )

        // 신스펙: userId 는 토큰(@CurrentUser)에서 해석 — body 에 있으면 안 된다
        assertEquals(setOf("qualificationNumber", "bankName", "accountNumber", "vehicle"), element.jsonObject.keys)

        val vehicle = element.jsonObject["vehicle"]!!.jsonObject
        assertEquals(setOf("seats", "plateNumber", "type"), vehicle.keys)
        assertEquals("4", vehicle["seats"]!!.jsonPrimitive.content)
        assertEquals("34가 1234", vehicle["plateNumber"]!!.jsonPrimitive.content)
        assertEquals("쏘나타", vehicle["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `차량 등록 요청 직렬화 필드명이 서버 RegisterVehicleRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            RegisterVehicleRequestDto.serializer(),
            RegisterVehicleRequestDto(seats = 4, plateNumber = "34가 1234", type = "쏘나타"),
        )
        assertEquals(setOf("seats", "plateNumber", "type"), element.jsonObject.keys)
    }

    @Test
    fun `DriverResult 응답을 서버 shape 그대로 파싱한다`() {
        val parsed = json.decodeFromString(
            DriverResultDto.serializer(),
            """{"id":7,"userId":3,"status":"VERIFIED","callEnabled":true}""",
        )
        assertEquals(7L, parsed.id)
        assertEquals(3L, parsed.userId)
        assertEquals("VERIFIED", parsed.status)
        assertTrue(parsed.callEnabled)
    }

    @Test
    fun `DriverResult 필드 누락을 방어한다`() {
        val parsed = json.decodeFromString(DriverResultDto.serializer(), """{}""")
        assertNull(parsed.id)
        assertNull(parsed.status)
        assertFalse(parsed.callEnabled)
    }
}
