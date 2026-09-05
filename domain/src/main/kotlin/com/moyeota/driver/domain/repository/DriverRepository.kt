package com.moyeota.driver.domain.repository

import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.CallDetail
import com.moyeota.driver.domain.model.CallSummary
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

/**
 * 기사 앱이 보는 유일한 데이터 계약.
 * 백엔드에 기사용 API가 아직 없으므로 현재 구현은 DummyDriverRepository 뿐이다.
 * 실연동 시 RemoteDriverRepository 가 이 인터페이스를 그대로 구현한다.
 */
interface DriverRepository {

    // 인증 · 가입 (D01~D05b)
    suspend fun login(loginId: String, password: String): LoginResult

    /**
     * 가입 1단계: 휴대폰 번호가 이미 user_profile 에 등록돼 있는지 조회.
     * true 면 기존 계정 로그인 유도, false 면 신규 프로필 입력으로 진행.
     * 백엔드 조회 API(GET /users/exists) 미구현 동안은 더미가 "미등록(false)"으로 폴백한다.
     */
    suspend fun isPhoneRegistered(phoneNumber: String): Boolean

    /** 가입 최종 제출 — 계정 생성 → 자동 로그인 → 기사 등록 → 승인 → 차량 등록 일괄 수행 */
    suspend fun signUp(form: DriverSignUpForm): LoginResult

    suspend fun logout()
    suspend fun requestOtp(phone: String)
    suspend fun verifyOtp(phone: String, code: String): Boolean
    suspend fun checkQualifications(
        licenseNumber: String,
        licenseSerial: String,
        taxiCertNumber: String,
    ): QualificationCheckResult
    suspend fun registerVehicle(
        plateNumber: String,
        vehicleModel: String,
        companyName: String,
        acceptsPool: Boolean,
    )

    // 홈 · 영업 상태 (D06~D08)
    suspend fun getHomeSummary(): HomeSummary
    suspend fun setDutyStatus(online: Boolean): HomeSummary

    // FCM (콜 푸시)

    /**
     * FCM 토큰 서버 등록 (PUT /drivers/fcm-token — 로그인 + 기사 등록 필수).
     * 미로그인 상태면 보관해 뒀다가 로그인·가입 성공 시 자동 전송한다. 실패는 조용히 무시(fire-and-forget).
     */
    suspend fun registerFcmToken(token: String)

    /** CALL_OPENED 수신 — 파티 상세를 조회해 콜 피드에 추가, 알림 표시용 요약 반환 (실패 시 null) */
    suspend fun handleCallOpened(partyId: String): CallSummary?

    /** CALL_CLOSED 수신 — 콜 피드에서 제거 */
    suspend fun handleCallClosed(partyId: String)

    // 긴급 신고 (D15 — 신고 버튼 3초 홀드)

    /**
     * 긴급 신고 접수 — 서버가 매칭방(파티) 정보와 신고 시점 위치를 저장하고 신고 id 를 반환한다.
     * 위치는 데이터 계층이 최신 측위값으로 채운다. 실패는 예외 전파 — 단, 화면은 신고 저장 실패와
     * 무관하게 112 다이얼로 진행해야 한다 (통화가 최우선).
     */
    suspend fun reportEmergency(tripId: String): Long

    /** 다이얼에서 복귀 후 "실제 통화 여부" 저장 (서버는 내 최근 신고 기준으로 기록) */
    suspend fun confirmEmergencyCall(called: Boolean)

    // 콜 (D09~D12, D22)
    suspend fun getCalls(): List<CallSummary>
    suspend fun getCallDetail(callId: String): CallDetail
    suspend fun acceptCall(callId: String): ActiveTrip
    suspend fun declineCall(callId: String)
    suspend fun getMissedCalls(): List<MissedCall>
    suspend fun getPromotion(): Promotion

    // 운행 (D13~D16b)
    suspend fun getActiveTrip(): ActiveTrip?

    /**
     * D13 픽업지 도착 통보 — 승객들에게 "기사 도착" 푸시(DRIVER_ARRIVED)를 트리거한다.
     * 알림 전용(상태 전이 없음)이라 실패해도 탑승 확인 플로우는 계속 진행한다.
     */
    suspend fun notifyPickupArrival(tripId: String)
    /** D14 승객별 탑승 확인 — 기사가 육안 확인 후 탭 (서버 코드 검증 API 없음 → 코드 입력 단계 제거). */
    suspend fun confirmBoarding(tripId: String, passengerId: String): ActiveTrip
    suspend fun markNoShow(tripId: String, passengerId: String): ActiveTrip
    suspend fun completeDropoff(tripId: String, passengerId: String): ActiveTrip
    suspend fun submitFinalFare(tripId: String, meterFare: Int): FareResult

    // 정산 (D17~D18)
    suspend fun getSettlementDetail(): SettlementDetail

    // 평점 · 이력 (D19~D21)
    suspend fun getRatingSummary(): RatingSummary
    suspend fun getTripHistory(): List<TripHistoryItem>
    suspend fun getTripDetail(tripId: String): TripHistoryDetail
}
