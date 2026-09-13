package com.moyeota.driver.data.session

/**
 * 프로세스가 죽어도 남아야 하는 기사 세션의 영속 포트.
 *
 * 안드로이드 구현(SharedPreferences)은 Context 를 가진 app 모듈이 소유하고, data 계층은 이 인터페이스로만
 * 읽고 쓴다 — `DriverLocationSource`(domain 인터페이스 ↔ app 구현)와 같은 패턴이다.
 *
 * 보관 대상은 두 가지뿐이다:
 * 1. 토큰 쌍 — [TokenStore.attachPersistence] 가 붙여 write-through 로 유지한다.
 * 2. 활성 partyId — 콜 수락 시 쓰고, 운행 완료·로그아웃·복구 판정 실패 시 지운다.
 *    서버에 "이 기사의 진행 중 운행" 조회 API 가 없어서(내부 가드로만 존재) 단말이 파티 id 를 들고 있다가
 *    재실행 때 그 파티를 다시 조회하는 방식으로 운행을 복구한다.
 *
 * 구현 주의: 네트워크 스레드(OkHttp Authenticator)에서도 호출되므로 쓰기는 블로킹하지 않아야 한다.
 */
interface DriverSessionStorage {

    /** 저장된 토큰 쌍 — 한쪽이라도 없으면 null (반쪽 세션은 복구 불가) */
    fun readTokens(): SessionTokens?

    fun writeTokens(accessToken: String, refreshToken: String)

    fun clearTokens()

    /** 진행 중으로 알고 있는 파티 id — 없으면 null */
    fun readActivePartyId(): Long?

    fun writeActivePartyId(partyId: Long)

    fun clearActivePartyId()
}

/** 저장된 토큰 쌍 — 회전 방식이라 항상 함께 읽고 쓴다 */
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
)
