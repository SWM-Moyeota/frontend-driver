package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.dto.PartyDetailDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 매칭방(파티) 상세 조회 — ../backend .../matching/presentation/PartyController.detail 기준.
 * 인증 클라이언트(Bearer) 사용 — 기사 토큰으로 조회 가능함을 실측으로 확인 (실서버 검증됨).
 *
 * 기사 앱의 쓰임:
 * - 콜 수락 시 승객 실닉네임 (members[].nickname)
 * - 앱 재실행 시 진행 중 운행 복구 (status·taxiDriverId·좌표·장소명) — dispatch 콜 상세는
 *   수락 후 콜 후보에서 빠져 409 CALL_CLOSED 로 떨어지므로 복구 원천으로 쓸 수 없다.
 *
 * (인터페이스 이름은 26번 최초 용도인 승객 목록 조회에서 유래했다 — 파일 경로 호환을 위해 유지)
 */
interface PartyMembersApi {

    /** 404 PARTY_NOT_FOUND 가능 — 닉네임 조회는 베스트에포트, 복구는 "저장된 파티 없음"으로 처리한다 */
    @GET("api/v1/matching/rooms/{partyId}")
    suspend fun getPartyDetail(@Path("partyId") partyId: Long): PartyDetailDto
}
