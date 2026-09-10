package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DispatchRules
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.PartyMembersApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.PartyDetailMembersDto
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
import com.moyeota.driver.domain.model.CallException
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripPhase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * 실콜(숫자 partyId) 경로가 더미로 강등되지 않는지 검증한다.
 *
 * 회귀 배경: getCallDetail 이 모든 예외를 삼키고 [DummyDriverRepository] 의 콜 상세
 * (강남역 2번 출구 · 김*진 · 판교역)를 돌려줘, 배포 서버 타임아웃이나 409 CALL_CLOSED 만으로도
 * 기사 화면에 존재하지 않는 승객·목적지가 떴다.
 */
class RemoteDriverRepositoryCallTest {

    private val partyDto = PartySummaryDto(
        id = 42,
        departureLatitude = 37.5,
        departureLongitude = 127.0,
        departure = "서울대입구역 2번 출구",
        destination = "사당역 4번 출구",
        memberCount = 3,
        estimatedFare = 12_000,
    )

    private fun repository(
        dispatchApi: DispatchApi,
        partyMembersApi: PartyMembersApi? = null,
    ) = RemoteDriverRepository(
        authApi = UnusedAuthApi,
        driverApi = UnusedDriverApi,
        dispatchApi = dispatchApi,
        tokenStore = TokenStore(),
        fallback = DummyDriverRepository(),
        partyMembersApi = partyMembersApi,
    )

    // ── 상세 조회 실패: 더미 강등 금지 ──────────────────────────────────

    @Test
    fun `상세 조회가 타임아웃되면 더미가 아니라 예외를 올린다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw SocketTimeoutException("timeout") })

        val error = assertCallFailure { repository.getCallDetail("42") }

        assertEquals(CallException.Kind.NETWORK, error.kind)
        assertEquals("서버 응답이 늦어요 · 다시 시도해 주세요", error.message)
        assertTrue("네트워크 실패는 재시도 가치가 있다", error.retryable)
    }

    @Test
    fun `이미 마감된 콜(409)은 마감 문구로 바뀌고 재시도하지 않는다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw httpException(409, "CALL_CLOSED") })

        val error = assertCallFailure { repository.getCallDetail("42") }

        assertEquals(CallException.Kind.CLOSED, error.kind)
        assertEquals("이미 마감된 콜이에요 · 다음 콜을 기다려 주세요", error.message)
        assertFalse("마감된 콜은 다시 물어도 결과가 같다", error.retryable)
    }

    @Test
    fun `인증 만료(401)는 재로그인 안내 문구로 바뀐다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw httpException(401, "UNAUTHORIZED") })

        val error = assertCallFailure { repository.getCallDetail("42") }

        assertEquals(CallException.Kind.UNAUTHORIZED, error.kind)
        assertEquals("기사 인증이 만료됐어요 · 다시 로그인해 주세요", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun `상세 조회가 성공하면 서버 값이 그대로 온다`() = runBlocking {
        val repository = repository(FakeDispatchApi { partyDto })

        val detail = repository.getCallDetail("42")

        assertEquals("서울대입구역 2번 출구", detail.summary.pickupPlace)
        assertEquals("사당역 4번 출구", detail.summary.dropoffPlace)
        assertEquals(3, detail.summary.passengerCount)
        assertFalse(detail.summary.provisional)
    }

    @Test
    fun `더미 콜 id 는 그대로 더미 상세를 쓴다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw SocketTimeoutException("timeout") })

        // 실콜이 아닌 id("call-1")는 서버에 없는 데모 데이터 — 기존대로 더미가 응답한다
        val detail = repository.getCallDetail("call-1")

        assertTrue(detail.summary.id.startsWith("call-"))
    }

    // ── 푸시 임시 요약 ────────────────────────────────────────────────

    @Test
    fun `푸시 임시 요약이 콜 피드에 즉시 들어간다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw SocketTimeoutException("timeout") })

        val preview = repository.seedCallPreview(
            partyId = "42",
            departure = "서울대입구역 2번 출구",
            destination = "사당역 4번 출구",
            memberCount = 3,
            estimatedFare = 12_000,
        )

        assertEquals("서울대입구역 2번 출구", preview?.pickupPlace)
        assertEquals(3, preview?.passengerCount)
        assertEquals(CallType.POOL, preview?.type)
        assertEquals(12_000 + DispatchRules.CALL_FEE + DispatchRules.POOL_BONUS, preview?.expectedTotal)
        assertTrue(preview?.provisional == true)

        // D09 콜 리스트·홈 배너가 읽는 목록과 D10 헤더가 읽는 조회 양쪽에 반영된다
        assertEquals(listOf("42"), repository.getCalls().map { it.id })
        assertEquals(preview, repository.peekCallSummary("42"))
    }

    @Test
    fun `인원·요금이 없는 푸시는 지어내지 않는다 - 합승 표기만`() {
        val repository = repository(FakeDispatchApi { partyDto })

        val preview = repository.seedCallPreview("42", "출발지", "도착지", null, null)!!

        assertEquals(0, preview.passengerCount)
        assertFalse("인원 미상", preview.hasPassengerCount)
        assertFalse("요금 미상 — 호출료·보너스를 지어내 금액을 만들지 않는다", preview.hasFareEstimate)
        assertEquals(0, preview.expectedTotal)
        assertEquals(CallType.POOL, preview.type)
    }

    @Test
    fun `상세 조회 성공 시 임시 요약이 확정 요약으로 교체된다`() = runBlocking {
        val repository = repository(FakeDispatchApi { partyDto })
        repository.seedCallPreview("42", "출발지", "도착지", null, null)

        repository.handleCallOpened("42")

        val call = repository.getCalls().single()
        assertEquals("서울대입구역 2번 출구", call.pickupPlace)
        assertFalse(call.provisional)
    }

    @Test
    fun `상세 조회가 실패해도 임시 요약은 피드에 남는다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw SocketTimeoutException("timeout") })
        repository.seedCallPreview("42", "서울대입구역 2번 출구", "사당역 4번 출구", 3, 12_000)

        assertNull(repository.handleCallOpened("42"))

        val call = repository.getCalls().single()
        assertEquals("서울대입구역 2번 출구", call.pickupPlace)
        assertTrue(call.provisional)
    }

    @Test
    fun `마감된 콜(409)은 임시 요약까지 피드에서 걷어낸다`() = runBlocking {
        val repository = repository(FakeDispatchApi { throw httpException(409, "CALL_CLOSED") })
        repository.seedCallPreview("42", "서울대입구역 2번 출구", "사당역 4번 출구", 3, 12_000)

        repository.handleCallOpened("42")

        assertTrue("죽은 콜을 목록에 남기지 않는다", repository.getCalls().isEmpty())
    }

    @Test
    fun `더미 콜 id 는 임시 요약을 만들지 않는다`() = runBlocking {
        val repository = repository(FakeDispatchApi { partyDto })

        assertNull(repository.seedCallPreview("call-1", "출발지", "도착지", 2, 9_000))
        assertTrue(repository.getCalls().isEmpty())
    }

    // ── 콜 수락 시 승객 닉네임 주입 (베스트에포트) ─────────────────────

    @Test
    fun `acceptCall - 매칭방 닉네임을 passengers 와 stops 에 순서대로 주입한다`() = runBlocking {
        val repository = repository(
            FakeDispatchApi(onAccept = {}) { partyDto },
            partyMembersApi = FakePartyMembersApi { partyId ->
                assertEquals(42L, partyId)
                PartyDetailMembersDto(
                    members = listOf(
                        PartyMemberDto(nickname = "승객검D417"),
                        PartyMemberDto(nickname = "모여타짱"),
                        PartyMemberDto(nickname = null),   // 탈퇴 회원 — 승객3 유지
                    ),
                )
            },
        )

        val trip = repository.acceptCall("42")

        assertEquals(listOf("승객검D417", "모여타짱", "승객3"), trip.passengers.map { it.maskedName })
        // D15 하차 매칭(passengerMaskedName 문자열 대응)을 위해 스톱 이름도 함께 바뀐다
        assertEquals(
            listOf("승객검D417", "모여타짱", "승객3"),
            trip.stops.filter { it.kind == StopKind.DROPOFF }.map { it.passengerMaskedName },
        )
        // getActiveTrip 은 remoteTrip 캐시 기반 — 닉네임이 유지된다
        assertEquals(trip, repository.getActiveTrip())
    }

    @Test
    fun `acceptCall - 닉네임 조회 실패는 조용히 폴백, 수락 흐름을 막지 않는다`() = runBlocking {
        val repository = repository(
            FakeDispatchApi(onAccept = {}) { partyDto },
            partyMembersApi = FakePartyMembersApi { throw httpException(404, "PARTY_NOT_FOUND") },
        )

        val trip = repository.acceptCall("42")

        assertEquals(TripPhase.ASSIGNED, trip.phase)
        assertEquals(listOf("승객1", "승객2", "승객3"), trip.passengers.map { it.maskedName })
    }

    @Test
    fun `acceptCall - 닉네임 API 미배선이면 기존 승객N 표기 그대로`() = runBlocking {
        val repository = repository(FakeDispatchApi(onAccept = {}) { partyDto })

        val trip = repository.acceptCall("42")

        assertEquals(listOf("승객1", "승객2", "승객3"), trip.passengers.map { it.maskedName })
    }

    // ── 운행 시작 (파티 단위 board) ───────────────────────────────────

    @Test
    fun `startRide 는 서버 board 1회로 전원 탑승·운행 중 단계로 전환한다`() = runBlocking {
        var boardCount = 0
        val repository = repository(
            FakeDispatchApi(onAccept = {}, onBoard = { boardCount++ }) { partyDto },
        )
        val trip = repository.acceptCall("42")

        val started = repository.startRide(trip.id)

        assertEquals("파티 단위 board — 1회 호출", 1, boardCount)
        assertEquals(TripPhase.IN_TRIP, started.phase)
        assertTrue("승객별 탑승 없이 전원 일괄 탑승", started.passengers.all { it.boarded })
        assertEquals("다음 스톱은 첫 하차지", StopKind.DROPOFF, started.stops[started.nextStopIndex].kind)
    }

    @Test
    fun `startRide 실패는 예외 전파 - 더미 강등 금지`() = runBlocking {
        val repository = repository(
            FakeDispatchApi(onAccept = {}, onBoard = { throw httpException(409, "NOT_AWAITING_PICKUP") }) { partyDto },
        )
        repository.acceptCall("42")

        try {
            repository.startRide("42")
            fail("board 실패는 예외로 올라와야 한다 — 상태 전이 액션은 화면 유지 + 재시도")
        } catch (e: HttpException) {
            assertEquals(409, e.code())
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────────

    private inline fun assertCallFailure(block: () -> Unit): CallException {
        try {
            block()
        } catch (e: CallException) {
            return e
        }
        fail("CallException 이 올라와야 한다 — 더미로 강등되면 안 된다")
        error("unreachable")
    }

    private fun httpException(code: Int, serverCode: String) = HttpException(
        Response.error<PartySummaryDto>(
            code,
            """{"code":"$serverCode","message":"서버 메시지"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )
}

/**
 * getPartyDetail(+운행 시나리오에선 accept/board)만 시나리오별로 바꿔 끼우는 가짜 dispatch API —
 * 나머지는 이 테스트에서 쓰지 않는다.
 */
private class FakeDispatchApi(
    private val onAccept: () -> Unit = { unused() },
    private val onBoard: () -> Unit = { unused() },
    private val partyDetail: () -> PartySummaryDto,
) : DispatchApi {
    override suspend fun goOnline(body: LocationReportRequestDto) = unused()
    override suspend fun goOffline() = unused()
    override suspend fun reportLocation(body: LocationReportRequestDto) = unused()
    override suspend fun acceptCall(partyId: Long) = onAccept()
    override suspend fun rejectCall(partyId: Long) = unused()
    override suspend fun callStatus(partyId: Long): CallStatusResponseDto = unused()
    override suspend fun getPartyDetail(partyId: Long): PartySummaryDto = partyDetail()
    override suspend fun arrive(partyId: Long) = unused()
    override suspend fun board(partyId: Long) = onBoard()
    override suspend fun complete(partyId: Long, body: CompleteRideRequestDto) = unused()
}

/** 매칭방 상세를 시나리오별로 바꿔 끼우는 가짜 파티 멤버 API */
private class FakePartyMembersApi(
    private val respond: (Long) -> PartyDetailMembersDto,
) : PartyMembersApi {
    override suspend fun getPartyMembers(partyId: Long): PartyDetailMembersDto = respond(partyId)
}

private object UnusedAuthApi : AuthApi {
    override suspend fun register(body: UserRegisterRequestDto): UserResponseDto = unused()
    override suspend fun login(body: UserLoginRequestDto): TokenResponseDto = unused()
    override fun reissue(body: TokenRequestDto): retrofit2.Call<TokenResponseDto> = unused()
    override suspend fun logout(body: TokenRequestDto) = unused()
    override suspend fun checkPhone(body: PhoneCheckRequestDto): PhoneCheckResponseDto = unused()
    override suspend fun getMyInfo(bearer: String): UserResponseDto = unused()
}

private object UnusedDriverApi : DriverApi {
    override suspend fun register(body: RegisterDriverRequestDto): DriverResultDto = unused()
    override suspend fun verify() = unused()
    override suspend fun registerVehicle(body: RegisterVehicleRequestDto) = unused()
    override suspend fun enableCall() = unused()
    override suspend fun disableCall() = unused()
    override suspend fun getMe(): DriverResultDto = unused()
    override suspend fun registerFcmToken(body: RegisterFcmTokenRequestDto) = unused()
    override suspend fun removeFcmToken() = unused()
}

private fun unused(): Nothing = error("이 테스트에서 호출되지 않아야 하는 API")
