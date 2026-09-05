package com.moyeota.driver.data.remote.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 → `/api/v1/auth/reissue` 로 재발급 후 원요청을 **1회** 재시도한다.
 *
 * 재귀·루프 방지 장치 세 겹:
 * 1. 애초에 Bearer 를 싣지 않았던 요청의 401 은 재발급으로 풀 수 없으므로 그대로 올린다
 *    (인가 엔드포인트는 [AuthHeaderInterceptor] 가 토큰을 붙이지 않아 여기 해당 — reissue 가
 *    reissue 를 부르는 재귀가 원천 차단된다).
 * 2. priorResponse 체인으로 재시도 횟수를 세어 [MAX_RETRY] 초과 시 중단한다.
 * 3. 재발급 자체는 [synchronized] 로 직렬화 — 서버가 리프레시 회전 방식이라 두 스레드가 같은
 *    리프레시로 동시에 재발급하면 늦은 쪽이 "이미 회전됨" 401 을 맞고 세션이 통째로 날아간다.
 *    잠금 획득 시점에 토큰이 이미 바뀌어 있으면(다른 스레드가 갱신 완료) 재발급 없이 재시도한다.
 *
 * 재발급 실패 시 [TokenStore] 를 비운다 — 이후 요청은 미로그인 401 로 떨어지고 화면이 재로그인을 안내한다.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val refresh: (refreshToken: String) -> Pair<String, String>?,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedAccessToken = response.request.bearerToken() ?: return null
        if (retryCount(response) >= MAX_RETRY) return null

        val newAccessToken = synchronized(this) {
            val current = tokens.accessToken()
            when {
                // 다른 스레드가 이미 갱신을 끝냈다 — 재발급 없이 새 토큰으로 재시도
                current != null && current != failedAccessToken -> current

                else -> {
                    val refreshToken = tokens.refreshToken()
                    val refreshed = refreshToken?.let { runCatching { refresh(it) }.getOrNull() }
                    if (refreshed == null) {
                        tokens.clear()
                        null
                    } else {
                        tokens.update(refreshed.first, refreshed.second)
                        refreshed.first
                    }
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + newAccessToken)
            .build()
    }

    private fun Request.bearerToken(): String? =
        header(HEADER_AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }

    // OkHttp 는 Authenticator 재시도의 앞선 응답들을 priorResponse 로 이어 붙인다
    private fun retryCount(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_RETRY = 1
    }
}
