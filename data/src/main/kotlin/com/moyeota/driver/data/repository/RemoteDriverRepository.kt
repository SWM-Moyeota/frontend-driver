package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.DispatchApi
import com.moyeota.driver.data.remote.DispatchRules
import com.moyeota.driver.data.remote.DriverApi
import com.moyeota.driver.data.remote.NetworkModule
import com.moyeota.driver.data.remote.ReportApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.calcFareResult
import com.moyeota.driver.data.remote.driverAccountStatus
import com.moyeota.driver.data.remote.normalizePhoneNumber
import com.moyeota.driver.data.remote.dto.CallResultRequestDto
import com.moyeota.driver.data.remote.dto.CompleteRideRequestDto
import com.moyeota.driver.data.remote.dto.LocationReportRequestDto
import com.moyeota.driver.data.remote.dto.PhoneCheckRequestDto
import com.moyeota.driver.data.remote.dto.RegisterDriverRequestDto
import com.moyeota.driver.data.remote.dto.RegisterFcmTokenRequestDto
import com.moyeota.driver.data.remote.dto.RegisterVehicleRequestDto
import com.moyeota.driver.data.remote.dto.ReportRequestDto
import com.moyeota.driver.data.remote.dto.TokenRequestDto
import com.moyeota.driver.data.remote.dto.UserLoginRequestDto
import com.moyeota.driver.data.remote.serverMessage
import com.moyeota.driver.data.remote.toActiveTrip
import com.moyeota.driver.data.remote.toCallDetail
import com.moyeota.driver.data.remote.toCallSummary
import com.moyeota.driver.data.remote.toRegisterRequest
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallDetail
import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.model.DriverSignUpForm
import com.moyeota.driver.domain.model.FareResult
import com.moyeota.driver.domain.model.HomeSummary
import com.moyeota.driver.domain.model.LoginResult
import com.moyeota.driver.domain.model.MissedCall
import com.moyeota.driver.domain.model.Promotion
import com.moyeota.driver.domain.model.QualificationCheckResult
import com.moyeota.driver.domain.model.RatingSummary
import com.moyeota.driver.domain.model.SettlementDetail
import com.moyeota.driver.domain.model.TripHistoryDetail
import com.moyeota.driver.domain.model.TripHistoryItem
import com.moyeota.driver.domain.model.TripPhase
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 실 백엔드 연동 Repository — 백엔드에 존재하는 기사·dispatch API 만 실연동하고,
 * 백엔드에 없는 조회(홈 요약·콜 목록·정산·평점·이력·프로모션·로그인/OTP)는 생성자로 받은
 * [DummyDriverRepository] 에 위임한다 (위임 목록은 _workspace/09_api-integrator_remote.md 참고).
 *
 * id 매핑 전략:
 * - driver·dispatch API 전부 토큰 기반(@CurrentUser/@CurrentDriver) — 클라이언트가 userId/driverId 를 보내지 않는다.
 * - CallSummary.id / ActiveTrip.id = partyId.toString(). 숫자로 파싱되지 않는 id("call-1" 등)는
 *   더미 데이터 소속이므로 해당 호출 전체를 더미에 위임한다.
 *
 * 실패 정책:
 * - 조회·상태 토글(영업 on/off)은 서버 불가 시 더미로 조용히 강등 — 앱이 계속 동작한다.
 * - 가입 분기 조회(isPhoneRegistered)와 상태 전이 액션(콜 수락/거절, 탑승, 요금 확정)은 예외를 전파한다
 *   (실패 시 화면 유지 + 재시도 원칙 — 오탐 가입 방지).
 */
class RemoteDriverRepository(
    private val authApi: AuthApi,
    private val driverApi: DriverApi,
    private val dispatchApi: DispatchApi,
    private val tokenStore: TokenStore,
    private val fallback: DummyDriverRepository,
    /** 단말 실측 위치 — 미주입(테스트·더미 구동)이면 기본 좌표로 폴백한다 */
    private val locationSource: DriverLocationSource = DriverLocationSource { null },
    /** 긴급 신고 API — 기본값은 NetworkModule 인증 클라이언트 (AppContainer 수정 불필요) */
    private val reportApi: ReportApi = NetworkModule.reportApi(),
) : DriverRepository {

    private var vehicleInfoLabel: String = DEFAULT_VEHICLE_LABEL
    private var remoteTrip: ActiveTrip? = null

    /** 영업중 위치 하트비트 루프 (startHeartbeat/stopHeartbeat 에서만 접근) */
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    /** FCM 으로 도착한 실콜 피드 (getCalls 가 더미 목록 앞에 병합) */
    private val callFeed = CallFeed()

    /**
     * 마지막으로 알게 된 FCM 토큰. 미로그인 시점에 도착한 토큰의 pending 보관소이자,
     * 로그인/가입 성공 시 자동 재전송 원천 (토큰 회전 대비 항상 최신값 유지).
     */
    @Volatile
    private var lastFcmToken: String? = null

    /** 로그인/가입 성공 후 토큰 fire-and-forget 전송용 (호출 코루틴 수명과 분리) */
    private val fcmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 마지막으로 확인된 기사 좌표. [refreshLocation] 이 실측값으로 갱신하고,
    // 측위 실패(권한 미허용·GPS 미수신) 시에는 직전 값(최초엔 기본 좌표)을 유지한다.
    @Volatile
    private var lastLatitude: Double = DEFAULT_LATITUDE

    @Volatile
    private var lastLongitude: Double = DEFAULT_LONGITUDE

    /**
     * 단말 실측 위치를 읽어 캐시를 갱신하고 전송용 좌표를 돌려준다.
     * 측위 불가 시 직전 좌표를 그대로 쓴다 — 하트비트가 끊기면 서버 TTL(30초)이 만료돼
     * 기사가 콜 후보에서 사라지므로, "정확한 좌표 없음"보다 "직전 좌표로라도 살아있음"이 낫다.
     */
    private fun refreshLocation(): LocationReportRequestDto {
        locationSource.current()?.let { coordinate ->
            lastLatitude = coordinate.latitude
            lastLongitude = coordinate.longitude
        }
        return LocationReportRequestDto(lastLatitude, lastLongitude)
    }

    // ── 인가 · 가입 (실연동 — POST /api/v1/auth/*) ────────────────────────

    /**
     * 실연동: POST /auth/login → 토큰 저장 → GET /local/users/info(이름) → GET /drivers/me(기사 상태).
     * /drivers/me 는 토큰(@CurrentUser) 기반이라 클라이언트가 userId 를 알 필요가 없다 — 구 userId=1L 고정 갭 해소.
     *
     * 기사 미등록(404 DRIVER_NOT_REGISTERED)은 "가입되지 않은 계정" 예외로 올린다 —
     * PENDING_REVIEW 로 조용히 홈에 들여보내면 이후 모든 기사 API 가 404 로 깨져 원인 추적이 어렵다.
     */
    override suspend fun login(loginId: String, password: String): LoginResult {
        val tokens = try {
            authApi.login(UserLoginRequestDto(loginId = loginId, password = password))
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401) throw IllegalStateException("아이디 또는 비밀번호가 올바르지 않습니다.", e)
            throw IllegalStateException("로그인 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        }
        tokenStore.update(tokens.accessToken, tokens.refreshToken)

        val name = runCatching { authApi.getMyInfo("Bearer ${tokens.accessToken}").name }
            .getOrNull() ?: DEFAULT_DRIVER_NAME

        val driver = try {
            driverApi.getMe()
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                throw IllegalStateException("기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요.", e)
            }
            throw IllegalStateException("기사 정보 조회 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        }
        flushFcmTokenAsync()
        return LoginResult(status = driverAccountStatus(driver.status), driverName = name)
    }

    /**
     * 실연동: POST /auth/phone/check (permitAll) → `{exists}`. 번호는 [normalizePhoneNumber] 로
     * 가입(register)과 같은 하이픈 표준형으로 정규화해 보낸다.
     *
     * 실패(네트워크·서버 오류)는 **예외 전파** — 화면(SignUpScreen 스텝1)이 에러 배너 + 재시도를 보유한다.
     * 조용한 더미 폴백은 기가입 번호를 "미등록"으로 오탐해 중복 가입 시도로 이어지므로 금지.
     */
    override suspend fun isPhoneRegistered(phoneNumber: String): Boolean =
        try {
            authApi.checkPhone(PhoneCheckRequestDto(normalizePhoneNumber(phoneNumber))).exists
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            throw IllegalStateException("번호 확인 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        } catch (e: Exception) {
            throw IllegalStateException("번호 확인 실패: ${e.message ?: "네트워크 오류"}", e)
        }

    /**
     * 실연동 일괄 플로우: /auth/register → /auth/login → POST /drivers(차량 정보 통합) → /drivers/verify.
     * 신스펙에서 POST /drivers 가 토큰(@CurrentUser) 기반 + 차량 정보 통합이라
     * userId 전송·별도 차량 등록 호출이 모두 사라졌다.
     *
     * 관용 처리:
     * - register 409(이미 존재하는 아이디)는 기존 계정 재가입 시도로 보고 로그인 단계로 진행한다.
     * - POST /drivers 409(DRIVER_ALREADY_REGISTERED)는 GET /drivers/me 로 기존 기사를 회수해 진행한다.
     * - verify 409(DRIVER_NOT_PENDING — 이미 검증됨)는 성공으로 취급한다.
     */
    override suspend fun signUp(form: DriverSignUpForm): LoginResult {
        // 1) 계정 생성 — 409(아이디 중복)는 기존 계정으로 보고 로그인으로 진행
        step("계정 생성") {
            try {
                authApi.register(form.toRegisterRequest())
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 409) throw e
            }
        }

        // 2) 자동 로그인
        val tokens = step("자동 로그인") {
            authApi.login(UserLoginRequestDto(loginId = form.loginId, password = form.password))
        }
        tokenStore.update(tokens.accessToken, tokens.refreshToken)

        // 3) 기사 등록 (차량 정보 통합) — 이미 등록(409)이면 GET /drivers/me 로 기존 기사 회수
        val driver = step("기사 등록") {
            try {
                driverApi.register(
                    RegisterDriverRequestDto(
                        qualificationNumber = form.qualificationNumber,
                        bankName = DEFAULT_BANK_NAME,
                        accountNumber = DEFAULT_ACCOUNT_NUMBER,
                        vehicle = RegisterDriverRequestDto.VehicleInfoDto(
                            seats = form.seats,
                            plateNumber = form.plateNumber,
                            type = form.vehicleType,
                        ),
                    ),
                )
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 409) driverApi.getMe() else throw e
            }
        }

        // 4) 자격 검증 (토큰 기반) — 409 는 이미 검증됨
        step("기사 자격 검증") {
            try {
                driverApi.verify()
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 409) throw e
            }
        }
        vehicleInfoLabel = "${form.vehicleType} ${form.plateNumber}"

        flushFcmTokenAsync()
        return LoginResult(status = DriverAccountStatus.APPROVED, driverName = form.name.ifBlank { DEFAULT_DRIVER_NAME })
    }

    /**
     * 실연동: POST /auth/logout(refreshToken). 서버 호출 성공 여부와 무관하게 로컬 토큰·캐시는 항상 비운다
     * — 서버가 죽어도 사용자는 로그아웃할 수 있어야 한다. 서버 logout 은 멱등(이미 무효화돼도 204).
     */
    override suspend fun logout() {
        stopHeartbeat()
        val refreshToken = tokenStore.refreshToken()
        tokenStore.clear()
        remoteTrip = null
        if (refreshToken != null) {
            try {
                authApi.logout(TokenRequestDto(refreshToken))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 서버 무효화 실패는 무시 — 로컬 세션은 이미 정리됐다
            }
        }
    }

    /** 단계 실패를 "회원가입 실패 — {단계}: {사유}" 한국어 예외로 변환한다 */
    private inline fun <T> step(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw IllegalStateException("회원가입 실패 — $name: ${e.message}", e)
        } catch (e: retrofit2.HttpException) {
            throw IllegalStateException("회원가입 실패 — $name: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        } catch (e: Exception) {
            throw IllegalStateException("회원가입 실패 — $name: ${e.message ?: "네트워크 오류"}", e)
        }

    /** 백엔드 미구현 — 더미 위임 (D02 화면 보존용, MVP 플로우 미사용) */
    override suspend fun requestOtp(phone: String) = fallback.requestOtp(phone)

    /** 백엔드 미구현 — 더미 위임 (D02 화면 보존용, MVP 플로우 미사용) */
    override suspend fun verifyOtp(phone: String, code: String): Boolean = fallback.verifyOtp(phone, code)

    /**
     * 백엔드 미대응 — 더미 위임 (레거시 D03 경로, MVP 플로우 미사용).
     * 구현은 POST /drivers 를 자격 검증 대용으로 썼지만, 신스펙의 register 는 차량 정보(vehicle)가
     * 필수라 차량 입력이 없는 이 화면(D03)에서는 호출할 수 없다. MVP 가입은 [signUp] 일괄 플로우가 담당한다.
     */
    override suspend fun checkQualifications(
        licenseNumber: String,
        licenseSerial: String,
        taxiCertNumber: String,
    ): QualificationCheckResult =
        fallback.checkQualifications(licenseNumber, licenseSerial, taxiCertNumber)

    /**
     * 실연동: POST /drivers/vehicle + 콜 수신 on/off(POST·DELETE /drivers/call) — 전부 토큰 기반.
     * 서버 스펙에 companyName 이 없어 전송하지 않는다. seats 는 기본 4.
     * 레거시 D04 경로(차량 변경)용 — 가입 플로우에선 register 에 차량 정보가 통합돼 불필요.
     */
    override suspend fun registerVehicle(
        plateNumber: String,
        vehicleModel: String,
        companyName: String,
        acceptsPool: Boolean,
    ) {
        try {
            driverApi.registerVehicle(
                RegisterVehicleRequestDto(seats = DEFAULT_SEATS, plateNumber = plateNumber, type = vehicleModel),
            )
            if (acceptsPool) driverApi.enableCall() else driverApi.disableCall()
            vehicleInfoLabel = "$vehicleModel $plateNumber"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback.registerVehicle(plateNumber, vehicleModel, companyName, acceptsPool)
        }
    }

    // ── 홈 · 영업 상태 ─────────────────────────────────────────────────────

    /** 백엔드 미구현(오늘 수입·운행수 요약 API 없음) — 더미 위임. 영업 상태는 setDutyStatus 에서 동기화됨 */
    override suspend fun getHomeSummary(): HomeSummary = fallback.getHomeSummary()

    /**
     * 실연동: POST /dispatch/online (위치 포함) · DELETE /dispatch/online — 토큰(@CurrentDriver) 기반.
     * 홈 요약 수치는 서버에 없으므로 더미가 만들고, 더미의 영업 상태를 함께 갱신해 일관성을 유지한다.
     * 서버 불가 시 더미 상태만 전환한다 (조용한 강등).
     */
    override suspend fun setDutyStatus(online: Boolean): HomeSummary {
        try {
            if (online) {
                dispatchApi.goOnline(refreshLocation())
                startHeartbeat()
            } else {
                stopHeartbeat()
                dispatchApi.goOffline()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 서버 미기동 등 — 더미 상태 전환으로 강등
        }
        return fallback.setDutyStatus(online)
    }

    /**
     * 영업중 위치 하트비트 — 서버(DriverLocationRedis)가 TTL 30초로 후보를 필터하므로,
     * 주기 보고가 없으면 영업 시작 30초 뒤부터 콜 후보에서 사라진다 (QA 리포트 18 실측).
     * 15초 주기로 **실측 좌표**([refreshLocation])를 POST /dispatch/location 으로 보낸다 —
     * 서버는 이 좌표로 콜 반경(findNearby)을 판정하므로 고정 좌표를 보내면 실제 위치와 무관하게 배차된다.
     * 개별 실패는 무시 — 다음 주기가 복구한다.
     * 프로세스 종료 시 루프도 함께 죽는데, 그러면 TTL 만료로 서버가 자동 오프라인 처리하므로 정합적이다.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = fcmScope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                try {
                    dispatchApi.reportLocation(refreshLocation())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 일시 실패 — 다음 주기 재시도
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── FCM (콜 푸시) ─────────────────────────────────────────────────────

    /**
     * 실연동: PUT /drivers/fcm-token (토큰 기반 @CurrentDriver — 로그인 + 기사 등록 필수).
     * 미로그인이면 보관만 하고, 로그인/가입 성공 시 [flushFcmTokenAsync] 가 자동 전송한다.
     * 전송 실패(404 DRIVER_NOT_REGISTERED 포함)는 무시 — 푸시 미수신일 뿐 앱 동작엔 지장 없다.
     */
    override suspend fun registerFcmToken(token: String) {
        lastFcmToken = token
        if (!tokenStore.isLoggedIn()) return   // pending — 로그인/가입 성공 시 자동 전송
        sendFcmTokenQuietly(token)
    }

    /**
     * CALL_OPENED 처리: GET /dispatch/calls/{partyId} → CallSummary → 콜 피드 추가.
     * 실패(미로그인 401, 이미 마감 409 CALL_CLOSED, 네트워크)는 null — 알림은 payload 텍스트로 폴백한다.
     */
    override suspend fun handleCallOpened(partyId: String): CallSummary? {
        val id = partyId.toLongOrNull() ?: return null
        refreshLocation()   // 픽업 거리·ETA 를 실위치 기준으로 계산
        return try {
            dispatchApi.getPartyDetail(id)
                .toCallSummary(lastLatitude, lastLongitude)
                .also { callFeed.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** CALL_CLOSED 처리: 피드에서 제거 (서버 호출 없음) */
    override suspend fun handleCallClosed(partyId: String) {
        callFeed.remove(partyId)
    }

    /** fire-and-forget 토큰 전송 — 404(기사 미등록)·네트워크 오류 전부 무시 */
    private suspend fun sendFcmTokenQuietly(token: String) {
        try {
            driverApi.registerFcmToken(RegisterFcmTokenRequestDto(token))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 무시 — 다음 로그인/토큰 회전 때 재시도된다
        }
    }

    /** 로그인/가입 성공 직후 보관 중인 토큰을 비동기 전송. 호출 코루틴을 막지 않는다 */
    private fun flushFcmTokenAsync() {
        val token = lastFcmToken ?: return
        fcmScope.launch { sendFcmTokenQuietly(token) }
    }

    // ── 긴급 신고 (D15 — POST /api/v1/reports) ─────────────────────────────

    /**
     * 실연동: POST /reports (토큰 기반 @CurrentUser). partyId 는 tripId 파싱값 —
     * 더미 트립(숫자 아님)이면 null 로 보내 신고자·위치만이라도 서버에 남긴다.
     * 위치는 하트비트와 같은 [refreshLocation] (실측, 실패 시 직전 좌표).
     * 실패는 한국어 예외 전파 — 화면은 저장 실패와 무관하게 112 다이얼로 진행한 뒤 배너를 띄운다.
     */
    override suspend fun reportEmergency(tripId: String): Long {
        val location = refreshLocation()
        val response = try {
            reportApi.report(
                ReportRequestDto(
                    partyId = tripId.toLongOrNull(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            throw IllegalStateException("신고 접수 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        } catch (e: Exception) {
            throw IllegalStateException("신고 접수 실패: ${e.message ?: "네트워크 오류"}", e)
        }
        return requireNotNull(response.reportId) { "신고 접수 실패: 서버 응답에 reportId 가 없습니다" }
    }

    /**
     * 실연동: PATCH /reports/call-result (204) — 서버가 내 최근 신고에 실제 통화 여부를 기록한다.
     * 실패는 한국어 예외 전파 (화면 유지 + 재시도).
     */
    override suspend fun confirmEmergencyCall(called: Boolean) {
        try {
            reportApi.confirmCallResult(CallResultRequestDto(called = called))
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            throw IllegalStateException("통화 여부 기록 실패: ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        } catch (e: Exception) {
            throw IllegalStateException("통화 여부 기록 실패: ${e.message ?: "네트워크 오류"}", e)
        }
    }

    // ── 콜 ────────────────────────────────────────────────────────────────

    /**
     * FCM 으로 도착한 **실콜만** 최신 도착 순으로 돌려준다 (더미 콜 병합 없음).
     * 기사 기준 "열린 콜 목록" 조회 API 가 백엔드에 없어(CallCandidates 가 partyId 키의 Redis set)
     * 실콜은 CALL_OPENED 푸시 → [handleCallOpened] 경로로만 피드에 들어온다.
     *
     * 더미 3건 병합을 제거한 이유: 홈 배너("지금 들어온 콜 N건")·콜 리스트가 서버에 없는 콜을
     * 상시 노출해 실제 인입 콜과 구분되지 않았다. 실콜이 없으면 목록은 빈 상태("지금 받을 수 있는 콜이 없어요")다.
     */
    override suspend fun getCalls(): List<CallSummary> = callFeed.snapshot()

    /** 실연동: GET /dispatch/calls/{partyId} (토큰 기반). callId 가 partyId(숫자)일 때만 — 더미 콜 id 는 위임 */
    override suspend fun getCallDetail(callId: String): CallDetail {
        val partyId = callId.toLongOrNull() ?: return fallback.getCallDetail(callId)
        refreshLocation()   // 픽업 거리·ETA 를 실위치 기준으로 계산
        return try {
            dispatchApi.getPartyDetail(partyId).toCallDetail(lastLatitude, lastLongitude)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback.getCallDetail(callId)
        }
    }

    /**
     * 실연동: POST /dispatch/calls/{partyId}/accept (토큰 기반).
     * accept 성공 시 서버가 콜 후보를 비우므로(CALL_CLOSED) 파티 상세를 먼저 조회해 ActiveTrip 을 구성한다.
     * 상태 전이 액션 — 실콜 실패는 예외 전파 (화면 유지 + 재시도).
     */
    override suspend fun acceptCall(callId: String): ActiveTrip {
        val partyId = callId.toLongOrNull() ?: return fallback.acceptCall(callId)
        refreshLocation()   // 픽업까지 남은 거리·ETA 를 실위치 기준으로 계산
        val party = try {
            dispatchApi.getPartyDetail(partyId)
        } catch (e: retrofit2.HttpException) {
            throw closedCallOr(e, "콜 정보를 불러오지 못했어요")
        }
        try {
            dispatchApi.acceptCall(partyId)
        } catch (e: retrofit2.HttpException) {
            throw closedCallOr(e, "수락하지 못했어요")
        }
        callFeed.remove(callId)   // 수락된 콜은 피드에서 소거 (콜 목록 재노출 방지)
        return party.toActiveTrip(vehicleInfoLabel, lastLatitude, lastLongitude)
            .also { remoteTrip = it }
    }

    /**
     * 실연동: POST /dispatch/calls/{partyId}/reject (토큰 기반). 더미 콜 id 는 위임.
     * 이미 마감된 콜(409 CALL_CLOSED)의 거절은 성공으로 취급한다 — 거절하려던 결과(콜이 사라짐)가 이미 이뤄졌다.
     */
    override suspend fun declineCall(callId: String) {
        val partyId = callId.toLongOrNull() ?: return fallback.declineCall(callId)
        try {
            dispatchApi.rejectCall(partyId)
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 409) throw IllegalStateException("거절 처리에 실패했어요 · 잠시 후 다시 시도해 주세요", e)
        } finally {
            callFeed.remove(callId)   // 거절한 콜은 목록에서 즉시 내린다
        }
    }

    /**
     * 콜 TTL 만료·타 기사 선점으로 콜이 닫힌 409(CALL_CLOSED)를 사용자 문구로 바꾼다.
     * 서버 콜 TTL 이 짧아 수락 지연 시 흔하게 발생하므로, 일반 오류와 구분해 안내한다.
     */
    private fun closedCallOr(e: retrofit2.HttpException, fallbackMessage: String): IllegalStateException =
        if (e.code() == 409) {
            IllegalStateException("이미 마감된 콜이에요 · 다음 콜을 기다려 주세요", e)
        } else {
            IllegalStateException("$fallbackMessage · ${e.serverMessage() ?: "서버 오류(${e.code()})"}", e)
        }

    /** 백엔드 미구현 — 더미 위임 */
    override suspend fun getMissedCalls(): List<MissedCall> = fallback.getMissedCalls()

    /** 백엔드 미구현 — 더미 위임 */
    override suspend fun getPromotion(): Promotion = fallback.getPromotion()

    // ── 운행 ──────────────────────────────────────────────────────────────

    override suspend fun getActiveTrip(): ActiveTrip? = remoteTrip ?: fallback.getActiveTrip()

    /**
     * 실연동: POST /dispatch/rides/{partyId}/board/{driverId}.
     * 서버는 파티 단위 board 1회(전원 탑승 → IN_RIDE)만 지원하므로 첫 탑승 확인 시에만 서버를 호출하고,
     * 승객별 탑승 체크는 로컬 상태 전이로 처리한다.
     */
    override suspend fun confirmBoarding(tripId: String, passengerId: String): ActiveTrip {
        val trip = remoteTrip
        if (trip == null || trip.id != tripId) return fallback.confirmBoarding(tripId, passengerId)
        val partyId = requireNotNull(tripId.toLongOrNull()) { "remote trip id 는 partyId 여야 합니다: $tripId" }
        if (trip.passengers.none { it.boarded }) {
            dispatchApi.board(partyId)
        }
        val passengers = trip.passengers.map { if (it.id == passengerId) it.copy(boarded = true) else it }
        val phase = if (passengers.all { it.boarded || it.noShow }) TripPhase.IN_TRIP else TripPhase.BOARDING
        return trip.copy(
            passengers = passengers,
            phase = phase,
            nextStopIndex = (trip.nextStopIndex + 1).coerceAtMost(trip.stops.lastIndex),
        ).also { remoteTrip = it }
    }

    /**
     * 실연동: POST /dispatch/rides/{partyId}/arrive (토큰 기반) — 승객 "기사 도착" 푸시 트리거.
     * 알림 전용이라 실패(NOT_AWAITING_PICKUP 등)는 삼키고 플로우를 막지 않는다. 더미 트립은 no-op.
     */
    override suspend fun notifyPickupArrival(tripId: String) {
        val partyId = tripId.toLongOrNull() ?: return fallback.notifyPickupArrival(tripId)
        try {
            dispatchApi.arrive(partyId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 알림 트리거 실패는 무시 — 탑승 확인 플로우 우선
        }
    }

    /** 백엔드 미구현(노쇼 API 없음) — remote trip 은 로컬 전이, 그 외 더미 위임 */
    override suspend fun markNoShow(tripId: String, passengerId: String): ActiveTrip {
        val trip = remoteTrip
        if (trip == null || trip.id != tripId) return fallback.markNoShow(tripId, passengerId)
        val passengers = trip.passengers.map { if (it.id == passengerId) it.copy(noShow = true) else it }
        return trip.copy(passengers = passengers).also { remoteTrip = it }
    }

    /** 백엔드 미구현(개별 하차 API 없음 — 종료는 complete 1회) — remote trip 은 로컬 전이, 그 외 더미 위임 */
    override suspend fun completeDropoff(tripId: String, passengerId: String): ActiveTrip {
        val trip = remoteTrip
        if (trip == null || trip.id != tripId) return fallback.completeDropoff(tripId, passengerId)
        val passengers = trip.passengers.map { if (it.id == passengerId) it.copy(droppedOff = true) else it }
        val allDone = passengers.all { it.droppedOff || it.noShow }
        return trip.copy(
            passengers = passengers,
            phase = if (allDone) TripPhase.FARE_INPUT else TripPhase.IN_TRIP,
            nextStopIndex = (trip.nextStopIndex + 1).coerceAtMost(trip.stops.lastIndex),
        ).also { remoteTrip = it }
    }

    /**
     * 실연동: POST /dispatch/rides/{partyId}/complete (토큰 기반, body: 미터기 요금).
     * 서버는 정산 내역을 돌려주지 않으므로 FareResult 는 로컬 규칙(수수료 5%)으로 계산한다.
     * 상태 전이 액션 — 실운행 실패는 예외 전파.
     */
    override suspend fun submitFinalFare(tripId: String, meterFare: Int): FareResult {
        val trip = remoteTrip
        if (trip == null || trip.id != tripId) return fallback.submitFinalFare(tripId, meterFare)
        val partyId = requireNotNull(tripId.toLongOrNull()) { "remote trip id 는 partyId 여야 합니다: $tripId" }
        dispatchApi.complete(partyId, CompleteRideRequestDto(fare = meterFare))
        remoteTrip = null
        return calcFareResult(meterFare, DispatchRules.CALL_FEE, trip.poolBonus)
    }

    // ── 정산 · 평점 · 이력 (백엔드 미구현 — 더미 위임) ─────────────────────

    override suspend fun getSettlementDetail(): SettlementDetail = fallback.getSettlementDetail()

    override suspend fun getRatingSummary(): RatingSummary = fallback.getRatingSummary()

    override suspend fun getTripHistory(): List<TripHistoryItem> = fallback.getTripHistory()

    override suspend fun getTripDetail(tripId: String): TripHistoryDetail = fallback.getTripDetail(tripId)

    // ── 내부 ──────────────────────────────────────────────────────────────

    companion object {
        /** 영업중 위치 하트비트 주기 — 서버 후보 TTL(30초)의 절반 */
        const val HEARTBEAT_INTERVAL_MS = 15_000L

        /** 이름 조회 실패·이름 미입력 폴백 표시명 — MvpProfileDefaults.NAME 과 동일 */
        const val DEFAULT_DRIVER_NAME = "기사"


        const val DEFAULT_SEATS = 4
        const val DEFAULT_BANK_NAME = "국민"
        const val DEFAULT_ACCOUNT_NUMBER = "000000000000"
        const val DEFAULT_VEHICLE_LABEL = "쏘나타 34가 1234"

        /** 측위 전(권한 미허용·GPS 미수신) 폴백 좌표 — 강남역 */
        const val DEFAULT_LATITUDE = 37.4980
        const val DEFAULT_LONGITUDE = 127.0276
    }
}
