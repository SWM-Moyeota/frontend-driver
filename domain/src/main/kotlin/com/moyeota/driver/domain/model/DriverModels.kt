package com.moyeota.driver.domain.model

// ── 인증 · 가입 (D01~D05b) ──────────────────────────────────────────────

enum class DriverAccountStatus { APPROVED, PENDING_REVIEW }

data class LoginResult(
    val status: DriverAccountStatus,
    val driverName: String,
)

/**
 * 기사 가입 폼 (phone-first 3단계) — ①휴대폰 확인 → ②프로필(이름·아이디·비밀번호) → ③택시 정보.
 * 휴대폰 OTP 인증·면허 자동 조회는 MVP 범위에서 제외 (번호 존재 조회만 수행).
 */
data class DriverSignUpForm(
    val phoneNumber: String,          // 1단계 입력 — user_profile 조회 키
    val name: String,                 // 2단계 프로필 (실명)
    val nickname: String,             // 동승자에게 보이는 이름 (2~10자 한글·영문·숫자, 서버 중복 불가)
    val loginId: String,
    val password: String,
    val vehicleType: String,          // 3단계 — 차종
    val seats: Int,                   // 좌석수 (백엔드 검증: 2 이상)
    val plateNumber: String,          // 번호판(차량번호)
    val qualificationNumber: String,  // 운수종사자 번호
)

/** D03 자동 조회 결과. 전 항목 통과 시에만 즉시 승인(D05b), 하나라도 실패면 예외 심사(D05). */
data class QualificationCheckResult(
    val licenseVerified: Boolean,          // 운전면허 진위
    val licenseStatusOk: Boolean,          // 면허 정지·취소 여부
    val taxiCertVerified: Boolean,         // 택시운전자격
    val vehicleVerified: Boolean,          // 자동차등록원부
    val insuranceVerified: Boolean,        // 자동차보험
) {
    val allPassed: Boolean
        get() = licenseVerified && licenseStatusOk && taxiCertVerified && vehicleVerified && insuranceVerified
}

// ── 홈 · 영업 상태 (D06~D08) ────────────────────────────────────────────

enum class DutyStatus { OFFLINE, ONLINE }

data class HomeSummary(
    val dutyStatus: DutyStatus,
    val driverName: String,
    val todayEarnings: Int,               // 오늘 수입 (원)
    val todayTripCount: Int,
    val onlineMinutes: Int,               // 오늘 영업 시간(분)
    val promotionBanner: String?,         // 합승 프로모션 배너 문구 (없으면 null)
)

// ── 콜 (D09~D12, D22) ──────────────────────────────────────────────────

enum class CallType { SOLO, POOL }

data class CallSummary(
    val id: String,
    val type: CallType,
    val pickupPlace: String,
    val dropoffPlace: String,
    val distanceToPickupKm: Double,       // 픽업지까지 거리
    val expectedFare: Int,                // 예상 미터기 요금
    val callFee: Int,                     // 호출료
    val poolBonus: Int,                   // 합승 보너스 (단독 콜이면 0)
    val passengerCount: Int,               // 0 = 인원 미상 (푸시 임시 요약에서만 발생)
    val createdAtLabel: String,           // "방금 전" 등
    /**
     * CALL_OPENED 푸시 데이터만으로 만든 **임시** 요약인지.
     * 상세 조회(GET /dispatch/calls/{partyId}) 전이라 인원·요금이 비어 있을 수 있고,
     * 서버 상세가 도착하면 확정 요약(provisional = false)으로 교체된다.
     * 화면은 이 플래그가 아니라 [hasPassengerCount] · [hasFareEstimate] 로 개별 항목의 유무를 판단한다.
     */
    val provisional: Boolean = false,
) {
    val expectedTotal: Int get() = expectedFare + callFee + poolBonus

    /** 합승 인원을 아는가 — 모르면 화면은 "합승 N인" 대신 "합승"만 쓴다 */
    val hasPassengerCount: Boolean get() = passengerCount > 0

    /** 예상 수익을 표시할 만큼 요금 정보가 있는가 — 없으면 "요금 확인 중" */
    val hasFareEstimate: Boolean get() = expectedTotal > 0
}

data class RouteStop(
    val order: Int,
    val kind: StopKind,
    val place: String,
    val passengerMaskedName: String,
)

enum class StopKind { PICKUP, DROPOFF }

data class CallDetail(
    val summary: CallSummary,
    val stops: List<RouteStop>,
    val countdownSeconds: Int,            // 수락 가능 시간 (기본 15초)
    val etaToPickupMin: Int,
)

data class MissedCall(
    val id: String,
    val type: CallType,
    val pickupPlace: String,
    val dropoffPlace: String,
    val expectedTotal: Int,
    val missedAtLabel: String,
    val reason: MissedReason,
)

enum class MissedReason { TIMEOUT, DECLINED }

data class Promotion(
    val title: String,
    val bonusPerPool: Int,                // 합승 1건당 보너스
    val periodLabel: String,
    val accruedAmount: Int,               // 이번 주 적립액
    val accruedCount: Int,
    val conditions: List<String>,
)

// ── 운행 (D11, D13~D16b) ───────────────────────────────────────────────

enum class TripPhase { ASSIGNED, TO_PICKUP, BOARDING, IN_TRIP, FARE_INPUT, COMPLETED }

data class TripPassenger(
    val id: String,
    val maskedName: String,               // "김*진"
    val pickupPlace: String,
    val dropoffPlace: String,
    val boarded: Boolean,
    val droppedOff: Boolean,
    val noShow: Boolean,
)

data class ActiveTrip(
    val id: String,
    val type: CallType,
    val phase: TripPhase,
    val passengers: List<TripPassenger>,
    val stops: List<RouteStop>,
    val nextStopIndex: Int,               // stops 중 현재 목표
    val remainingKm: Double,
    val remainingMin: Int,
    val vehicleInfoLabel: String,         // "쏘나타 34가 1234"
    val poolBonus: Int,
)

/** D16 요금 확정 결과. 기사 정산액 = 승객 청구 총액 − 서비스 수수료(5%) ± 보너스·차감 */
data class FareResult(
    val meterFare: Int,
    val callFee: Int,
    val serviceFee: Int,                  // 수수료 (5%)
    val poolBonus: Int,
    val driverPayout: Int,
)

// ── 정산 (D17~D18) ─────────────────────────────────────────────────────

data class SettlementDay(
    val dateLabel: String,                // "8/29 (금)"
    val tripCount: Int,
    val payout: Int,
)

data class SettlementSummary(
    val weekLabel: String,                // "8/25 ~ 8/31"
    val totalPayout: Int,
    val totalFare: Int,
    val totalServiceFee: Int,
    val totalBonus: Int,
    val payoutDateLabel: String,          // 지급 예정일
    val bankAccountLabel: String,         // "국민 ****1234"
    val days: List<SettlementDay>,
)

data class SettlementTripRow(
    val tripId: String,
    val timeLabel: String,
    val type: CallType,
    val routeLabel: String,               // "강남역 → 판교"
    val fare: Int,
    val bonus: Int,
    val payout: Int,
)

data class SettlementDetail(
    val summary: SettlementSummary,
    val trips: List<SettlementTripRow>,
)

// ── 평점 · 이력 (D19~D21) ──────────────────────────────────────────────

data class ReviewComment(
    val id: String,
    val tripId: String,
    val rating: Int,                      // 1~5
    val text: String,
    val dateLabel: String,
)

data class RatingSummary(
    val average: Double,
    val totalCount: Int,
    val distribution: Map<Int, Int>,      // 별점 → 개수
    val comments: List<ReviewComment>,
)

enum class TripHistoryStatus { COMPLETED, CANCELED }

data class TripHistoryItem(
    val id: String,
    val dateLabel: String,                // "8/29 (금) 22:41"
    val type: CallType,
    val routeLabel: String,
    val fare: Int,
    val payout: Int,
    val status: TripHistoryStatus,
)

data class TripHistoryDetail(
    val item: TripHistoryItem,
    val stops: List<RouteStop>,
    val fareResult: FareResult,
    val passengerCount: Int,
    val durationMin: Int,
    val distanceKm: Double,
)
