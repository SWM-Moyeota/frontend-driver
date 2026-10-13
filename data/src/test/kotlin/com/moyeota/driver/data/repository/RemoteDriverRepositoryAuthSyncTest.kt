package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.PartyMembersApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.AppConfigResponseDto
import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckResponseDto
import com.moyeota.driver.data.remote.dto.PartyDetailDto
import com.moyeota.driver.data.remote.dto.PartyMemberDto
import com.moyeota.driver.data.remote.dto.PartySummaryDto
import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckResponseDto
import com.moyeota.driver.data.remote.dto.RegisterDriverRequestDto
import com.moyeota.driver.data.remote.dto.RegisterFcmTokenRequestDto
import com.moyeota.driver.data.remote.dto.RegisterVehicleRequestDto
import com.moyeota.driver.data.remote.dto.SmsSendRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyResponseDto
import com.moyeota.driver.data.remote.dto.TokenRequestDto
import com.moyeota.driver.data.remote.dto.TokenResponseDto
import com.moyeota.driver.data.remote.dto.UserLoginRequestDto
import com.moyeota.driver.data.remote.dto.UserRegisterRequestDto
import com.moyeota.driver.data.remote.dto.UserResponseDto
import com.moyeota.driver.data.session.DriverSessionStorage
import com.moyeota.driver.data.session.SessionTokens
import com.moyeota.driver.domain.model.CallException
import com.moyeota.driver.domain.model.DriverSignUpForm
import com.moyeota.driver.domain.model.TripPhase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 백엔드 HEAD 동기화(계약서 `_workspace/30_sync_contract.md`) 검증 —
 * 문자 인증 가입(A)과 안전성 수정(B)의 **판정 분기**를 못 박는다.
 *
 * 여기 모인 테스트가 지키는 공통 원칙 하나: **"모르는 실패로 사용자 상태를 파괴하지 않는다."**
 * 404 는 두 가지(기사 미등록 / 핸들러 없음)이고, 409 도 두 가지(남이 선점 / 내가 이미 수락)다.
 * 상태코드만 보고 단정하면 각각 "매 실행 로그아웃"과 "배차된 콜을 놓침"이 된다.
 */
class RemoteDriverRepositoryAuthSyncTest {

    private val me = DriverResultDto(id = 7, userId = 1, name = "박기사", status = "VERIFIED", callEnabled = true)

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

    private val callParty = PartySummaryDto(
        id = 42,
        departureLatitude = 37.4980,
        departureLongitude = 127.0276,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        memberCount = 2,
        estimatedFare = 18_400,
    )

    /** 내게 배정된 진행 중 파티 (taxiDriverId = me.id) */
    private val myParty = PartyDetailDto(
        id = 42,
        departureLat = 37.4980,
        departureLng = 127.0276,
        destinationLat = 37.3948,
        destinationLng = 127.1112,
        departure = "강남역 2번 출구",
        destination = "판교역 1번 출구",
        currentMembers = 2,
        status = "DRIVER_ASSIGNED",
        members = listOf(PartyMemberDto("승객하나"), PartyMemberDto("승객둘")),
        estimateFare = 18_400,
        estimateTime = 25,
        taxiDriverId = 7,
    )

    // ── B1. 404 를 "기사 미등록"으로 단정하지 않는다 ─────────────────────────

    /**
     * 배포 서버가 `taxiEnabled=false` 면 기사 컨트롤러가 등록조차 되지 않아 Spring 기본 404 가 온다.
     * 예전 구현은 이것을 미등록으로 읽고 저장을 비워 **매 실행 로그아웃**시켰다.
     */
    @Test
    fun `code 없는 404 는 저장을 지우지 않고 로그인 상태를 유지한다`() = runBlocking {
        val storage = FakeStorage(tokens = SessionTokens("access", "refresh"), activePartyId = 42)
        val repository = repository(
            storage = storage,
            driverApi = FakeDriverApi(onGetMe = { throw springNotFound() }),
        )

        val restored = repository.restoreSession()

        assertTrue("핸들러 없음은 '미등록'이 아니다 — 세션을 유지한다", restored.loggedIn)
        assertNotNull("저장된 토큰이 남아야 다음 실행에서 다시 시도할 수 있다", storage.tokens)
        assertEquals("활성 파티도 보존된다", 42L, storage.activePartyId)
    }

    @Test
    fun `DRIVER_NOT_REGISTERED 404 는 확인된 종료라 저장을 지운다`() = runBlocking {
        val storage = FakeStorage(tokens = SessionTokens("access", "refresh"), activePartyId = 42)
        val repository = repository(
            storage = storage,
            driverApi = FakeDriverApi(onGetMe = { throw coded(404, "DRIVER_NOT_REGISTERED", "기사로 등록되지 않은 유저입니다.") }),
        )

        val restored = repository.restoreSession()

        assertFalse(restored.loggedIn)
        assertNull("기사 미등록은 이 계정으로 기사 API 를 쓸 수 없음이 확인된 상태다", storage.tokens)
        assertNull(storage.activePartyId)
    }

    @Test
    fun `로그인 중 DRIVER_NOT_REGISTERED 404 는 가입 안내 문구가 된다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { throw coded(404, "DRIVER_NOT_REGISTERED", "기사로 등록되지 않은 유저입니다.") }),
        )

        val message = loginFailureMessage(repository)

        assertTrue(message, message.contains("기사로 가입되지 않은 계정"))
    }

    /** 같은 404 라도 code 가 없으면 "가입하라"가 아니라 "서버 사정"으로 안내해야 한다 */
    @Test
    fun `로그인 중 code 없는 404 는 taxiEnabled false 를 문구에 반영한다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { throw springNotFound() }),
            authApi = FakeAuthApi(taxiEnabled = false),
        )

        val message = loginFailureMessage(repository)

        assertFalse("중복 가입을 유도하면 안 된다", message.contains("회원가입을 진행"))
        assertTrue(message, message.contains("택시 서비스가 꺼져"))
    }

    @Test
    fun `로그인 중 code 없는 404 에 서버 설정이 열려 있으면 일시적 오류로 안내한다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { throw springNotFound() }),
            authApi = FakeAuthApi(taxiEnabled = true),
        )

        val message = loginFailureMessage(repository)

        assertTrue(message, message.contains("기사 기능을 사용할 수 없어요"))
    }

    // ── B6. 수락 409 → 파티 소유권으로 되묻기 ───────────────────────────────

    /**
     * 콜드 스타트로 accept 응답이 유실되면 재시도가 409 CALL_CLOSED 를 맞는다.
     * 서버에는 이미 내게 배차돼 있으므로 "이미 마감된 콜"로 안내하면 배차된 승객을 버리는 셈이다.
     */
    @Test
    fun `수락이 409 라도 내게 배정된 파티면 운행으로 진행한다`() = runBlocking {
        val storage = FakeStorage()
        val repository = repository(
            storage = storage,
            driverApi = FakeDriverApi(onGetMe = { me }),
            dispatchApi = FakeDispatch(partyDetail = { callParty }, onAccept = { throw coded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = FakePartyApi { myParty },
        )

        val trip = repository.acceptCall("42")

        assertEquals("42", trip.id)
        assertEquals(42L, storage.activePartyId)   // 앱이 죽어도 되찾을 수 있게 남긴다
        assertEquals(trip, repository.getActiveTrip())
    }

    @Test
    fun `수락 409 이고 다른 기사에게 배정된 파티면 마감으로 처리한다`() = runBlocking {
        val storage = FakeStorage()
        val repository = repository(
            storage = storage,
            driverApi = FakeDriverApi(onGetMe = { me }),
            dispatchApi = FakeDispatch(partyDetail = { callParty }, onAccept = { throw coded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = FakePartyApi { myParty.copy(taxiDriverId = 99) },
        )

        val error = assertCallFailure { repository.acceptCall("42") }

        assertEquals(CallException.Kind.CLOSED, error.kind)
        assertNull("남의 운행을 내 것으로 저장하면 안 된다", storage.activePartyId)
    }

    /**
     * 수락이 이미 반영된 뒤에는 **콜 상세(dispatch)부터** 409 로 떨어진다 — 서버가 수락 즉시
     * 콜 후보를 비우기 때문이다. 이 지점도 같은 소유권 판정으로 구제해야 실효가 있다.
     */
    @Test
    fun `콜 상세가 409 여도 내 파티면 매칭방 상세로 운행을 복원한다`() = runBlocking {
        val storage = FakeStorage()
        val repository = repository(
            storage = storage,
            driverApi = FakeDriverApi(onGetMe = { me }),
            dispatchApi = FakeDispatch(partyDetail = { throw coded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = FakePartyApi { myParty },
        )

        val trip = repository.acceptCall("42")

        assertEquals("42", trip.id)
        assertEquals(TripPhase.ASSIGNED, trip.phase)
        // 복구 매퍼(PartyRecoveryMappers)를 그대로 재사용하므로 승객 실닉네임까지 실려 온다
        assertEquals(listOf("승객하나", "승객둘"), trip.passengers.map { it.maskedName })
        assertEquals(42L, storage.activePartyId)
    }

    @Test
    fun `이미 끝난 파티는 소유권이 있어도 운행으로 되살리지 않는다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { me }),
            dispatchApi = FakeDispatch(partyDetail = { callParty }, onAccept = { throw coded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = FakePartyApi { myParty.copy(status = "FINISHED") },
        )

        assertEquals(CallException.Kind.CLOSED, assertCallFailure { repository.acceptCall("42") }.kind)
    }

    /** 소유권을 확인할 길이 없으면(매칭방 API 미주입) 함부로 성공으로 바꾸지 않는다 */
    @Test
    fun `소유권을 확인할 수 없으면 409 는 그대로 마감이다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { me }),
            dispatchApi = FakeDispatch(partyDetail = { callParty }, onAccept = { throw coded(409, "CALL_CLOSED", "이미 마감된 콜입니다.") }),
            partyMembersApi = null,
        )

        assertEquals(CallException.Kind.CLOSED, assertCallFailure { repository.acceptCall("42") }.kind)
    }

    // ── B2. 로그아웃 시 FCM 토큰 해제 ───────────────────────────────────────

    /**
     * 해제하지 않으면 서버는 이 단말을 여전히 그 기사로 알고 콜 푸시를 보낸다.
     * 해제는 인증 구간(@CurrentDriver)이라 **토큰을 비우기 전에** 나가야 한다.
     */
    @Test
    fun `로그아웃은 토큰을 비우기 전에 FCM 토큰을 해제한다`() = runBlocking {
        val storage = FakeStorage(tokens = SessionTokens("access", "refresh"))
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        var hadTokenWhenRemoved: Boolean? = null
        val repository = repository(
            storage = storage,
            tokenStore = tokenStore,
            driverApi = FakeDriverApi(
                onGetMe = { me },
                onRemoveFcmToken = { hadTokenWhenRemoved = tokenStore.isLoggedIn() },
            ),
        )

        repository.logout()

        assertEquals("DELETE /drivers/fcm-token 이 호출돼야 한다", true, hadTokenWhenRemoved != null)
        assertEquals("Bearer 가 실리도록 토큰 삭제 전에 호출해야 한다", true, hadTokenWhenRemoved)
        assertFalse(tokenStore.isLoggedIn())
        assertNull(storage.tokens)
    }

    @Test
    fun `FCM 해제가 실패해도 로그아웃은 완료된다`() = runBlocking {
        val storage = FakeStorage(tokens = SessionTokens("access", "refresh"))
        val tokenStore = TokenStore().apply { attachPersistence(storage) }
        val repository = repository(
            storage = storage,
            tokenStore = tokenStore,
            driverApi = FakeDriverApi(onGetMe = { me }, onRemoveFcmToken = { throw springNotFound() }),
        )

        repository.logout()

        assertFalse(tokenStore.isLoggedIn())
        assertNull(storage.tokens)
    }

    @Test
    fun `미로그인 상태의 로그아웃은 서버를 부르지 않는다`() = runBlocking {
        val repository = repository(
            driverApi = FakeDriverApi(onGetMe = { me }, onRemoveFcmToken = { fail("미로그인 상태에서 호출하면 안 된다") }),
        )

        repository.logout()
    }

    // ── B3. register 409 를 코드로 가른다 ───────────────────────────────────

    /** 닉네임 중복은 로그인으로 넘어가도 성공할 수 없다 — 무엇을 고쳐야 하는지 알려야 한다 */
    @Test
    fun `닉네임 중복 409 는 로그인을 시도하지 않고 중단한다`() = runBlocking {
        var loginCalled = false
        val repository = repository(
            authApi = FakeAuthApi(
                onRegister = { throw coded(409, "USER108", "이미 사용 중인 닉네임입니다.") },
                onLogin = { loginCalled = true; TokenResponseDto("a", "r") },
            ),
        )

        val message = signUpFailureMessage(repository)

        assertFalse("성공할 수 없는 로그인을 시도하면 원인이 가려진다", loginCalled)
        assertTrue(message, message.contains("이미 사용 중인 닉네임입니다."))
    }

    @Test
    fun `문자 인증 만료 400 USER207 은 그대로 안내된다`() = runBlocking {
        val repository = repository(
            authApi = FakeAuthApi(onRegister = { throw coded(400, "USER207", "휴대폰 인증이 필요합니다.") }),
        )

        assertTrue(signUpFailureMessage(repository).contains("휴대폰 인증이 필요합니다."))
    }

    /**
     * 아이디 중복(USER101)은 **내 계정일 수 있다** — 기사 등록만 실패해 다시 제출하는 재시도가
     * 정확히 이 모양이다. 그래서 로그인으로 진행하고, 로그인까지 실패했을 때 원인을 바꿔 말한다.
     */
    @Test
    fun `아이디 중복 409 는 로그인으로 진행한다 - 내 계정 재시도 경로`() = runBlocking {
        val repository = repository(
            authApi = FakeAuthApi(onRegister = { throw coded(409, "USER101", "이미 존재하는 아이디입니다.") }),
            driverApi = FakeDriverApi(onGetMe = { me }, onRegisterDriver = { me }),
        )

        val result = repository.signUp(form)

        assertEquals("번개기사", result.driverName)
    }

    @Test
    fun `아이디 중복 뒤 로그인 401 이면 엉뚱한 로그인 실패 대신 중복을 알린다`() = runBlocking {
        val repository = repository(
            authApi = FakeAuthApi(
                onRegister = { throw coded(409, "USER101", "이미 존재하는 아이디입니다.") },
                onLogin = { throw coded(401, "USER102", "아이디나 비밀번호가 다릅니다.") },
            ),
        )

        val message = signUpFailureMessage(repository)

        assertTrue(message, message.contains("이미 사용 중인 아이디"))
        assertFalse("방금 아이디를 만든 사용자에게 아무 단서가 못 된다", message.contains("비밀번호가 다릅니다"))
    }

    // ── A. 문자 인증 · 닉네임 조회 ──────────────────────────────────────────

    @Test
    fun `인증번호 발송 요청은 하이픈 표준형으로 정규화해 보낸다`() = runBlocking {
        var sent: String? = null
        val repository = repository(authApi = FakeAuthApi(onSendSms = { sent = it.phoneNumber }))

        repository.requestPhoneVerification("01012345678")

        assertEquals("010-1234-5678", sent)
    }

    @Test
    fun `발송 실패는 서버 한국어 메시지를 그대로 올린다`() = runBlocking {
        val repository = repository(
            authApi = FakeAuthApi(onSendSms = { throw coded(429, "USER202", "인증번호는 1분에 한 번만 요청할 수 있습니다.") }),
        )

        val error = runCatching { repository.requestPhoneVerification("01012345678") }.exceptionOrNull()

        assertEquals("인증번호는 1분에 한 번만 요청할 수 있습니다.", error?.message)
    }

    @Test
    fun `인증번호 확인은 verificationToken 을 돌려준다`() = runBlocking {
        val repository = repository(authApi = FakeAuthApi(smsToken = "token-xyz"))

        assertEquals("token-xyz", repository.verifyPhoneCode("01012345678", "123456"))
    }

    /** 토큰 없는 200 으로 가입까지 가면 USER207 로 떨어진다 — 여기서 끊어야 원인이 드러난다 */
    @Test
    fun `토큰이 빈 200 응답은 인증 실패로 처리한다`() = runBlocking {
        val repository = repository(authApi = FakeAuthApi(smsToken = ""))

        val error = runCatching { repository.verifyPhoneCode("01012345678", "123456") }.exceptionOrNull()

        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("인증이 완료되지 않았어요"))
    }

    @Test
    fun `닉네임 형식 거절 400 USER107 은 사용 불가로 합친다`() = runBlocking {
        val repository = repository(
            authApi = FakeAuthApi(onCheckNickname = { throw coded(400, "USER107", "닉네임은 2~10자의 한글, 영문, 숫자만 가능합니다.") }),
        )

        assertTrue(repository.isNicknameTaken("!!"))
    }

    @Test
    fun `닉네임 조회 실패는 사용 가능으로 통과시키지 않는다`() = runBlocking {
        val repository = repository(authApi = FakeAuthApi(onCheckNickname = { throw coded(500, "X", "서버 오류") }))

        assertTrue(runCatching { repository.isNicknameTaken("번개기사") }.isFailure)
    }

    @Test
    fun `닉네임은 공백을 제거해 조회한다 - 서버 비교 규칙과 맞춘다`() = runBlocking {
        var asked: String? = null
        val repository = repository(authApi = FakeAuthApi(onCheckNickname = { asked = it.nickname; NicknameCheckResponseDto(false) }))

        repository.isNicknameTaken("  번개기사  ")

        assertEquals("번개기사", asked)
    }

    // ── B7. 서버 설정 ───────────────────────────────────────────────────────

    @Test
    fun `서버 설정의 taxiEnabled 를 그대로 읽는다`() = runBlocking {
        assertFalse(repository(authApi = FakeAuthApi(taxiEnabled = false)).isTaxiServiceEnabled())
        assertTrue(repository(authApi = FakeAuthApi(taxiEnabled = true)).isTaxiServiceEnabled())
    }

    /** 게이트가 네트워크 한 번 흔들렸다고 정상 기사를 앱 밖에 가두면 안 된다 */
    @Test
    fun `서버 설정 조회 실패는 열림으로 폴백한다`() = runBlocking {
        val repository = repository(authApi = FakeAuthApi(onConfig = { throw springNotFound() }))

        assertTrue(repository.isTaxiServiceEnabled())
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private fun repository(
        storage: FakeStorage = FakeStorage(),
        tokenStore: TokenStore = TokenStore().apply { attachPersistence(storage) },
        authApi: AuthApi = FakeAuthApi(),
        driverApi: DriverApi = FakeDriverApi(onGetMe = { me }),
        dispatchApi: DispatchApi = FakeDispatch(),
        partyMembersApi: PartyMembersApi? = null,
    ) = RemoteDriverRepository(
        authApi = authApi,
        driverApi = driverApi,
        dispatchApi = dispatchApi,
        tokenStore = tokenStore,
        fallback = DummyDriverRepository(),
        partyMembersApi = partyMembersApi,
        sessionStorage = storage,
    )

    private suspend fun loginFailureMessage(repository: RemoteDriverRepository): String =
        runCatching { repository.login("driver01", "test123!@") }.exceptionOrNull()?.message
            ?: fail("로그인이 실패해야 하는 시나리오다").let { "" }

    private suspend fun signUpFailureMessage(repository: RemoteDriverRepository): String =
        runCatching { repository.signUp(form) }.exceptionOrNull()?.message
            ?: fail("가입이 실패해야 하는 시나리오다").let { "" }

    private inline fun assertCallFailure(block: () -> Unit): CallException =
        try {
            block()
            fail("CallException 이 떠야 한다")
            error("unreachable")
        } catch (e: CallException) {
            e
        }

    /** 백엔드 공통 에러 바디 `{code, message}` */
    private fun coded(status: Int, code: String, message: String) = HttpException(
        Response.error<Unit>(
            status,
            """{"code":"$code","message":"$message"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )

    /** 핸들러가 없을 때의 Spring 기본 404 — `code` 가 없다 */
    private fun springNotFound() = HttpException(
        Response.error<Unit>(
            404,
            """{"timestamp":"2026-10-13T00:00:00.000+00:00","status":404,"error":"Not Found","path":"/api/v1/drivers/me"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )
}

private class FakeStorage(
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

private class FakeAuthApi(
    private val onRegister: () -> UserResponseDto = { UserResponseDto(uuid = "uuid", name = "번개기사") },
    private val onLogin: () -> TokenResponseDto = { TokenResponseDto("access", "refresh") },
    private val onSendSms: (SmsSendRequestDto) -> Unit = {},
    private val smsToken: String = "token-abc",
    private val onCheckNickname: (NicknameCheckRequestDto) -> NicknameCheckResponseDto = { NicknameCheckResponseDto(false) },
    private val taxiEnabled: Boolean = true,
    private val onConfig: () -> AppConfigResponseDto = { AppConfigResponseDto(taxiEnabled) },
) : AuthApi {
    override suspend fun register(body: UserRegisterRequestDto): UserResponseDto = onRegister()
    override suspend fun login(body: UserLoginRequestDto): TokenResponseDto = onLogin()
    override fun reissue(body: TokenRequestDto): retrofit2.Call<TokenResponseDto> = error("미사용")
    override suspend fun logout(body: TokenRequestDto) = Unit
    override suspend fun checkPhone(body: PhoneCheckRequestDto): PhoneCheckResponseDto = PhoneCheckResponseDto(false)
    override suspend fun sendSmsCode(body: SmsSendRequestDto) = onSendSms(body)
    override suspend fun verifySmsCode(body: SmsVerifyRequestDto): SmsVerifyResponseDto = SmsVerifyResponseDto(smsToken)
    override suspend fun checkNickname(body: NicknameCheckRequestDto): NicknameCheckResponseDto = onCheckNickname(body)
    override suspend fun getAppConfig(): AppConfigResponseDto = onConfig()
    override suspend fun getMyInfo(bearer: String): UserResponseDto = UserResponseDto(uuid = "uuid", name = "박기사")
}

private class FakeDriverApi(
    private val onGetMe: () -> DriverResultDto,
    private val onRemoveFcmToken: () -> Unit = {},
    private val onRegisterDriver: () -> DriverResultDto = { error("미사용") },
) : DriverApi {
    override suspend fun register(body: RegisterDriverRequestDto): DriverResultDto = onRegisterDriver()
    override suspend fun verify() = Unit
    override suspend fun registerVehicle(body: RegisterVehicleRequestDto) = Unit
    override suspend fun enableCall() = Unit
    override suspend fun disableCall() = Unit
    override suspend fun getMe(): DriverResultDto = onGetMe()
    override suspend fun registerFcmToken(body: RegisterFcmTokenRequestDto) = Unit
    override suspend fun removeFcmToken() = onRemoveFcmToken()
}

private class FakeDispatch(
    private val partyDetail: () -> PartySummaryDto = { error("미사용") },
    private val onAccept: () -> Unit = {},
) : DispatchApi {
    override suspend fun goOnline(body: LocationReportRequestDto) = Unit
    override suspend fun goOffline() = Unit
    override suspend fun reportLocation(body: LocationReportRequestDto) = Unit
    override suspend fun acceptCall(partyId: Long) = onAccept()
    override suspend fun rejectCall(partyId: Long) = Unit
    override suspend fun callStatus(partyId: Long): CallStatusResponseDto = error("미사용")
    override suspend fun getPartyDetail(partyId: Long): PartySummaryDto = partyDetail()
    override suspend fun arrive(partyId: Long) = Unit
    override suspend fun board(partyId: Long) = Unit
    override suspend fun complete(partyId: Long, body: CompleteRideRequestDto) = Unit
}

private class FakePartyApi(private val respond: (Long) -> PartyDetailDto) : PartyMembersApi {
    override suspend fun getPartyDetail(partyId: Long): PartyDetailDto = respond(partyId)
}
