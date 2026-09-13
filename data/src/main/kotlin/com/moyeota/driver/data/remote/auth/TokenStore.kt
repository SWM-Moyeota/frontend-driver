package com.moyeota.driver.data.remote.auth

import com.moyeota.driver.data.session.DriverSessionStorage

/**
 * 토큰 보관소 — 인메모리 캐시가 원본이고, [attachPersistence] 로 영속 저장소를 붙이면 write-through 된다.
 *
 * OkHttp 인터셉터/Authenticator(워커 스레드)와 Repository(코루틴)가 동시에 접근하므로
 * 갱신·조회를 [lock] 으로 직렬화한다. 401 재발급 경합 제어는 [TokenAuthenticator] 쪽 잠금이 맡고,
 * 여기는 "쌍(access+refresh)이 항상 함께 바뀐다"는 원자성만 보장한다.
 *
 * 영속화가 없으면 프로세스 종료 = 로그아웃이라 운행 중 앱이 죽으면 로그인 화면부터 다시 시작한다
 * (27번 복구 작업의 원인 1). 붙이는 시점은 AppContainer 생성 — 첫 네트워크 호출보다 앞선다.
 */
class TokenStore {

    private val lock = Any()

    private var accessToken: String? = null
    private var refreshToken: String? = null

    /** 붙기 전에는 순수 인메모리 — 테스트·더미 구동은 이 상태로 기존과 동일하게 동작한다 */
    private var persistence: DriverSessionStorage? = null

    /**
     * 영속 저장소를 연결한다 — 붙는 즉시 저장된 쌍을 메모리로 올리고(앱 재실행 복구),
     * 이후 [update]·[clear] 가 저장소까지 함께 반영한다.
     *
     * 이미 메모리에 토큰이 있으면(같은 프로세스에서 로그인 뒤 늦게 붙인 경우) 메모리 쪽이 최신이므로
     * 저장소를 메모리 값으로 덮는다.
     */
    fun attachPersistence(storage: DriverSessionStorage) {
        synchronized(lock) {
            persistence = storage
            val access = accessToken
            val refresh = refreshToken
            if (access != null && refresh != null) {
                storage.writeTokens(access, refresh)
            } else {
                storage.readTokens()?.let {
                    accessToken = it.accessToken
                    refreshToken = it.refreshToken
                }
            }
        }
    }

    fun accessToken(): String? = synchronized(lock) { accessToken }

    fun refreshToken(): String? = synchronized(lock) { refreshToken }

    fun isLoggedIn(): Boolean = synchronized(lock) { accessToken != null }

    /** 로그인·재발급 성공 반영 — 회전 방식이라 항상 쌍으로 교체한다 */
    fun update(accessToken: String, refreshToken: String) {
        synchronized(lock) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            persistence?.writeTokens(accessToken, refreshToken)
        }
    }

    /** 로그아웃·재발급 실패(세션 만료) 반영 */
    fun clear() {
        synchronized(lock) {
            accessToken = null
            refreshToken = null
            persistence?.clearTokens()
        }
    }
}
