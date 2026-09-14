package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
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
import com.moyeota.driver.domain.location.DriverCoordinate
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.model.DriverLocationUnavailableException
import com.moyeota.driver.domain.model.DutyStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * 영업 시작·하트비트가 **좌표를 지어내지 않는지** 검증한다.
 *
 * 회귀 배경(_workspace/28): 좌표 캐시가 강남역(37.4980/127.0276)으로 초기화돼 있어,
 * 위치 권한이 꺼진 실기기에서도 영업이 켜지고 하트비트가 15초마다 강남역을 서버에 보고했다.
 * 서버는 이 좌표로 콜 반경(findNearby)을 판정하므로 부산 기사에게 서울 콜이 갈 수 있었다.
 *
 * 새 정책:
 * - 좌표를 못 얻으면 영업 시작은 [DriverLocationUnavailableException] 으로 실패하고 서버를 호출하지 않는다.
 * - goOnline 실패는 더미로 강등하지 않고 전파한다 (화면만 "영업중"이 되는 것을 막는다).
 * - 하트비트는 좌표가 없으면 그 주기를 건너뛴다.
 * - 영업 종료는 기존대로 관대하다 (서버 실패해도 휴무 전환).
 */
class RemoteDriverRepositoryDutyLocationTest {

    /** 기사의 실제 위치 — 서울 기본 좌표가 새어 나오면 이 값과 달라진다 */
    private val busan = DriverCoordinate(latitude = 35.1796, longitude = 129.0756)

    private fun repository(
        dispatchApi: DispatchApi,
        locationSource: DriverLocationSource,
        locationWaitTimeoutMs: Long = 0L,
    ) = RemoteDriverRepository(
        authApi = UnusedDutyAuthApi,
        driverApi = UnusedDutyDriverApi,
        dispatchApi = dispatchApi,
        tokenStore = TokenStore(),
        fallback = DummyDriverRepository(),
        locationSource = locationSource,
        locationWaitTimeoutMs = locationWaitTimeoutMs,
    )

    // ── 영업 시작: 좌표 없으면 시작하지 않는다 ──────────────────────────

    @Test
    fun `좌표를 모르면 영업 시작이 실패하고 goOnline 을 호출하지 않는다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(dispatchApi, locationSource = { null })

        try {
            repository.setDutyStatus(online = true)
            fail("좌표 없이 영업이 시작되면 안 된다 — 서버가 엉뚱한 위치로 배차한다")
        } catch (e: DriverLocationUnavailableException) {
            assertEquals("위치를 확인할 수 없어요", e.message)
        }

        assertTrue("좌표 없이 서버를 부르면 안 된다", dispatchApi.onlineBodies.isEmpty())
        assertEquals(
            "영업 시작 실패는 더미 영업 상태로 강등되면 안 된다",
            DutyStatus.OFFLINE,
            repository.getHomeSummary().dutyStatus,
        )
    }

    @Test
    fun `측위에 성공하면 그 좌표로 영업을 시작한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(dispatchApi, locationSource = { busan })

        val summary = repository.setDutyStatus(online = true)

        assertEquals(DutyStatus.ONLINE, summary.dutyStatus)
        assertEquals(
            listOf(LocationReportRequestDto(busan.latitude, busan.longitude)),
            dispatchApi.onlineBodies,
        )
    }

    @Test
    fun `첫 fix 가 늦어도 대기 구간 안에 들어오면 영업을 시작한다`() = runBlocking {
        // 권한을 갓 허용한 직후 — 처음 두 번은 아직 좌표가 없다
        var attempts = 0
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(
            dispatchApi,
            locationSource = { if (attempts++ < 2) null else busan },
            locationWaitTimeoutMs = 3_000L,
        )

        val summary = repository.setDutyStatus(online = true)

        assertEquals(DutyStatus.ONLINE, summary.dutyStatus)
        assertEquals(
            listOf(LocationReportRequestDto(busan.latitude, busan.longitude)),
            dispatchApi.onlineBodies,
        )
    }

    @Test
    fun `대기 시간을 다 써도 좌표가 없으면 실패한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(
            dispatchApi,
            locationSource = { null },
            locationWaitTimeoutMs = 600L,   // 300ms 간격 → 재시도 후 포기
        )

        try {
            repository.setDutyStatus(online = true)
            fail("대기 후에도 좌표가 없으면 실패해야 한다")
        } catch (_: DriverLocationUnavailableException) {
            // 기대 동작
        }
        assertTrue(dispatchApi.onlineBodies.isEmpty())
    }

    // ── 영업 시작: 서버 실패는 전파한다 (더미 강등 금지) ────────────────

    @Test
    fun `goOnline 이 서버 오류면 더미로 강등하지 않고 전파한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi(onOnline = { throw httpException(500) })
        val repository = repository(dispatchApi, locationSource = { busan })

        try {
            repository.setDutyStatus(online = true)
            fail("서버가 모르는데 화면만 영업중이 되면 안 된다")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
        assertEquals(
            "goOnline 실패 후 더미가 영업중으로 켜지면 안 된다",
            DutyStatus.OFFLINE,
            repository.getHomeSummary().dutyStatus,
        )
    }

    @Test
    fun `goOnline 이 네트워크 실패여도 전파한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi(onOnline = { throw SocketTimeoutException("timeout") })
        val repository = repository(dispatchApi, locationSource = { busan })

        try {
            repository.setDutyStatus(online = true)
            fail("네트워크 실패도 영업중으로 보이면 안 된다")
        } catch (_: SocketTimeoutException) {
            // 기대 동작
        }
        assertEquals(DutyStatus.OFFLINE, repository.getHomeSummary().dutyStatus)
    }

    // ── 영업 종료: 기존대로 관대하게 ───────────────────────────────────

    @Test
    fun `영업 종료는 서버가 실패해도 휴무로 전환한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi(onOffline = { throw httpException(503) })
        val repository = repository(dispatchApi, locationSource = { busan })
        repository.setDutyStatus(online = true)

        val summary = repository.setDutyStatus(online = false)

        assertEquals(DutyStatus.OFFLINE, summary.dutyStatus)
    }

    // ── 하트비트 ──────────────────────────────────────────────────────

    @Test
    fun `좌표를 모르면 하트비트 주기를 건너뛴다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(dispatchApi, locationSource = { null })

        repository.reportLocationTick()

        assertTrue("좌표 없이 위치를 보고하면 안 된다", dispatchApi.locationBodies.isEmpty())
    }

    @Test
    fun `하트비트는 실측 좌표만 보낸다 — 기본 좌표 폴백이 없다`() = runBlocking {
        // 첫 주기에 측위 성공, 그 뒤로는 측위 실패(권한 회수·실내 등)
        var fixes = 1
        val dispatchApi = RecordingDispatchApi()
        val repository = repository(dispatchApi, locationSource = { if (fixes-- > 0) busan else null })

        repository.reportLocationTick()
        repository.reportLocationTick()

        assertEquals(
            "측위 실패 주기는 마지막 실측 좌표를 유지한다 — 서울 기본 좌표로 되돌아가지 않는다",
            List(2) { LocationReportRequestDto(busan.latitude, busan.longitude) },
            dispatchApi.locationBodies,
        )
        assertNull(
            "강남역(37.4980/127.0276)은 어떤 주기에서도 보고되지 않아야 한다",
            dispatchApi.locationBodies.firstOrNull { it.latitude == 37.4980 },
        )
    }

    @Test
    fun `하트비트 전송 실패는 삼킨다 — 다음 주기가 복구한다`() = runBlocking {
        val dispatchApi = RecordingDispatchApi(onLocation = { throw SocketTimeoutException("timeout") })
        val repository = repository(dispatchApi, locationSource = { busan })

        repository.reportLocationTick()   // 예외가 올라오면 하트비트 루프가 죽는다

        assertEquals(1, dispatchApi.locationBodies.size)
    }

    private fun httpException(code: Int) = HttpException(
        Response.error<Unit>(
            code,
            """{"code":"SERVER_ERROR","message":"서버 메시지"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )
}

/**
 * 영업 시작·위치 보고 호출을 기록하는 가짜 dispatch API — 나머지는 이 테스트에서 쓰지 않는다.
 * 기록이 먼저다: 실패 시나리오에서도 "무엇을 보내려 했는지"가 남아야 한다.
 */
private class RecordingDispatchApi(
    private val onOnline: () -> Unit = {},
    private val onOffline: () -> Unit = {},
    private val onLocation: () -> Unit = {},
) : DispatchApi {
    val onlineBodies = mutableListOf<LocationReportRequestDto>()
    val locationBodies = mutableListOf<LocationReportRequestDto>()

    override suspend fun goOnline(body: LocationReportRequestDto) {
        onlineBodies += body
        onOnline()
    }

    override suspend fun goOffline() = onOffline()

    override suspend fun reportLocation(body: LocationReportRequestDto) {
        locationBodies += body
        onLocation()
    }

    override suspend fun acceptCall(partyId: Long) = unusedDuty()
    override suspend fun rejectCall(partyId: Long) = unusedDuty()
    override suspend fun callStatus(partyId: Long): CallStatusResponseDto = unusedDuty()
    override suspend fun getPartyDetail(partyId: Long): PartySummaryDto = unusedDuty()
    override suspend fun arrive(partyId: Long) = unusedDuty()
    override suspend fun board(partyId: Long) = unusedDuty()
    override suspend fun complete(partyId: Long, body: CompleteRideRequestDto) = unusedDuty()
}

private object UnusedDutyAuthApi : AuthApi {
    override suspend fun register(body: UserRegisterRequestDto): UserResponseDto = unusedDuty()
    override suspend fun login(body: UserLoginRequestDto): TokenResponseDto = unusedDuty()
    override fun reissue(body: TokenRequestDto): retrofit2.Call<TokenResponseDto> = unusedDuty()
    override suspend fun logout(body: TokenRequestDto) = unusedDuty()
    override suspend fun checkPhone(body: PhoneCheckRequestDto): PhoneCheckResponseDto = unusedDuty()
    override suspend fun getMyInfo(bearer: String): UserResponseDto = unusedDuty()
}

private object UnusedDutyDriverApi : DriverApi {
    override suspend fun register(body: RegisterDriverRequestDto): DriverResultDto = unusedDuty()
    override suspend fun verify() = unusedDuty()
    override suspend fun registerVehicle(body: RegisterVehicleRequestDto) = unusedDuty()
    override suspend fun enableCall() = unusedDuty()
    override suspend fun disableCall() = unusedDuty()
    override suspend fun getMe(): DriverResultDto = unusedDuty()
    override suspend fun registerFcmToken(body: RegisterFcmTokenRequestDto) = unusedDuty()
    override suspend fun removeFcmToken() = unusedDuty()
}

private fun unusedDuty(): Nothing = error("이 테스트에서 호출되지 않아야 하는 API")
