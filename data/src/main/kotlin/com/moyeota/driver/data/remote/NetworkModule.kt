package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.auth.AuthHeaderInterceptor
import com.moyeota.driver.data.remote.auth.TokenAuthenticator
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.TokenRequestDto
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 구성 (수동 DI — AppContainer 에서 호출).
 * baseUrl 은 에뮬레이터 → 호스트 머신 기준. 실기기 테스트 시 호스트 IP 로 교체한다.
 *
 * 클라이언트 2종:
 * - 기본 클라이언트: [authApi] 전용. Bearer 인터셉터·Authenticator 없음 — 재발급이 재발급을
 *   부르는 재귀를 원천 차단한다.
 * - 인증 클라이언트: [driverApi]·[dispatchApi]. 모든 요청에 Bearer 부착([AuthHeaderInterceptor]),
 *   401 시 reissue 1회 재시도([TokenAuthenticator]).
 */
object NetworkModule {

    /** 배포 백엔드(CloudFront/HTTPS). 로컬 개발 시 주석의 에뮬레이터 주소로 바꿔 쓴다 */
    const val BASE_URL = "https://api.moyeota.p-e.kr/" // 배포 서버. 로컬 백엔드는 "http://10.0.2.2:8080/" (에뮬레이터)

    private val json = Json {
        ignoreUnknownKeys = true     // 서버 필드 추가에 관대
        coerceInputValues = true     // null → 기본값 방어
    }

    /** 앱 전역 토큰 보관소 (인메모리 — 앱 재실행 시 재로그인) */
    val tokenStore: TokenStore = TokenStore()

    private val baseRetrofit: Retrofit by lazy { retrofit(baseOkHttpClient()) }

    private val authedRetrofit: Retrofit by lazy { retrofit(authedOkHttpClient()) }

    fun authApi(): AuthApi = cachedAuthApi

    fun driverApi(): DriverApi = authedRetrofit.create(DriverApi::class.java)

    fun dispatchApi(): DispatchApi = authedRetrofit.create(DispatchApi::class.java)

    private val cachedAuthApi: AuthApi by lazy { baseRetrofit.create(AuthApi::class.java) }

    /** Authenticator 에서 쓰는 동기 재발급 — 성공 시 (access, refresh) 쌍, 실패 시 null */
    private fun reissueBlocking(refreshToken: String): Pair<String, String>? {
        val response = runCatching { cachedAuthApi.reissue(TokenRequestDto(refreshToken)).execute() }
            .getOrNull() ?: return null
        val body = response.body()
        if (!response.isSuccessful || body == null) return null
        if (body.accessToken.isBlank() || body.refreshToken.isBlank()) return null
        return body.accessToken to body.refreshToken
    }

    private fun baseOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            // 배포 서버(https://api.moyeota.p-e.kr)는 유휴 후 첫 요청 응답이 7~8초까지 걸린다(콜드 스타트).
            // 5s/10s 로는 콜 상세 조회가 그 구간에서 통째로 타임아웃돼 콜 정보를 못 띄웠다.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY },
            )
            .build()

    private fun authedOkHttpClient(): OkHttpClient =
        baseOkHttpClient().newBuilder()
            .addInterceptor(AuthHeaderInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, ::reissueBlocking))
            .build()

    private fun retrofit(client: OkHttpClient, baseUrl: String = BASE_URL): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
