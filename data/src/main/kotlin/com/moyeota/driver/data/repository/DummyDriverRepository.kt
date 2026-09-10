package com.moyeota.driver.data.repository

import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallDetail
import com.moyeota.driver.domain.model.CallSummary
import com.moyeota.driver.domain.model.CallType
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.model.DutyStatus
import com.moyeota.driver.domain.model.FareResult
import com.moyeota.driver.domain.model.HomeSummary
import com.moyeota.driver.domain.model.LoginResult
import com.moyeota.driver.domain.model.MissedCall
import com.moyeota.driver.domain.model.MissedReason
import com.moyeota.driver.domain.model.Promotion
import com.moyeota.driver.domain.model.QualificationCheckResult
import com.moyeota.driver.domain.model.RatingSummary
import com.moyeota.driver.domain.model.ReviewComment
import com.moyeota.driver.domain.model.RouteStop
import com.moyeota.driver.domain.model.SettlementDay
import com.moyeota.driver.domain.model.SettlementDetail
import com.moyeota.driver.domain.model.SettlementSummary
import com.moyeota.driver.domain.model.SettlementTripRow
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.model.TripHistoryDetail
import com.moyeota.driver.domain.model.TripHistoryItem
import com.moyeota.driver.domain.model.TripHistoryStatus
import com.moyeota.driver.domain.model.TripPassenger
import com.moyeota.driver.domain.model.TripPhase
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.delay

/**
 * 백엔드 미구현 — 더미 연동.
 * 기사용 API 가 백엔드에 생기면 RemoteDriverRepository 로 교체하고 이 클래스는 개발용으로만 유지한다.
 * 영업 상태·운행 단계는 인메모리로 상태 전이를 흉내 내 화면 플로우 전체를 걸어볼 수 있게 한다.
 */
class DummyDriverRepository : DriverRepository {

    private var dutyStatus = DutyStatus.OFFLINE
    private var activeTrip: ActiveTrip? = null

    private val poolStops = listOf(
        RouteStop(0, StopKind.PICKUP, "강남역 2번 출구", "김*진"),
        RouteStop(1, StopKind.PICKUP, "역삼역 4번 출구", "이*원"),
        RouteStop(2, StopKind.DROPOFF, "판교역 1번 출구", "김*진"),
        RouteStop(3, StopKind.DROPOFF, "정자역 3번 출구", "이*원"),
    )

    private val calls = listOf(
        CallSummary(
            id = "call-1", type = CallType.POOL,
            pickupPlace = "강남역 2번 출구", dropoffPlace = "판교역 방면 2곳",
            distanceToPickupKm = 0.8, expectedFare = 18400, callFee = 3000, poolBonus = 1500,
            passengerCount = 2, createdAtLabel = "방금 전",
        ),
        CallSummary(
            id = "call-2", type = CallType.SOLO,
            pickupPlace = "역삼역 4번 출구", dropoffPlace = "서울대입구역",
            distanceToPickupKm = 1.2, expectedFare = 12800, callFee = 3000, poolBonus = 0,
            passengerCount = 1, createdAtLabel = "1분 전",
        ),
        CallSummary(
            id = "call-3", type = CallType.SOLO,
            pickupPlace = "선릉역 5번 출구", dropoffPlace = "잠실역",
            distanceToPickupKm = 2.1, expectedFare = 9600, callFee = 3000, poolBonus = 0,
            passengerCount = 1, createdAtLabel = "3분 전",
        ),
    )

    override suspend fun login(loginId: String, password: String): LoginResult {
        delay(400)
        return LoginResult(status = DriverAccountStatus.APPROVED, driverName = "박기사")
    }

    override suspend fun isPhoneRegistered(phoneNumber: String): Boolean {
        delay(300)
        // 백엔드 조회 API 미구현 — 기본 "미등록". 기가입 분기 UI 테스트용으로 9999로 끝나는 번호만 true.
        return phoneNumber.filter(Char::isDigit).endsWith("9999")
    }

    override suspend fun signUp(form: com.moyeota.driver.domain.model.DriverSignUpForm): LoginResult {
        delay(600)
        return LoginResult(status = DriverAccountStatus.APPROVED, driverName = "박기사")
    }

    override suspend fun logout() {
        delay(200)
    }

    override suspend fun requestOtp(phone: String) {
        delay(300)
    }

    override suspend fun verifyOtp(phone: String, code: String): Boolean {
        delay(300)
        return code.length == 6
    }

    override suspend fun checkQualifications(
        licenseNumber: String,
        licenseSerial: String,
        taxiCertNumber: String,
    ): QualificationCheckResult {
        delay(1200)
        return QualificationCheckResult(
            licenseVerified = true,
            licenseStatusOk = true,
            taxiCertVerified = true,
            vehicleVerified = true,
            insuranceVerified = true,
        )
    }

    override suspend fun registerVehicle(
        plateNumber: String,
        vehicleModel: String,
        companyName: String,
        acceptsPool: Boolean,
    ) {
        delay(400)
    }

    override suspend fun getHomeSummary(): HomeSummary {
        delay(300)
        return homeSummary()
    }

    override suspend fun setDutyStatus(online: Boolean): HomeSummary {
        delay(300)
        dutyStatus = if (online) DutyStatus.ONLINE else DutyStatus.OFFLINE
        return homeSummary()
    }

    private fun homeSummary() = HomeSummary(
        dutyStatus = dutyStatus,
        driverName = "박기사",
        todayEarnings = 86200,
        todayTripCount = 7,
        onlineMinutes = 312,
        promotionBanner = "8월 합승 프로모션 — 합승 1건당 보너스 1,500원",
    )

    // ── FCM (백엔드 미구현 — 더미 no-op) ───────────────────────────────────

    override suspend fun registerFcmToken(token: String) {
        // no-op — 더미는 서버 등록 없음
    }

    /** 더미는 푸시 피드가 없다 — 임시 요약을 보관하지 않는다 (실콜 경로 전용 기능) */
    override fun seedCallPreview(
        partyId: String,
        departure: String?,
        destination: String?,
        memberCount: Int?,
        estimatedFare: Int?,
    ): CallSummary? = null

    override fun peekCallSummary(callId: String): CallSummary? = calls.firstOrNull { it.id == callId }

    /** 더미 콜 첫 건을 "새로 열린 콜"로 흉내 낸다 (알림 표시 경로 테스트용) */
    override suspend fun handleCallOpened(partyId: String): CallSummary? = calls.first()

    override suspend fun handleCallClosed(partyId: String) {
        // no-op — 더미 피드 없음
    }

    override suspend fun getCalls(): List<CallSummary> {
        delay(300)
        return calls
    }

    override suspend fun getCallDetail(callId: String): CallDetail {
        delay(300)
        val summary = calls.firstOrNull { it.id == callId } ?: calls.first()
        val stops = if (summary.type == CallType.POOL) {
            poolStops
        } else {
            listOf(
                RouteStop(0, StopKind.PICKUP, summary.pickupPlace, "김*진"),
                RouteStop(1, StopKind.DROPOFF, summary.dropoffPlace, "김*진"),
            )
        }
        return CallDetail(summary = summary, stops = stops, countdownSeconds = 15, etaToPickupMin = 4)
    }

    override suspend fun acceptCall(callId: String): ActiveTrip {
        delay(400)
        val summary = calls.firstOrNull { it.id == callId } ?: calls.first()
        val stops = if (summary.type == CallType.POOL) {
            poolStops
        } else {
            listOf(
                RouteStop(0, StopKind.PICKUP, summary.pickupPlace, "김*진"),
                RouteStop(1, StopKind.DROPOFF, summary.dropoffPlace, "김*진"),
            )
        }
        val passengers = stops.filter { it.kind == StopKind.PICKUP }.mapIndexed { i, stop ->
            TripPassenger(
                id = "p-$i", maskedName = stop.passengerMaskedName,
                pickupPlace = stop.place,
                dropoffPlace = stops.first { it.kind == StopKind.DROPOFF && it.passengerMaskedName == stop.passengerMaskedName }.place,
                boarded = false, droppedOff = false, noShow = false,
            )
        }
        val trip = ActiveTrip(
            id = "trip-${summary.id}", type = summary.type, phase = TripPhase.ASSIGNED,
            passengers = passengers, stops = stops, nextStopIndex = 0,
            remainingKm = summary.distanceToPickupKm, remainingMin = 4,
            vehicleInfoLabel = "쏘나타 34가 1234", poolBonus = summary.poolBonus,
        )
        activeTrip = trip
        return trip
    }

    override suspend fun declineCall(callId: String) {
        delay(200)
    }

    override suspend fun getMissedCalls(): List<MissedCall> {
        delay(300)
        return listOf(
            MissedCall("m-1", CallType.POOL, "삼성역 6번 출구", "수서역 방면 2곳", 21900, "오늘 21:14", MissedReason.TIMEOUT),
            MissedCall("m-2", CallType.SOLO, "교대역 1번 출구", "사당역", 14200, "오늘 20:47", MissedReason.DECLINED),
            MissedCall("m-3", CallType.SOLO, "양재역 9번 출구", "수원 인계동", 32800, "어제 23:31", MissedReason.TIMEOUT),
        )
    }

    override suspend fun getPromotion(): Promotion {
        delay(300)
        return Promotion(
            title = "8월 합승 프로모션",
            bonusPerPool = 1500,
            periodLabel = "2026.08.01 ~ 2026.08.31",
            accruedAmount = 13500,
            accruedCount = 9,
            conditions = listOf(
                "합승 2인 이상 · 운행 완주 건만 지급",
                "노쇼로 1인이 되면 미지급",
                "취소 · 기사 귀책 중단 건 미지급",
                "보너스는 주간 정산에 합산 지급",
            ),
        )
    }

    override suspend fun getActiveTrip(): ActiveTrip? {
        delay(200)
        return activeTrip
    }

    override suspend fun notifyPickupArrival(tripId: String) {
        delay(100)
    }

    /** 서버 board(파티 단위 1회)와 동일하게 전원 탑승 + 운행 중 전환을 흉내 낸다 */
    override suspend fun startRide(tripId: String): ActiveTrip {
        delay(300)
        return updateTrip { trip ->
            val firstDropoff = trip.stops.indexOfFirst { it.kind == StopKind.DROPOFF }
            trip.copy(
                passengers = trip.passengers.map { it.copy(boarded = true) },
                phase = TripPhase.IN_TRIP,
                nextStopIndex = if (firstDropoff >= 0) firstDropoff else trip.nextStopIndex,
            )
        }
    }

    override suspend fun submitFinalFare(tripId: String, meterFare: Int): FareResult {
        delay(400)
        val bonus = activeTrip?.poolBonus ?: 0
        val callFee = 3000
        val serviceFee = ((meterFare + callFee) * 5) / 100 / 10 * 10
        activeTrip = null
        return FareResult(
            meterFare = meterFare,
            callFee = callFee,
            serviceFee = serviceFee,
            poolBonus = bonus,
            driverPayout = meterFare + callFee - serviceFee + bonus,
        )
    }

    private inline fun updateTrip(transform: (ActiveTrip) -> ActiveTrip): ActiveTrip {
        val trip = checkNotNull(activeTrip) { "진행 중인 운행이 없습니다" }
        return transform(trip).also { activeTrip = it }
    }

    override suspend fun getSettlementDetail(): SettlementDetail {
        delay(300)
        val days = listOf(
            SettlementDay("8/25 (월)", 9, 118400),
            SettlementDay("8/26 (화)", 11, 142700),
            SettlementDay("8/27 (수)", 8, 103900),
            SettlementDay("8/28 (목)", 12, 156200),
            SettlementDay("8/29 (금)", 14, 189300),
            SettlementDay("8/30 (토)", 7, 86200),
        )
        val summary = SettlementSummary(
            weekLabel = "8/25 ~ 8/31",
            totalPayout = days.sumOf { it.payout },
            totalFare = 838000,
            totalServiceFee = 41900,
            totalBonus = 13500,
            payoutDateLabel = "9/3 (수) 지급 예정",
            bankAccountLabel = "국민 ****1234",
            days = days,
        )
        val trips = listOf(
            SettlementTripRow("h-1", "22:41", CallType.POOL, "강남역 → 판교 · 정자", 21400, 1500, 21830),
            SettlementTripRow("h-2", "21:58", CallType.SOLO, "역삼역 → 서울대입구", 15800, 0, 15010),
            SettlementTripRow("h-3", "21:02", CallType.SOLO, "선릉역 → 잠실", 12600, 0, 11970),
        )
        return SettlementDetail(summary = summary, trips = trips)
    }

    override suspend fun getRatingSummary(): RatingSummary {
        delay(300)
        return RatingSummary(
            average = 4.8,
            totalCount = 214,
            distribution = mapOf(5 to 178, 4 to 26, 3 to 7, 2 to 2, 1 to 1),
            comments = listOf(
                ReviewComment("r-1", "h-1", 5, "합승인데도 경유가 빨랐어요. 친절합니다.", "8/29"),
                ReviewComment("r-2", "h-2", 5, "차가 깨끗하고 운전이 편안했어요.", "8/28"),
                ReviewComment("r-3", "h-3", 4, "약간 돌아갔지만 전반적으로 좋았어요.", "8/27"),
            ),
        )
    }

    override suspend fun getTripHistory(): List<TripHistoryItem> {
        delay(300)
        return listOf(
            TripHistoryItem("h-1", "8/29 (금) 22:41", CallType.POOL, "강남역 → 판교 · 정자", 21400, 21830, TripHistoryStatus.COMPLETED),
            TripHistoryItem("h-2", "8/29 (금) 21:58", CallType.SOLO, "역삼역 → 서울대입구", 15800, 15010, TripHistoryStatus.COMPLETED),
            TripHistoryItem("h-3", "8/29 (금) 21:02", CallType.SOLO, "선릉역 → 잠실", 12600, 11970, TripHistoryStatus.COMPLETED),
            TripHistoryItem("h-4", "8/28 (목) 23:19", CallType.POOL, "홍대입구 → 마곡 · 발산", 19800, 20310, TripHistoryStatus.COMPLETED),
            TripHistoryItem("h-5", "8/28 (목) 22:05", CallType.SOLO, "신촌역 → 은평뉴타운", 16400, 15580, TripHistoryStatus.CANCELED),
        )
    }

    override suspend fun getTripDetail(tripId: String): TripHistoryDetail {
        delay(300)
        val item = getTripHistory().firstOrNull { it.id == tripId } ?: getTripHistory().first()
        return TripHistoryDetail(
            item = item,
            stops = poolStops,
            fareResult = FareResult(meterFare = 18400, callFee = 3000, serviceFee = 1070, poolBonus = 1500, driverPayout = 21830),
            passengerCount = 2,
            durationMin = 38,
            distanceKm = 18.6,
        )
    }
}
