package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.TokenResponseDto
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.model.DriverSignUpForm
import com.moyeota.driver.data.session.SessionTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가입 체인(`register → login → POST /drivers → /drivers/verify`)의 **중간 단계 실패** 검증.
 *
 * 가입은 네 번의 서버 왕복이라 "어디서 멈췄는가"가 그대로 사용자 안내와 남은 상태를 결정한다.
 * 기존 `RemoteDriverRepositoryAuthSyncTest` 는 1단계(register)의 409 코드 분기를 덮는다.
 * 여기서는 그 뒤 단계가 깨졌을 때를 본다 — 세 가지를 함께 단정한다:
 *
 * 1. **어느 단계에서 멈췄는지** 문구에 드러나는가 (`회원가입 실패 — {단계}: {사유}`)
 * 2. **토큰이 어떻게 남는가** — 2단계 전 실패는 미로그인, 3·4단계 실패는 토큰이 남아 재제출이 가능해야 한다
 * 3. **다음 단계를 헛되게 부르지 않는가** — 성공할 수 없는 호출은 원인을 가린다
 *
 * 인증 토큰(verificationToken)은 서버가 가입 성공 후에만 소비하므로(SmsVerificationService.consume),
 * 중간에 멈춰도 같은 토큰으로 곧바로 재제출할 수 있다 — 그래서 "멈춤"은 "처음부터 다시"가 아니다.
 */
class RemoteDriverRepositorySignUpChainTest {

    private val form = DriverSignUpForm(
        phoneNumber = "01012345678",
        name = "박기사",
        nickname = "번개기사",
        loginId = "driver01",
        password = "test123!@",
        vehicleType = "쏘나타",
        seats = 4,
        plateNumber = "34가 1234",
        qualificationNumber = "서울-2024-001234",
        verificationToken = "token-abc",
    )

    // ── 1단계(register) 실패 — 토큰도 저장도 남지 않는다 ────────────────────

    /**
     * 인증 토큰 만료(400 USER207)에 body 가 없으면 서버 문구가 없다. 그래도 **어느 단계**에서 막혔는지는
     * 알려야 한다 — 가입 1~4단계를 오가는 화면에서 "서버 오류"만 뜨면 어디로 돌아가야 할지 알 수 없다.
     */
    @Test
    fun `인증 만료 400 에 본문이 없어도 멈춘 단계와 상태코드를 알린다`() = runBlocking {
        val storage = EdgeStorage()
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val authApi = EdgeAuthApi(onRegister = { throw edgeEmptyBody(400) })
        val repository = repository(authApi = authApi, tokenStore = tokenStore, storage = storage)

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 계정 생성: 계정을 만들지 못했어요 · 서버 오류(400)", message)
        assertEquals("성공할 수 없는 로그인을 시도하면 원인이 가려진다", 0, authApi.loginCalls)
        assertFalse("계정이 안 만들어졌으니 로그인 상태가 될 수 없다", tokenStore.isLoggedIn())
        assertNull(storage.tokens)
    }

    // ── 2단계(자동 로그인) 실패 — 토큰 없음 + 3단계 미호출 ──────────────────

    @Test
    fun `계정 생성 후 로그인이 5xx 로 실패하면 토큰을 저장하지 않고 단계를 알린다`() = runBlocking {
        val storage = EdgeStorage()
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val driverApi = EdgeDriverApi()
        val authApi = EdgeAuthApi(onLogin = { throw edgeCoded(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.") })
        val repository = repository(authApi = authApi, driverApi = driverApi, tokenStore = tokenStore, storage = storage)

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 자동 로그인: 서비스를 사용할 수 없습니다.", message)
        assertEquals("계정은 만들어졌다 — 재제출 시 409 USER101 로 같은 경로를 탄다", 1, authApi.registerCalls)
        assertFalse(tokenStore.isLoggedIn())
        assertNull(storage.tokens)
        assertEquals("토큰 없이 기사 등록을 시도하면 401 만 더 받는다", 0, driverApi.registerCalls)
    }

    /**
     * 중복(409)을 거치지 않은 401 은 진짜 "아이디·비밀번호 불일치"다 — 이때는 서버 문구를 바꾸지 않는다.
     * (중복 뒤의 401 만 [RemoteDriverRepository] 가 "이미 사용 중인 아이디" 로 바꿔 말한다)
     */
    @Test
    fun `중복이 아닌 로그인 401 은 서버 문구를 그대로 쓴다`() = runBlocking {
        val repository = repository(
            authApi = EdgeAuthApi(onLogin = { throw edgeCoded(401, "USER102", "아이디나 비밀번호가 다릅니다.") }),
        )

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 자동 로그인: 아이디나 비밀번호가 다릅니다.", message)
        assertFalse("중복으로 넘어온 적이 없으니 중복 안내로 바꾸면 거짓이다", message.contains("이미"))
    }

    /** 전화번호 중복(USER104)도 "내 계정일 수 있다" — 로그인까지 실패하면 무엇이 중복인지 집어 준다 */
    @Test
    fun `전화번호 중복 409 뒤 로그인 401 은 로그인 화면 안내로 바뀐다`() = runBlocking {
        val repository = repository(
            authApi = EdgeAuthApi(
                onRegister = { throw edgeCoded(409, "USER104", "이미 가입된 전화번호입니다.") },
                onLogin = { throw edgeCoded(401, "USER102", "아이디나 비밀번호가 다릅니다.") },
            ),
        )

        val message = signUpFailureMessage(repository)

        assertTrue(message, message.contains("이미 가입된 전화번호예요"))
        assertTrue(message, message.contains("로그인"))
        assertFalse("방금 번호 인증까지 끝낸 사용자에게 단서가 못 된다", message.contains("비밀번호가 다릅니다"))
    }

    /** 같은 USER104 라도 내 계정이면(로그인 성공) 가입은 끝까지 이어진다 — 재제출 경로 */
    @Test
    fun `전화번호 중복 409 라도 내 계정이면 가입이 이어진다`() = runBlocking {
        val driverApi = EdgeDriverApi()
        val repository = repository(
            authApi = EdgeAuthApi(onRegister = { throw edgeCoded(409, "USER104", "이미 가입된 전화번호입니다.") }),
            driverApi = driverApi,
        )

        val result = repository.signUp(form)

        assertEquals(DriverAccountStatus.APPROVED, result.status)
        assertEquals("번개기사", result.driverName)
        assertEquals(1, driverApi.registerCalls)
        assertEquals(1, driverApi.verifyCalls)
    }

    // ── 3단계(기사 등록) ───────────────────────────────────────────────────

    /**
     * 기사 등록이 깨져도 **토큰은 남긴다** — 계정과 세션은 이미 유효하므로, 기사가 다시 제출하면
     * register 409(USER101) → 로그인 → 기사 등록만 다시 시도하는 경로를 탈 수 있다.
     * 여기서 토큰까지 비우면 재제출이 처음부터 다시가 된다.
     */
    @Test
    fun `기사 등록 5xx 는 세션을 남기고 멈춘 단계를 알린다`() = runBlocking {
        val storage = EdgeStorage()
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val driverApi = EdgeDriverApi(onRegisterDriver = { throw edgeCoded(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다.") })
        val repository = repository(driverApi = driverApi, tokenStore = tokenStore, storage = storage)

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 기사 등록: 서버 오류가 발생했습니다.", message)
        assertTrue("로그인은 성공했다 — 재제출이 기사 등록부터 이어질 수 있어야 한다", tokenStore.isLoggedIn())
        assertEquals(SessionTokens("access-1", "refresh-1"), storage.tokens)
        assertEquals("기사 레코드가 없으니 자격 검증은 의미가 없다", 0, driverApi.verifyCalls)
    }

    /** 이미 기사로 등록된 계정(409)은 GET /drivers/me 로 기존 기사를 회수해 그대로 진행한다 */
    @Test
    fun `기사 등록 409 는 기존 기사를 조회해 가입을 이어 간다`() = runBlocking {
        val driverApi = EdgeDriverApi(
            onRegisterDriver = { throw edgeCoded(409, "DRIVER_ALREADY_REGISTERED", "이미 등록된 기사입니다.") },
        )
        val repository = repository(driverApi = driverApi)

        val result = repository.signUp(form)

        assertEquals(DriverAccountStatus.APPROVED, result.status)
        assertEquals("409 를 실패로 보면 재제출이 영영 막힌다", 1, driverApi.getMeCalls)
        assertEquals(1, driverApi.verifyCalls)
    }

    @Test
    fun `기사 등록 409 인데 기존 기사 조회까지 실패하면 멈춘다`() = runBlocking {
        val driverApi = EdgeDriverApi(
            onRegisterDriver = { throw edgeCoded(409, "DRIVER_ALREADY_REGISTERED", "이미 등록된 기사입니다.") },
            onGetMe = { throw edgeCoded(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다.") },
        )
        val repository = repository(driverApi = driverApi)

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 기사 등록: 서버 오류가 발생했습니다.", message)
        assertEquals(0, driverApi.verifyCalls)
    }

    // ── 4단계(자격 검증) ───────────────────────────────────────────────────

    /** 이미 검증된 기사(409 DRIVER_NOT_PENDING)는 가입의 목표가 이미 이뤄진 상태다 */
    @Test
    fun `자격 검증 409 는 이미 검증됨으로 보고 가입을 완료한다`() = runBlocking {
        val repository = repository(
            driverApi = EdgeDriverApi(onVerify = { throw edgeCoded(409, "DRIVER_NOT_PENDING", "검증 대기 상태가 아닙니다.") }),
        )

        val result = repository.signUp(form)

        assertEquals(DriverAccountStatus.APPROVED, result.status)
        assertEquals("번개기사", result.driverName)
    }

    @Test
    fun `자격 검증 5xx 는 가입 실패로 올린다`() = runBlocking {
        val storage = EdgeStorage()
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(
            driverApi = EdgeDriverApi(onVerify = { throw edgeCoded(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.") }),
            tokenStore = tokenStore,
            storage = storage,
        )

        val message = signUpFailureMessage(repository)

        assertEquals("회원가입 실패 — 기사 자격 검증: 서비스를 사용할 수 없습니다.", message)
        assertTrue("계정·기사 등록은 끝났다 — 재제출이 검증만 다시 하도록 세션을 남긴다", tokenStore.isLoggedIn())
    }

    @Test
    fun `자격 검증이 네트워크로 실패해도 단계가 드러난다`() = runBlocking {
        val repository = repository(
            driverApi = EdgeDriverApi(onVerify = { throw java.net.SocketTimeoutException("timeout") }),
        )

        assertEquals("회원가입 실패 — 기사 자격 검증: timeout", signUpFailureMessage(repository))
    }

    // ── 성공 경로의 표시 이름 ──────────────────────────────────────────────

    /** 닉네임이 비면 실명으로 인사한다 — 기본값("기사")으로 덮어 익명 취급하지 않는다 */
    @Test
    fun `닉네임이 비면 실명을 표시 이름으로 쓴다`() = runBlocking {
        val repository = repository()

        val result = repository.signUp(form.copy(nickname = ""))

        assertEquals("박기사", result.driverName)
    }

    @Test
    fun `가입 성공은 로그인 토큰을 저장소까지 반영한다`() = runBlocking {
        val storage = EdgeStorage()
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(
            authApi = EdgeAuthApi(onLogin = { TokenResponseDto("access-new", "refresh-new") }),
            tokenStore = tokenStore,
            storage = storage,
        )

        repository.signUp(form)

        assertEquals("앱이 죽어도 가입 직후 세션으로 복구돼야 한다", SessionTokens("access-new", "refresh-new"), storage.tokens)
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private fun repository(
        authApi: EdgeAuthApi = EdgeAuthApi(),
        driverApi: EdgeDriverApi = EdgeDriverApi(),
        tokenStore: TokenStore = TokenStore(),
        storage: EdgeStorage = EdgeStorage(),
    ) = RemoteDriverRepository(
        authApi = authApi,
        driverApi = driverApi,
        dispatchApi = EdgeDispatchApi(),
        tokenStore = tokenStore,
        fallback = DummyDriverRepository(),
        sessionStorage = storage,
    )

    private suspend fun signUpFailureMessage(repository: RemoteDriverRepository): String {
        val error = runCatching { repository.signUp(form) }.exceptionOrNull()
            ?: error("가입이 실패해야 하는 시나리오인데 성공했다")
        return error.message.orEmpty()
    }
}
