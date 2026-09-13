package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartyDetailDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/v1/matching/rooms/{partyId} 응답 파싱 (실측 응답 shape 기준).
 * 닉네임(26번) + 복구에 필요한 상태·배정 기사·좌표·장소명·인원·요금(27번)을 뽑고,
 * route(대형 폴리라인)·createdAt(Instant)·미지 필드는 전부 건너뛰는지 검증한다.
 */
class PartyMemberDtosTest {

    // NetworkModule 과 동일 설정
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `실측 응답 shape 에서 복구 필드를 파싱한다`() {
        val dto = json.decodeFromString<PartyDetailDto>(
            """
            {"id":32,"departureLat":37.4980,"departureLng":127.0276,
             "destinationLat":37.3948,"destinationLng":127.1112,
             "departure":"강남역","destination":"판교역",
             "capacity":2,"currentMembers":2,"departureRadius":300,"destinationRadius":300,
             "status":"IN_RIDE","createdAt":"2026-09-10T01:23:45Z",
             "members":[{"publicId":"8f4a1c2e-0000-0000-0000-000000000001","nickname":"승객검D417","rideCount":1}],
             "estimateFare":18400,"estimateTime":25,
             "route":"a~sdEwbmfWjA`Cx@vBhCdGr@dB|@xB(...대형 폴리라인...)","taxiDriverId":7}
            """.trimIndent(),
        )

        assertEquals(32L, dto.id)
        assertEquals("IN_RIDE", dto.status)
        assertEquals(7L, dto.taxiDriverId)
        // 이 응답의 좌표 키는 departureLat/Lng — dispatch 콜 상세(departureLatitude/Longitude)와 다르다
        assertEquals(37.4980, dto.departureLat!!, 1e-6)
        assertEquals(127.0276, dto.departureLng!!, 1e-6)
        assertEquals(37.3948, dto.destinationLat!!, 1e-6)
        assertEquals(127.1112, dto.destinationLng!!, 1e-6)
        assertEquals("강남역", dto.departure)
        assertEquals("판교역", dto.destination)
        assertEquals(2, dto.currentMembers)
        assertEquals(18400, dto.estimateFare)
        assertEquals(25, dto.estimateTime)
    }

    @Test
    fun `배정 전 파티는 taxiDriverId 가 null 이어도 파싱된다`() {
        val dto = json.decodeFromString<PartyDetailDto>(
            """{"id":32,"status":"MATCHING","taxiDriverId":null,"currentMembers":1,"members":[]}""",
        )

        assertNull(dto.taxiDriverId)
        assertEquals("MATCHING", dto.status)
    }

    @Test
    fun `실측 응답 shape 에서 닉네임만 파싱한다`() {
        val dto = json.decodeFromString<PartyDetailDto>(
            """
            {"id":32,"departureLat":37.4980,"departureLng":127.0276,
             "destinationLat":37.3948,"destinationLng":127.1112,
             "departure":"강남역","destination":"판교역",
             "capacity":2,"currentMembers":2,"departureRadius":300,"destinationRadius":300,
             "status":"MATCHING","createdAt":"2026-09-10T01:23:45Z",
             "members":[
               {"publicId":"8f4a1c2e-0000-0000-0000-000000000001","nickname":"승객검D417","imageUrl":null,"badgeId":null,"rideCount":1,"joinedAt":"2026-09-10T01:20:00Z"},
               {"publicId":"8f4a1c2e-0000-0000-0000-000000000002","nickname":"모여타짱","imageUrl":"https://cdn/img.png","badgeId":null,"rideCount":3,"joinedAt":"2026-09-10T01:21:00Z"}
             ],
             "estimateFare":18400,"estimateTime":25,
             "route":"a~sdEwbmfWjA`Cx@vBhCdGr@dB|@xB(...대형 폴리라인...)","taxiDriverId":7}
            """.trimIndent(),
        )

        assertEquals(listOf("승객검D417", "모여타짱"), dto.members.map { it.nickname })
    }

    @Test
    fun `탈퇴 회원 등 닉네임 null 이어도 파싱이 깨지지 않는다`() {
        val dto = json.decodeFromString<PartyDetailDto>(
            """{"id":32,"members":[{"publicId":null,"nickname":null,"rideCount":0},{"nickname":"승객검D417"}]}""",
        )

        assertEquals(2, dto.members.size)
        assertNull(dto.members[0].nickname)
        assertEquals("승객검D417", dto.members[1].nickname)
    }

    @Test
    fun `members 누락이면 빈 목록으로 방어한다`() {
        val dto = json.decodeFromString<PartyDetailDto>("""{"id":32,"status":"MATCHING"}""")

        assertTrue(dto.members.isEmpty())
    }
}
