package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.CallResultRequestDto
import com.moyeota.driver.data.remote.dto.ReportRequestDto
import com.moyeota.driver.data.remote.dto.ReportResponseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * report DTO 직렬화 — 백엔드 record(ReportRequest·ReportResponse·CallResultRequest)의
 * 필드명을 고정한다. 서버 record 필드명이 바뀌면 여기서 먼저 깨진다.
 */
class ReportDtosTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `신고 요청 직렬화 필드명이 서버 ReportRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            ReportRequestDto.serializer(),
            ReportRequestDto(partyId = 12L, latitude = 37.4980, longitude = 127.0276),
        )
        assertEquals(setOf("partyId", "latitude", "longitude"), element.jsonObject.keys)
        assertEquals("12", element.jsonObject["partyId"]!!.jsonPrimitive.content)
        assertEquals("37.498", element.jsonObject["latitude"]!!.jsonPrimitive.content)
        assertEquals("127.0276", element.jsonObject["longitude"]!!.jsonPrimitive.content)
    }

    @Test
    fun `더미 트립 신고 - partyId 는 생략되고 위치만 전송된다`() {
        val element = json.encodeToJsonElement(
            ReportRequestDto.serializer(),
            ReportRequestDto(partyId = null, latitude = 37.4980, longitude = 127.0276),
        )
        // 기본값(null) 필드는 직렬화에서 생략 — 서버(Jackson)는 누락 필드를 null 로 받는다
        assertEquals(setOf("latitude", "longitude"), element.jsonObject.keys)
    }

    @Test
    fun `신고 응답을 서버 shape 그대로 파싱한다`() {
        val parsed = json.decodeFromString(ReportResponseDto.serializer(), """{"reportId":42}""")
        assertEquals(42L, parsed.reportId)
    }

    @Test
    fun `신고 응답 reportId 누락을 방어한다`() {
        val parsed = json.decodeFromString(ReportResponseDto.serializer(), """{}""")
        assertNull(parsed.reportId)
    }

    @Test
    fun `통화 여부 요청 직렬화 필드명이 서버 CallResultRequest 와 일치한다`() {
        val element = json.encodeToJsonElement(
            CallResultRequestDto.serializer(),
            CallResultRequestDto(called = false),
        )
        assertEquals(setOf("called"), element.jsonObject.keys)
        assertFalse(element.jsonObject["called"]!!.jsonPrimitive.content.toBoolean())
    }
}
