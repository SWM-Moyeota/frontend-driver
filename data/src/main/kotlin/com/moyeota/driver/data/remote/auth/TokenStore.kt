package com.moyeota.driver.data.remote.auth

/**
 * 인메모리 토큰 보관소 (MVP — 디스크 저장 없음, 앱 재실행 시 재로그인).
 *
 * OkHttp 인터셉터/Authenticator(워커 스레드)와 Repository(코루틴)가 동시에 접근하므로
 * 갱신·조회를 [lock] 으로 직렬화한다. 401 재발급 경합 제어는 [TokenAuthenticator] 쪽 잠금이 맡고,
 * 여기는 "쌍(access+refresh)이 항상 함께 바뀐다"는 원자성만 보장한다.
 */
class TokenStore {

    private val lock = Any()

    private var accessToken: String? = null
    private var refreshToken: String? = null

    fun accessToken(): String? = synchronized(lock) { accessToken }

    fun refreshToken(): String? = synchronized(lock) { refreshToken }

    fun isLoggedIn(): Boolean = synchronized(lock) { accessToken != null }

    /** 로그인·재발급 성공 반영 — 회전 방식이라 항상 쌍으로 교체한다 */
    fun update(accessToken: String, refreshToken: String) {
        synchronized(lock) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
        }
    }

    /** 로그아웃·재발급 실패(세션 만료) 반영 */
    fun clear() {
        synchronized(lock) {
            accessToken = null
            refreshToken = null
        }
    }
}
