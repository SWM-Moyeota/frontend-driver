package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartyDetailMembersDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 매칭방(파티) 상세 조회 — ../backend .../matching/presentation/PartyController.detail 기준.
 * 기사 앱에서는 콜 수락 시 승객 실닉네임을 얻는 용도로만 쓴다 (members[].nickname 만 파싱).
 * 인증 클라이언트(Bearer) 사용 — 기사 토큰으로 조회 가능함을 실측으로 확인 (실서버 검증됨).
 */
interface PartyMembersApi {

    /** 404 PARTY_NOT_FOUND 가능 — 호출부는 베스트에포트(실패 시 "승객N" 유지)로 처리한다 */
    @GET("api/v1/matching/rooms/{partyId}")
    suspend fun getPartyMembers(@Path("partyId") partyId: Long): PartyDetailMembersDto
}
