package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.PartyMembersApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.PartyDetailDto
import com.moyeota.driver.data.remote.dto.PartyMemberDto
import com.moyeota.driver.data.remote.dto.PartySummaryDto
import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
import com.moyeota.driver.data.remote.dto.RegisterDriverRequestDto
import com.moyeota.driver.data.remote.dto.RegisterFcmTokenRequestDto
import com.moyeota.driver.data.remote.dto.RegisterVehicleRequestDto
import com.moyeota.driver.data.remote.dto.TokenRequestDto
import com.moyeota.driver.data.remote.dto.TokenResponseDto
import com.moyeota.driver.data.remote.dto.UserLoginRequestDto
import com.moyeota.driver.data.remote.dto.UserRegisterRequestDto
import com.moyeota.driver.data.remote.dto.UserResponseDto
import com.moyeota.driver.data.session.DriverSessionStorage
import com.moyeota.driver.data.session.SessionTokens
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPhase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * 앱 재실행 복구([RemoteDriverRepository.restoreSession]) 판정 검증.
 *
 * 핵심 계약: **저장(토큰·활성 partyId)을 지우는 것은 "확인된 종료"뿐**이다.
 * 네트워크 실패로 저장을 지우면 운행 중인 기사가 복구 기회를 영영 잃는다 —
 * 그래서 실패 종류별로 저장이 남는지/지워지는지를 전부 못 박는다.
 */
class RemoteDriverRepositorySessionTest {

    private val me = DriverResultDto(id = 7, userId = 1, name = "박기사", status = "VERIFIED", callEnabled = true)

    private val assignedParty = PartyDetailDto(
        id = 42,
        departureLat = 37.4980,
        departureLng = 127.0276,
        destinationLat = 37.3948,
        destinationLng = 127.1112,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        currentMembers = 2,
        status = "DRIVER_ASSIGNED",
        members = listOf(PartyMemberDto("승객검D417"), PartyMemberDto("모여타짱")),
        estimateFare = 18_400,
        estimateTime = 25,
        taxiDriverId = 7,   // = me.id
    )

    private val callParty = PartySummaryDto(
        id = 42,
        departureLatitude = 37.4980,
        departureLongitude = 127.0276,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        memberCount = 2,
        estimatedFare = 18_400,
    )

    // ── 세션 없음 · 만료 ──────────────────────────────────────────────

    @Test
    fun `저장된 토큰이 없으면 서버를 부르지 않고 로그인 화면으로 보낸다`() = runBlocking {
        // 토큰 없음 + 파티 id 만 남은 상태 — 복구를 시도하면 401 폭풍이 난다
        val storage = FakeSessionStorage(tokens = null, activePartyId = 42)
        val repository = repository(storage, driverApi = SessionDriverApi { error("호출되면 안 된다") })

        val restored = repository.restoreSession()

        assertFalse(restored.loggedIn)
        assertNull(restored.ongoingTrip)
    }

    @Test
    fun `세션 만료(401)는 토큰만 비우고 활성 파티는 남긴다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(storage, tokenStore, driverApi = SessionDriverApi { throw http(401) })

        val restored = repository.restoreSession()

        assertFalse(restored.loggedIn)
        assertNull(restored.ongoingTrip)
        assertNull("재발급까지 실패한 세션 — 토큰을 남겨두면 매 요청이 401", storage.tokens)
        assertFalse(tokenStore.isLoggedIn())
        // 운행 중 토큰이 만료됐을 뿐이다 — 재로그인하면 login() 이 이 파티로 운행을 되살린다
        assertEquals("세션 만료로 운행을 잃으면 안 된다", 42L, storage.activePartyId)
    }

    @Test
    fun `파티 조회 401 도 토큰만 비우고 활성 파티는 남긴다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(
            storage,
            tokenStore,
            partyMembersApi = SessionPartyApi { throw http(401) },
        )

        val restored = repository.restoreSession()

        assertFalse(restored.loggedIn)
        assertNull(storage.tokens)
        assertEquals(42L, storage.activePartyId)
    }

    @Test
    fun `기사 미등록(404)이면 저장을 비우고 재로그인으로 보낸다`() = runBlocking {
        // 계정은 있지만 기사 레코드가 없다 — 홈에 들여보내면 이후 모든 기사 API 가 404 로 깨진다
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val repository = repository(storage, driverApi = SessionDriverApi { throw http(404) })

        val restored = repository.restoreSession()

        assertFalse(restored.loggedIn)
        assertNull(storage.tokens)
        assertNull(storage.activePartyId)
    }

    // ── 네트워크 실패: 저장 보존 ──────────────────────────────────────

    @Test
    fun `세션 확인이 네트워크로 실패하면 저장을 지키고 홈에 착지한다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        var attempts = 0
        val repository = repository(
            storage,
            driverApi = SessionDriverApi { attempts++; throw SocketTimeoutException("timeout") },
        )

        val restored = repository.restoreSession()

        assertTrue("토큰은 살아 있다 — 재로그인시키지 않는다", restored.loggedIn)
        assertNull(restored.ongoingTrip)
        assertEquals("콜드 스타트 대비 재시도", RemoteDriverRepository.RESTORE_MAX_ATTEMPTS, attempts)
        assertEquals("다음 실행에서 다시 복구해야 한다", 42L, storage.activePartyId)
        assertEquals(SessionTokens("a", "r"), storage.tokens)
    }

    @Test
    fun `서버 오류(5xx)도 재시도하고 저장을 지킨다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        var attempts = 0
        val repository = repository(storage, driverApi = SessionDriverApi { attempts++; throw http(502) })

        val restored = repository.restoreSession()

        assertTrue(restored.loggedIn)
        assertEquals(RemoteDriverRepository.RESTORE_MAX_ATTEMPTS, attempts)
        assertEquals(42L, storage.activePartyId)
    }

    @Test
    fun `파티 조회가 네트워크로 실패하면 저장을 지킨다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        var attempts = 0
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { attempts++; throw SocketTimeoutException("timeout") },
        )

        val restored = repository.restoreSession()

        assertTrue(restored.loggedIn)
        assertNull(restored.ongoingTrip)
        assertEquals(RemoteDriverRepository.RESTORE_MAX_ATTEMPTS, attempts)
        assertEquals(42L, storage.activePartyId)
    }

    // ── 저장된 운행 없음 ──────────────────────────────────────────────

    @Test
    fun `저장된 파티가 없으면 홈으로 보내고 기사 이름은 실데이터로 채운다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = null)
        val repository = repository(storage, partyMembersApi = SessionPartyApi { error("호출되면 안 된다") })

        val restored = repository.restoreSession()

        assertTrue(restored.loggedIn)
        assertNull(restored.ongoingTrip)
        // drivers/me 에서 얻은 이름을 캐시해 홈 인사말이 첫 프레임부터 실이름으로 뜬다
        assertEquals("박기사", repository.getHomeSummary().driverName)
    }

    // ── 복구 성공 ─────────────────────────────────────────────────────

    @Test
    fun `DRIVER_ASSIGNED 는 픽업 이동 운행으로 복구된다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val repository = repository(storage, partyMembersApi = SessionPartyApi { partyId ->
            assertEquals(42L, partyId)
            assignedParty
        })

        val restored = repository.restoreSession()

        val trip = requireNotNull(restored.ongoingTrip)
        assertTrue(restored.loggedIn)
        assertEquals("42", trip.id)
        assertEquals(TripPhase.ASSIGNED, trip.phase)
        assertEquals(0, trip.nextStopIndex)
        assertFalse(trip.passengers.any { it.boarded })
        assertEquals(listOf("승객검D417", "모여타짱"), trip.passengers.map { it.maskedName })
        // 복구된 운행이 캐시에 심어져 이후 운행 화면·상태 전이가 이어서 동작한다
        assertEquals(trip, repository.getActiveTrip())
        assertEquals("복구 성공은 저장을 유지한다", 42L, storage.activePartyId)
    }

    @Test
    fun `IN_RIDE 는 운행 중으로 복구되고 곧바로 운행 완료까지 이어진다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        var completedFare: Int? = null
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { assignedParty.copy(status = "IN_RIDE") },
            dispatchApi = SessionDispatchApi(onComplete = { completedFare = it }),
        )

        val trip = requireNotNull(repository.restoreSession().ongoingTrip)

        assertEquals(TripPhase.IN_TRIP, trip.phase)
        assertTrue("서버 IN_RIDE = 파티 전원 탑승", trip.passengers.all { it.boarded })
        assertEquals(StopKind.DROPOFF, trip.stops[trip.nextStopIndex].kind)

        // 복구된 트립 id 로 서버 운행 완료가 그대로 나간다 (더미 강등 아님)
        val fare = repository.submitFinalFare(trip.id, 18_400)

        assertEquals(18_400, completedFare)
        assertEquals(18_400, fare.meterFare)
        assertNull("운행이 끝났으니 복구 대상에서 내린다", storage.activePartyId)
        assertNull(repository.getActiveTrip())
    }

    // ── 복구하지 않는 경우: 저장 정리 ─────────────────────────────────

    @Test
    fun `이미 끝난 파티는 저장을 지우고 홈으로 보낸다`() = runBlocking {
        listOf("FINISHED", "CANCELED", "MATCHING").forEach { status ->
            val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
            val repository = repository(
                storage,
                partyMembersApi = SessionPartyApi { assignedParty.copy(status = status) },
            )

            val restored = repository.restoreSession()

            assertTrue(status, restored.loggedIn)
            assertNull(status, restored.ongoingTrip)
            assertNull("$status — 다시 조회할 이유가 없다", storage.activePartyId)
        }
    }

    @Test
    fun `다른 기사에게 배정된 파티는 복구하지 않는다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { assignedParty.copy(taxiDriverId = 99) },
        )

        val restored = repository.restoreSession()

        assertTrue(restored.loggedIn)
        assertNull("남의 운행 화면을 띄우면 안 된다", restored.ongoingTrip)
        assertNull(storage.activePartyId)
    }

    @Test
    fun `배정 전 파티(taxiDriverId 없음)도 복구하지 않는다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { assignedParty.copy(taxiDriverId = null, status = "MATCHING") },
        )

        assertNull(repository.restoreSession().ongoingTrip)
        assertNull(storage.activePartyId)
    }

    @Test
    fun `서버에 없는 파티(404)는 저장에서 지운다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val repository = repository(storage, partyMembersApi = SessionPartyApi { throw http(404) })

        val restored = repository.restoreSession()

        assertTrue("파티가 없을 뿐 세션은 멀쩡하다", restored.loggedIn)
        assertNull(restored.ongoingTrip)
        assertNull(storage.activePartyId)
        assertEquals("토큰까지 지우면 안 된다", SessionTokens("a", "r"), storage.tokens)
    }

    // ── 재로그인 직후 운행 재수화 (베스트에포트) ──────────────────────

    @Test
    fun `로그인 성공 직후 저장된 파티로 운행이 되살아난다`() = runBlocking {
        // 세션 만료로 로그인 화면에 떨어진 기사가 다시 로그인한 상황 — 토큰은 없고 파티 id 만 남아 있다
        val storage = FakeSessionStorage(tokens = null, activePartyId = 42)
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { assignedParty.copy(status = "IN_RIDE") },
        )

        val result = repository.login("driver01", "pw")

        assertEquals("박기사", result.driverName)
        // 화면은 로그인 성공 후 이 값으로 운행 화면 복귀를 판단한다
        val trip = requireNotNull(repository.getActiveTrip())
        assertEquals("42", trip.id)
        assertEquals(TripPhase.IN_TRIP, trip.phase)
        assertTrue(trip.passengers.all { it.boarded })
        assertEquals(42L, storage.activePartyId)
    }

    @Test
    fun `파티 조회가 실패해도 로그인 자체는 성공한다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = null, activePartyId = 42)
        val repository = repository(
            storage,
            partyMembersApi = SessionPartyApi { throw SocketTimeoutException("timeout") },
        )

        val result = repository.login("driver01", "pw")

        assertEquals("재수화는 부가 기능 — 로그인을 막으면 안 된다", "박기사", result.driverName)
        assertNull(repository.getActiveTrip())
        assertEquals("저장은 남아 다음 기회에 복구된다", 42L, storage.activePartyId)
    }

    @Test
    fun `저장된 파티가 없으면 로그인은 파티 API 를 부르지 않는다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = null, activePartyId = null)
        val repository = repository(storage, partyMembersApi = SessionPartyApi { error("호출되면 안 된다") })

        repository.login("driver01", "pw")

        assertNull(repository.getActiveTrip())
    }

    // ── 저장 시점 (수락 · 완료 · 로그아웃) ────────────────────────────

    @Test
    fun `콜 수락 성공 시 파티 id 를 단말에 남긴다`() = runBlocking {
        val storage = FakeSessionStorage()
        val repository = repository(storage, dispatchApi = SessionDispatchApi(partyDetail = { callParty }))

        repository.acceptCall("42")

        assertEquals("이 값이 다음 실행의 복구 열쇠다", 42L, storage.activePartyId)
    }

    @Test
    fun `로그아웃은 토큰과 활성 파티를 함께 비운다`() = runBlocking {
        val storage = FakeSessionStorage(tokens = SessionTokens("a", "r"), activePartyId = 42)
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(storage, tokenStore)

        repository.logout()

        assertNull(storage.tokens)
        assertNull(storage.activePartyId)
        assertFalse(tokenStore.isLoggedIn())
    }

    // ── 도우미 ────────────────────────────────────────────────────────

    /** 저장소를 붙인 TokenStore 로 조립한다 — 로그인 여부는 [FakeSessionStorage.tokens] 가 결정한다 */
    private fun repository(
        storage: FakeSessionStorage,
        tokenStore: TokenStore = TokenStore().apply { attachPersistence(storage) },
        driverApi: DriverApi = SessionDriverApi { me },
        partyMembersApi: PartyMembersApi = SessionPartyApi { assignedParty },
        dispatchApi: DispatchApi = SessionDispatchApi(),
    ): RemoteDriverRepository = RemoteDriverRepository(
        authApi = SessionAuthApi(),
        driverApi = driverApi,
        dispatchApi = dispatchApi,
        tokenStore = tokenStore,
        fallback = DummyDriverRepository(),
        partyMembersApi = partyMembersApi,
        sessionStorage = storage,
    )

    private fun http(code: Int) = HttpException(
        Response.error<PartyDetailDto>(
            code,
            """{"code":"ERROR","message":"서버 메시지"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )
}

/** 인메모리 세션 저장소 — 실제 구현(SharedPreferences)의 읽기/쓰기/삭제 계약만 흉내 낸다 */
private class FakeSessionStorage(
    var tokens: SessionTokens? = null,
    var activePartyId: Long? = null,
) : DriverSessionStorage {
    override fun readTokens(): SessionTokens? = tokens
    override fun writeTokens(accessToken: String, refreshToken: String) {
        tokens = SessionTokens(accessToken, refreshToken)
    }

    override fun clearTokens() {
        tokens = null
    }

    override fun readActivePartyId(): Long? = activePartyId
    override fun writeActivePartyId(partyId: Long) {
        activePartyId = partyId
    }

    override fun clearActivePartyId() {
        activePartyId = null
    }
}

private class SessionDriverApi(private val onGetMe: () -> DriverResultDto) : DriverApi {
    override suspend fun register(body: RegisterDriverRequestDto): DriverResultDto = unusedSession()
    override suspend fun verify() = unusedSession()
    override suspend fun registerVehicle(body: RegisterVehicleRequestDto) = unusedSession()
    override suspend fun enableCall() = unusedSession()
    override suspend fun disableCall() = unusedSession()
    override suspend fun getMe(): DriverResultDto = onGetMe()
    override suspend fun registerFcmToken(body: RegisterFcmTokenRequestDto) = unusedSession()
    override suspend fun removeFcmToken() = unusedSession()
}

private class SessionPartyApi(private val respond: (Long) -> PartyDetailDto) : PartyMembersApi {
    override suspend fun getPartyDetail(partyId: Long): PartyDetailDto = respond(partyId)
}

private class SessionDispatchApi(
    private val partyDetail: () -> PartySummaryDto = { unusedSession() },
    private val onComplete: (Int) -> Unit = { unusedSession() },
) : DispatchApi {
    override suspend fun goOnline(body: LocationReportRequestDto) = unusedSession()
    override suspend fun goOffline() = unusedSession()
    override suspend fun reportLocation(body: LocationReportRequestDto) = unusedSession()
    override suspend fun acceptCall(partyId: Long) = Unit
    override suspend fun rejectCall(partyId: Long) = unusedSession()
    override suspend fun callStatus(partyId: Long): CallStatusResponseDto = unusedSession()
    override suspend fun getPartyDetail(partyId: Long): PartySummaryDto = partyDetail()
    override suspend fun arrive(partyId: Long) = unusedSession()
    override suspend fun board(partyId: Long) = unusedSession()
    override suspend fun complete(partyId: Long, body: CompleteRideRequestDto) = onComplete(body.fare)
}

private class SessionAuthApi : AuthApi {
    override suspend fun register(body: UserRegisterRequestDto): UserResponseDto = unusedSession()
    override suspend fun login(body: UserLoginRequestDto): TokenResponseDto =
        TokenResponseDto(accessToken = "access-new", refreshToken = "refresh-new")

    override fun reissue(body: TokenRequestDto): retrofit2.Call<TokenResponseDto> = unusedSession()
    override suspend fun logout(body: TokenRequestDto) = Unit   // 로그아웃 검증에서 실제로 호출된다
    override suspend fun checkPhone(body: PhoneCheckRequestDto): PhoneCheckResponseDto = unusedSession()
    override suspend fun getMyInfo(bearer: String): UserResponseDto = unusedSession()
}

private fun unusedSession(): Nothing = error("이 테스트에서 호출되지 않아야 하는 API")
