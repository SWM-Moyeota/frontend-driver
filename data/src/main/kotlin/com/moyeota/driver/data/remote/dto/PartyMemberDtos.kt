package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 매칭방 상세 응답 중 승객 표시명에 필요한 최소 shape.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/matching/application/dto/PartyDetailResult.java
 *      + 실측 응답(기사 토큰으로 GET /api/v1/matching/rooms/{partyId} 조회 가능).
 *
 * 의도적으로 members[].nickname 만 파싱한다 — route(대형 폴리라인 문자열)·좌표 등 나머지 필드는
 * ignoreUnknownKeys 로 건너뛴다. 파티 좌표/요금은 이미 PartySummaryDto(dispatch 콜 상세)가 담당한다.
 */

/** GET /api/v1/matching/rooms/{partyId} 응답 — PartyDetailResult 중 members 만 */
@Serializable
data class PartyDetailMembersDto(
    val members: List<PartyMemberDto> = emptyList(),
)

/** PartyDetailResult.MemberInfo — 탈퇴 회원 등은 nickname null 가능 */
@Serializable
data class PartyMemberDto(
    val nickname: String? = null,
)
