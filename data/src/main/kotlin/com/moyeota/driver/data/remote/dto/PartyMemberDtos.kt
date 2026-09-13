package com.moyeota.driver.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * 매칭방 상세 응답 shape.
 * 원천: ../backend/src/main/java/team/codingforest/moyeota/matching/application/dto/PartyDetailResult.java
 *      + 실측 응답(기사 토큰으로 GET /api/v1/matching/rooms/{partyId} 조회 가능).
 *
 * 쓰임 두 가지:
 * 1. 콜 수락 직후 승객 실닉네임 주입 (26번) — members[].nickname
 * 2. 앱 재실행 시 진행 중 운행 복구 (27번) — status·taxiDriverId·좌표·장소명·currentMembers·요금/시간
 *
 * **route 는 일부러 파싱하지 않는다** (대형 폴리라인 문자열 — 복구에 불필요).
 * createdAt(Instant)·capacity·radius 등 나머지도 ignoreUnknownKeys 로 건너뛴다.
 *
 * 좌표 필드명 주의: 이 응답은 `departureLat`/`departureLng` 이고,
 * dispatch 콜 상세([PartySummaryDto])는 `departureLatitude`/`departureLongitude` 다 — 서로 다르다.
 */

/** GET /api/v1/matching/rooms/{partyId} 응답 — PartyDetailResult (필요 필드만) */
@Serializable
data class PartyDetailDto(
    val id: Long? = null,
    val departureLat: Double? = null,
    val departureLng: Double? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val departure: String? = null,
    val destination: String? = null,
    /** 현재 참여 인원 — 복구 시 승객 수의 원천 (없으면 members.size 로 폴백) */
    val currentMembers: Int? = null,
    /** ACTIVE | COMPLETED | MATCHING | DRIVER_ASSIGNED | IN_RIDE | FINISHED | CANCELED */
    val status: String? = null,
    val members: List<PartyMemberDto> = emptyList(),
    val estimateFare: Int? = null,       // 서버 Integer — null 가능 (원)
    val estimateTime: Int? = null,       // 서버 Integer — null 가능 (분)
    /** 배정된 기사의 **driver 엔티티 id** (= GET /drivers/me 의 id). 배정 전 null */
    val taxiDriverId: Long? = null,
)

/** PartyDetailResult.MemberInfo — 탈퇴 회원 등은 nickname null 가능 */
@Serializable
data class PartyMemberDto(
    val nickname: String? = null,
)
