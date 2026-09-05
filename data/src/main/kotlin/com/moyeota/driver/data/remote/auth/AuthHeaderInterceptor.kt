package com.moyeota.driver.data.remote.auth

import okhttp3.Interceptor
import okhttp3.Response

internal const val HEADER_AUTHORIZATION = "Authorization"
internal const val BEARER_PREFIX = "Bearer "

/** permitAll 인 인가 엔드포인트 프리픽스 — 여기엔 토큰을 붙이지 않는다 */
internal const val AUTH_PATH_PREFIX = "/api/v1/auth/"

/**
 * 보호 API 요청에 `Authorization: Bearer {access}` 를 붙인다.
 *
 * - `/api/v1/auth/` 하위(가입/로그인/재발급/로그아웃)는 제외 — permitAll 구간이고,
 *   만료 토큰이 실려 가면 로그인 요청 자체가 401 로 오염될 수 있다.
 * - 이미 Authorization 이 있으면 건드리지 않는다 — [TokenAuthenticator] 가 재발급 토큰으로
 *   세팅한 재시도 요청, [com.moyeota.driver.data.remote.AuthApi.getMyInfo] 의 명시 헤더가 해당된다.
 * - 토큰이 없으면(미로그인) 헤더 없이 보낸다. 서버가 401 로 명확히 답하고, 그 401 은
 *   재시도할 토큰이 없으므로 [TokenAuthenticator] 도 구제하지 않는다 — 화면이 로그인을 막아야 한다.
 */
class AuthHeaderInterceptor(
    private val tokens: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.startsWith(AUTH_PATH_PREFIX)) return chain.proceed(request)
        if (request.header(HEADER_AUTHORIZATION) != null) return chain.proceed(request)

        val accessToken = tokens.accessToken() ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                .build(),
        )
    }
}
