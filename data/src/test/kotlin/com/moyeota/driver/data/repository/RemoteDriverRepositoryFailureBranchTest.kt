package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.AppConfigResponseDto
import com.moyeota.driver.data.session.SessionTokens
import com.moyeota.driver.domain.model.CallException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/**
 * "모르는 실패로 사용자 상태를 파괴하지 않는다"의 **경계값** 보강.
 *
 * 기존 테스트는 두 대표 모양(코드가 있는 404 / Spring 기본 404)을 못 박았다. 실전에서 오는 404 는 더 많다 —
 * 다른 도메인 코드가 실린 404, body 가 아예 없는 404(프록시), HTML 오류 페이지(CDN). 이 셋은 전부
 * "기사 레코드가 없다"를 **말해 주지 않으므로** 저장을 보존해야 한다. 하나라도 미등록으로 읽으면
 * 운행 중인 기사가 매 실행 로그아웃된다.
 *
 * 함께 보강하는 것:
 * - 수락 409 소유권 되묻기(B6)의 **확인 불가** 경로 — 모르면 "내 것"이라 주장하지 않는다
 * - 로그아웃의 서버 실패 — 서버가 죽어도 기사는 로그아웃할 수 있어야 한다
 * - 서비스 게이트(`GET /api/v1/config`)의 폴백과 취소 전파
 */
class RemoteDriverRepositoryFailureBranchTest {

    // ── 404 분기: 코드 없는/다른 코드의 404 는 전부 "모름" ──────────────────

    /**
     * `DRIVER_NOT_FOUND`·`USER103` 은 **기사 레코드 존재 여부를 말해 주지 않는다**.
     * 확인된 종료는 `GET /drivers/me` 가 미등록 유저에게 주는 `DRIVER_NOT_REGISTERED` 하나뿐이다.
     * (구현 주석은 DRIVER_NOT_FOUND 도 종료라고 적어 두었지만 판정은 그렇지 않다 — 판정 쪽이 옳다)
     */
    @Test
    fun `다른 코드의 404 는 기사 미등록이 아니라 저장을 보존한다`() = runBlocking {
        listOf("DRIVER_NOT_FOUND", "USER103", "PARTY_NOT_FOUND").forEach { code ->
            val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"), activePartyId = 42)
            val repository = repository(
                storage = storage,
                driverApi = EdgeDriverApi(onGetMe = { throw edgeCoded(404, code, "없습니다.") }),
            )

            val restored = repository.restoreSession()

            assertTrue(code, restored.loggedIn)
            assertNotNull(code, storage.tokens)
            assertEquals(code, 42L, storage.activePartyId)
        }
    }

    /** body 가 없는 404(프록시)·HTML 404(CDN)도 code 를 읽을 수 없으니 "모름"이다 */
    @Test
    fun `본문이 없거나 JSON 이 아닌 404 도 저장을 보존한다`() = runBlocking {
        listOf<() -> HttpException>({ edgeEmptyBody(404) }, { edgeHtmlNotFound() }).forEach { makeError ->
            val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"), activePartyId = 42)
            val repository = repository(storage = storage, driverApi = EdgeDriverApi(onGetMe = { throw makeError() }))

            val restored = repository.restoreSession()

            assertTrue(restored.loggedIn)
            assertNotNull("파싱 실패를 '미등록'으로 읽으면 매 실행 로그아웃된다", storage.tokens)
            assertEquals(42L, storage.activePartyId)
        }
    }

    /**
     * 파티 복구 쪽 404 도 같은 규칙이다 — 지워도 되는 것은 `PARTY_NOT_FOUND` 뿐이고,
     * 경로 미존재(Spring 기본 404)나 다른 코드의 404 는 저장을 지우면 복구 기회가 영영 사라진다.
     */
    @Test
    fun `파티 조회의 코드 없는 404 는 활성 파티를 지우지 않는다`() = runBlocking {
        listOf<() -> HttpException>(
            { edgeSpringNotFound("/api/v1/matching/rooms/42") },
            { edgeCoded(404, "DRIVER_NOT_FOUND", "기사를 찾을 수 없습니다.") },
        ).forEach { makeError ->
            val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"), activePartyId = 42)
            val repository = repository(
                storage = storage,
                partyMembersApi = EdgePartyApi { throw makeError() },
            )

            val restored = repository.restoreSession()

            assertTrue("세션은 멀쩡하다", restored.loggedIn)
            assertNull(restored.ongoingTrip)
            assertEquals("확인된 '파티 없음'이 아니면 보존한다", 42L, storage.activePartyId)
            assertNotNull(storage.tokens)
        }
    }

    /** 로그인 중 만난 body 없는 404 도 가입 안내가 아니라 서버 사정으로 안내해야 한다 */
    @Test
    fun `로그인 중 본문 없는 404 는 서버 설정을 읽어 원인을 좁힌다`() = runBlocking {
        val authApi = EdgeAuthApi(onConfig = { AppConfigResponseDto(taxiEnabled = false) })
        val repository = repository(
            authApi = authApi,
            driverApi = EdgeDriverApi(onGetMe = { throw edgeEmptyBody(404) }),
        )

        val message = loginFailureMessage(repository)

        assertFalse("중복 가입을 유도하면 안 된다", message.contains("회원가입을 진행"))
        assertTrue(message, message.contains("택시 서비스가 꺼져"))
        assertEquals("원인을 좁히려 설정을 한 번 읽는다", 1, authApi.configCalls)
    }

    /** 설정 조회까지 실패하면 낙관(열림) 폴백이라 "일시적 오류" 문구로 떨어진다 */
    @Test
    fun `로그인 중 404 안내는 설정 조회가 실패해도 안내 문구를 만든다`() = runBlocking {
        val repository = repository(
            authApi = EdgeAuthApi(onConfig = { throw SocketTimeoutException("timeout") }),
            driverApi = EdgeDriverApi(onGetMe = { throw edgeSpringNotFound() }),
        )

        val message = loginFailureMessage(repository)

        assertTrue(message, message.contains("기사 기능을 사용할 수 없어요"))
        assertFalse(message, message.contains("회원가입을 진행"))
    }

    // ── 수락 409 소유권 되묻기: 확인 불가 경로 ──────────────────────────────

    /** 파티 조회 자체가 실패하면 소유권을 모른다 → 409 는 그대로 마감이고 저장도 건드리지 않는다 */
    @Test
    fun `수락 409 에 파티 조회가 실패하면 마감으로 남는다`() = runBlocking {
        val storage = EdgeStorage()
        val partyApi = EdgePartyApi { throw edgeCoded(500, "INTERNAL_ERROR", "서버 오류") }
        val repository = repository(
            storage = storage,
            dispatchApi = EdgeDispatchApi(onAccept = { throw edgeCoded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = partyApi,
        )

        val error = assertCallFailure { repository.acceptCall("42") }

        assertEquals(CallException.Kind.CLOSED, error.kind)
        assertEquals(1, partyApi.calls)
        assertNull("모르는 채로 운행을 저장하면 남의 파티를 복구할 수 있다", storage.activePartyId)
        assertNull(repository.getActiveTrip())
    }

    /** 내 기사 id 를 모르면 비교 자체가 불가능하다 — 파티를 물어볼 필요도 없다 */
    @Test
    fun `수락 409 에 내 기사 정보 조회가 실패하면 파티를 묻지 않는다`() = runBlocking {
        val partyApi = EdgePartyApi()
        val repository = repository(
            driverApi = EdgeDriverApi(onGetMe = { throw edgeSpringNotFound() }),
            dispatchApi = EdgeDispatchApi(onAccept = { throw edgeCoded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = partyApi,
        )

        val error = assertCallFailure { repository.acceptCall("42") }

        assertEquals(CallException.Kind.CLOSED, error.kind)
        assertEquals("비교할 내 id 가 없으면 조회는 무의미하다", 0, partyApi.calls)
    }

    /** 아직 아무 기사도 배정되지 않은 파티(taxiDriverId=null)는 내 것이 아니다 */
    @Test
    fun `수락 409 에 배정 전 파티는 내 운행으로 주장하지 않는다`() = runBlocking {
        val storage = EdgeStorage()
        val repository = repository(
            storage = storage,
            dispatchApi = EdgeDispatchApi(onAccept = { throw edgeCoded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = EdgePartyApi { edgeMyParty.copy(taxiDriverId = null, status = "MATCHING") },
        )

        assertEquals(CallException.Kind.CLOSED, assertCallFailure { repository.acceptCall("42") }.kind)
        assertNull(storage.activePartyId)
    }

    /** 409 가 아닌 실패는 소유권과 무관하다 — 헛된 조회 두 번을 더 하지 않는다 */
    @Test
    fun `수락이 409 가 아니면 소유권을 되묻지 않는다`() = runBlocking {
        val driverApi = EdgeDriverApi()
        val partyApi = EdgePartyApi()
        val repository = repository(
            driverApi = driverApi,
            dispatchApi = EdgeDispatchApi(onAccept = { throw edgeCoded(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다.") }),
            partyMembersApi = partyApi,
        )

        val error = assertCallFailure { repository.acceptCall("42") }

        assertEquals(CallException.Kind.SERVER, error.kind)
        assertEquals("수락하지 못했어요 · 서버 오류가 발생했습니다.", error.message)
        assertTrue("재시도 가치가 있는 서버 오류다", error.retryable)
        assertEquals(0, partyApi.calls)
        assertEquals(0, driverApi.getMeCalls)
    }

    /** 소유권으로 복원된 운행도 정상 수락과 같은 뒤처리를 받아야 한다 — 콜 피드 소거 + 활성 파티 저장 */
    @Test
    fun `소유권으로 복원한 운행도 콜 피드에서 내려가고 저장된다`() = runBlocking {
        val storage = EdgeStorage()
        val repository = repository(
            storage = storage,
            dispatchApi = EdgeDispatchApi(onAccept = { throw edgeCoded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = EdgePartyApi(),
        )
        repository.seedCallPreview("42", "강남역 2번 출구", "판교역 1번 출구", 2, 18_400)

        val trip = repository.acceptCall("42")

        assertEquals("42", trip.id)
        assertTrue("수락된 콜이 목록에 남으면 또 수락하려 든다", repository.getCalls().isEmpty())
        assertEquals(42L, storage.activePartyId)
        assertEquals(trip, repository.getActiveTrip())
    }

    // ── 로그아웃: 서버가 죽어도 로컬은 비운다 ───────────────────────────────

    @Test
    fun `서버 로그아웃이 실패해도 로컬 세션은 비워진다`() = runBlocking {
        val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"), activePartyId = 42)
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(
            storage = storage,
            tokenStore = tokenStore,
            authApi = EdgeAuthApi(onLogout = { throw edgeCoded(500, "INTERNAL_ERROR", "서버 오류") }),
        )

        repository.logout()

        assertFalse("서버가 죽어도 기사는 로그아웃할 수 있어야 한다", tokenStore.isLoggedIn())
        assertNull(storage.tokens)
        assertNull(storage.activePartyId)
    }

    /** 서버 무효화는 저장된 refreshToken 으로 나간다 — 엉뚱한 값이면 남의 세션이 살아 있게 된다 */
    @Test
    fun `로그아웃은 저장된 refreshToken 으로 서버 세션을 무효화한다`() = runBlocking {
        val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"))
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val authApi = EdgeAuthApi()
        val driverApi = EdgeDriverApi()
        val repository = repository(storage = storage, tokenStore = tokenStore, authApi = authApi, driverApi = driverApi)

        repository.logout()

        assertEquals(listOf("refresh-1"), authApi.logoutBodies)
        assertEquals("콜 푸시가 계속 오면 남의 콜이 뜬다", 1, driverApi.removeFcmCalls)
    }

    @Test
    fun `미로그인 로그아웃은 FCM 해제도 서버 무효화도 호출하지 않는다`() = runBlocking {
        val authApi = EdgeAuthApi()
        val driverApi = EdgeDriverApi()
        val repository = repository(authApi = authApi, driverApi = driverApi)

        repository.logout()

        assertEquals(0, driverApi.removeFcmCalls)
        assertTrue(authApi.logoutBodies.isEmpty())
    }

    /** 운행 중 로그아웃 — 다음 사용자(또는 다음 로그인)에게 이전 운행이 보이면 안 된다 */
    @Test
    fun `로그아웃은 진행 중 운행 캐시까지 비운다`() = runBlocking {
        val storage = EdgeStorage(tokens = SessionTokens("access-1", "refresh-1"))
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(storage = storage, tokenStore = tokenStore)
        assertNotNull(repository.acceptCall("42"))
        assertEquals(42L, storage.activePartyId)

        repository.logout()

        assertNull("로그아웃 후 운행 화면으로 돌아가면 안 된다", repository.getActiveTrip())
        assertNull(storage.activePartyId)
        assertNull(storage.tokens)
    }

    // ── 서비스 게이트 (GET /api/v1/config) ──────────────────────────────────

    /** 게이트는 앱 전체를 막는다 — 5xx·타임아웃으로 정상 기사를 "준비 중" 화면에 가두지 않는다 */
    @Test
    fun `서버 설정 5xx·타임아웃도 열림으로 폴백한다`() = runBlocking {
        listOf<() -> Nothing>(
            { throw edgeCoded(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.") },
            { throw SocketTimeoutException("timeout") },
            { throw IllegalStateException("직렬화 실패") },
        ).forEach { raise ->
            val repository = repository(authApi = EdgeAuthApi(onConfig = { raise() }))

            assertTrue(repository.isTaxiServiceEnabled())
        }
    }

    /** 폴백이 넓어도 취소까지 "열림"으로 바꾸면 구조적 동시성이 깨진다 */
    @Test
    fun `서버 설정 조회의 취소는 열림으로 뭉개지 않는다`() = runBlocking {
        val repository = repository(authApi = EdgeAuthApi(onConfig = { throw CancellationException("화면 이탈") }))

        val error = runCatching { repository.isTaxiServiceEnabled() }.exceptionOrNull()

        assertTrue("취소는 취소로 전파돼야 한다: $error", error is CancellationException)
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private fun repository(
        storage: EdgeStorage = EdgeStorage(),
        tokenStore: TokenStore = TokenStore().apply { attachPersistence(storage) },
        authApi: EdgeAuthApi = EdgeAuthApi(),
        driverApi: EdgeDriverApi = EdgeDriverApi(),
        dispatchApi: EdgeDispatchApi = EdgeDispatchApi(),
        partyMembersApi: EdgePartyApi? = null,
    ) = RemoteDriverRepository(
        authApi = authApi,
        driverApi = driverApi,
        dispatchApi = dispatchApi,
        tokenStore = tokenStore,
        fallback = DummyDriverRepository(),
        partyMembersApi = partyMembersApi,
        sessionStorage = storage,
    )

    private suspend fun loginFailureMessage(repository: RemoteDriverRepository): String {
        val error = runCatching { repository.login("driver01", "test123!@") }.exceptionOrNull()
            ?: error("로그인이 실패해야 하는 시나리오인데 성공했다")
        return error.message.orEmpty()
    }

    private inline fun assertCallFailure(block: () -> Unit): CallException {
        try {
            block()
        } catch (e: CallException) {
            return e
        }
        fail("CallException 이 올라와야 한다")
        error("unreachable")
    }
}
