package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.PartyMembersApi
import com.moyeota.driver.data.remote.dto.AppConfigResponseDto
import com.moyeota.driver.data.remote.dto.CallStatusResponseDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.DriverResultDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckRequestDto
import com.moyeota.driver.data.remote.dto.NicknameCheckResponseDto
import com.moyeota.driver.data.remote.dto.PartyDetailDto
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/*
 * 엣지 케이스 테스트(32d) 공용 가짜 API·에러 조립기.
 *
 * 기존 테스트 파일들은 각자 가짜를 들고 있는데(FakeAuthApi·SessionAuthApi·UnusedAuthApi …),
 * 같은 패키지에 같은 이름의 최상위 선언을 또 두면 JVM 클래스 이름이 충돌한다. 그래서 `Edge` 접두사로
 * 이름을 분리하고, **호출 횟수까지 기록하는** 가짜로 한 벌만 둬 세 파일이 함께 쓴다
 * (호출되지 않아야 하는 경로를 `fail()` 대신 카운터로 검증할 수 있어 단정이 분명해진다).
 */

/** 인메모리 세션 저장소 — 토큰·활성 partyId 의 읽기/쓰기/삭제만 흉내 낸다 */
internal class EdgeStorage(
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

/** 호출 횟수를 세는 가짜 인가 API — 응답·예외는 람다로 바꿔 끼운다 */
internal class EdgeAuthApi(
    private val onRegister: () -> UserResponseDto = { UserResponseDto(uuid = "uuid", name = "번개기사") },
    private val onLogin: () -> TokenResponseDto = { TokenResponseDto("access-1", "refresh-1") },
    private val onLogout: (TokenRequestDto) -> Unit = {},
    private val onCheckPhone: (PhoneCheckRequestDto) -> PhoneCheckResponseDto = { PhoneCheckResponseDto(false) },
    private val onSendSms: (SmsSendRequestDto) -> Unit = {},
    private val onVerifySms: (SmsVerifyRequestDto) -> SmsVerifyResponseDto = { SmsVerifyResponseDto("token-abc") },
    private val onCheckNickname: (NicknameCheckRequestDto) -> NicknameCheckResponseDto = { NicknameCheckResponseDto(false) },
    private val onConfig: () -> AppConfigResponseDto = { AppConfigResponseDto(taxiEnabled = true) },
    private val onMyInfo: () -> UserResponseDto = { UserResponseDto(uuid = "uuid", name = "박기사") },
) : AuthApi {

    var registerCalls = 0
        private set
    var loginCalls = 0
        private set
    var configCalls = 0
        private set

    /** 서버 무효화 요청으로 나간 refreshToken 들 — 순서와 값까지 단정할 수 있게 남긴다 */
    val logoutBodies = mutableListOf<String>()

    val sentSmsNumbers = mutableListOf<String>()

    override suspend fun register(body: UserRegisterRequestDto): UserResponseDto {
        registerCalls++
        return onRegister()
    }

    override suspend fun login(body: UserLoginRequestDto): TokenResponseDto {
        loginCalls++
        return onLogin()
    }

    override fun reissue(body: TokenRequestDto): retrofit2.Call<TokenResponseDto> = unusedEdge()

    override suspend fun logout(body: TokenRequestDto) {
        logoutBodies += body.refreshToken
        onLogout(body)
    }

    override suspend fun checkPhone(body: PhoneCheckRequestDto): PhoneCheckResponseDto = onCheckPhone(body)

    override suspend fun sendSmsCode(body: SmsSendRequestDto) {
        sentSmsNumbers += body.phoneNumber
        onSendSms(body)
    }

    override suspend fun verifySmsCode(body: SmsVerifyRequestDto): SmsVerifyResponseDto = onVerifySms(body)

    override suspend fun checkNickname(body: NicknameCheckRequestDto): NicknameCheckResponseDto = onCheckNickname(body)

    override suspend fun getAppConfig(): AppConfigResponseDto {
        configCalls++
        return onConfig()
    }

    override suspend fun getMyInfo(bearer: String): UserResponseDto = onMyInfo()
}

/** 호출 횟수를 세는 가짜 기사 API */
internal class EdgeDriverApi(
    private val onGetMe: () -> DriverResultDto = { edgeMe },
    private val onRegisterDriver: () -> DriverResultDto = { edgeMe },
    private val onVerify: () -> Unit = {},
    private val onRemoveFcmToken: () -> Unit = {},
) : DriverApi {

    var getMeCalls = 0
        private set
    var registerCalls = 0
        private set
    var verifyCalls = 0
        private set
    var removeFcmCalls = 0
        private set

    override suspend fun register(body: RegisterDriverRequestDto): DriverResultDto {
        registerCalls++
        return onRegisterDriver()
    }

    override suspend fun verify() {
        verifyCalls++
        onVerify()
    }

    override suspend fun registerVehicle(body: RegisterVehicleRequestDto) = unusedEdge()

    override suspend fun enableCall() = unusedEdge()

    override suspend fun disableCall() = unusedEdge()

    override suspend fun getMe(): DriverResultDto {
        getMeCalls++
        return onGetMe()
    }

    override suspend fun registerFcmToken(body: RegisterFcmTokenRequestDto) = Unit

    override suspend fun removeFcmToken() {
        removeFcmCalls++
        onRemoveFcmToken()
    }
}

/** 콜 상세·수락만 바꿔 끼우는 가짜 dispatch API */
internal class EdgeDispatchApi(
    private val onPartyDetail: () -> PartySummaryDto = { edgeCallParty },
    private val onAccept: () -> Unit = {},
) : DispatchApi {

    var acceptCalls = 0
        private set

    override suspend fun goOnline(body: LocationReportRequestDto) = unusedEdge()

    override suspend fun goOffline() = unusedEdge()

    override suspend fun reportLocation(body: LocationReportRequestDto) = unusedEdge()

    override suspend fun acceptCall(partyId: Long) {
        acceptCalls++
        onAccept()
    }

    override suspend fun rejectCall(partyId: Long) = unusedEdge()

    override suspend fun callStatus(partyId: Long): CallStatusResponseDto = unusedEdge()

    override suspend fun getPartyDetail(partyId: Long): PartySummaryDto = onPartyDetail()

    override suspend fun arrive(partyId: Long) = unusedEdge()

    override suspend fun board(partyId: Long) = unusedEdge()

    override suspend fun complete(partyId: Long, body: CompleteRideRequestDto) = unusedEdge()
}

/** 매칭방 상세(소유권 판정 원천) — 조회 횟수까지 센다 */
internal class EdgePartyApi(
    private val respond: (Long) -> PartyDetailDto = { edgeMyParty },
) : PartyMembersApi {

    var calls = 0
        private set

    override suspend fun getPartyDetail(partyId: Long): PartyDetailDto {
        calls++
        return respond(partyId)
    }
}

/** 내 기사 레코드 (status VERIFIED = 영업 가능) */
internal val edgeMe = DriverResultDto(id = 7, userId = 1, name = "박기사", status = "VERIFIED", callEnabled = true)

/** 콜 후보 상세 (GET /dispatch/calls/{partyId}) */
internal val edgeCallParty = PartySummaryDto(
    id = 42,
    departureLatitude = 37.4980,
    departureLongitude = 127.0276,
    departure = "강남역 2번 출구",
    destination = "판교역 1번 출구",
    memberCount = 2,
    estimatedFare = 18_400,
)

/** 내게 배정된 진행 중 파티 (taxiDriverId = edgeMe.id) */
internal val edgeMyParty = PartyDetailDto(
    id = 42,
    departureLat = 37.4980,
    departureLng = 127.0276,
    destinationLat = 37.3948,
    destinationLng = 127.1112,
    departure = "강남역 2번 출구",
    destination = "판교역 1번 출구",
    currentMembers = 2,
    status = "DRIVER_ASSIGNED",
    members = emptyList(),
    estimateFare = 18_400,
    estimateTime = 25,
    taxiDriverId = 7,
)

/** 백엔드 공통 4xx body — `{code, message}` (common/exception/ErrorResponse.java) */
internal fun edgeCoded(status: Int, code: String, message: String) = HttpException(
    Response.error<Unit>(
        status,
        """{"code":"$code","message":"$message"}""".toResponseBody("application/json".toMediaTypeOrNull()),
    ),
)

/**
 * 핸들러가 없을 때의 Spring 기본 404 — `code` 가 없다.
 * **실제 배포 서버가 보내는 모양**이다(`moyeota.taxi.enabled=false` → 기사·배차 컨트롤러 미등록).
 */
internal fun edgeSpringNotFound(path: String = "/api/v1/drivers/me") = HttpException(
    Response.error<Unit>(
        404,
        """{"timestamp":"2026-10-13T00:00:00.000+00:00","status":404,"error":"Not Found","path":"$path"}"""
            .toResponseBody("application/json".toMediaTypeOrNull()),
    ),
)

/** body 가 아예 없는 4xx/5xx — 프록시·게이트웨이가 끼어들면 이 모양이 온다 */
internal fun edgeEmptyBody(status: Int) = HttpException(
    Response.error<Unit>(status, "".toResponseBody("application/json".toMediaTypeOrNull())),
)

/** JSON 이 아닌 404 — CDN·리버스 프록시의 HTML 오류 페이지 */
internal fun edgeHtmlNotFound() = HttpException(
    Response.error<Unit>(
        404,
        "<html><head><title>404 Not Found</title></head><body>404 Not Found</body></html>"
            .toResponseBody("text/html".toMediaTypeOrNull()),
    ),
)

internal fun unusedEdge(): Nothing = error("이 테스트에서 호출되지 않아야 하는 API")
