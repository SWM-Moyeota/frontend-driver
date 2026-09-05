package com.moyeota.driver.presentation.core

// 라우트 상수 — docs/FIGMA-SCREENS.md 화면 ID 기준
object Routes {
    // GRP/A 가입 · 로그인
    const val AUTH_LOGIN = "auth/login"                        // D01
    const val AUTH_SIGNUP = "auth/signup"                      // MVP 간소 가입 (아이디·비번 + 차량·자격) — D02~D04 대체
    const val AUTH_PHONE = "auth/phone"                        // D02 (MVP 플로우에서 미사용, 화면 보존)
    const val AUTH_QUALIFICATION = "auth/qualification"        // D03
    const val AUTH_QUALIFICATION_RESULT = "auth/qualification-result" // D03b
    const val AUTH_VEHICLE = "auth/vehicle"                    // D04
    const val AUTH_REVIEW_PENDING = "auth/review-pending"      // D05
    const val AUTH_APPROVED = "auth/approved"                  // D05b

    // GRP/B 홈 · 영업 상태
    const val HOME = "home"                                    // D06(휴무) · D07(영업중) 상태 분기
    const val HOME_OFF_DUTY_CONFIRM = "home/off-duty-confirm"  // D08

    // GRP/C 콜
    const val CALL_LIST = "call/list"                          // D09
    const val CALL_DETAIL = "call/detail/{callId}"             // D10
    const val CALL_ASSIGNED = "call/assigned"                  // D11
    const val CALL_MISSED = "call/missed"                      // D12
    const val CALL_PROMOTION = "call/promotion"                // D22

    // GRP/D 운행
    const val TRIP_PICKUP = "trip/pickup"                      // D13
    const val TRIP_BOARDING = "trip/boarding"                  // D14
    const val TRIP_DRIVING = "trip/driving"                    // D15
    const val TRIP_FARE = "trip/fare"                          // D16 (D16b 키패드 포함)

    // GRP/E 정산
    const val SETTLEMENT = "settlement"                        // D17
    const val SETTLEMENT_DETAIL = "settlement/detail"          // D18

    // GRP/F 평점 · 이력
    const val RATING = "rating"                                // D19
    const val HISTORY = "history"                              // D20
    const val HISTORY_DETAIL = "history/detail/{tripId}"       // D21

    fun callDetail(callId: String) = "call/detail/$callId"
    fun historyDetail(tripId: String) = "history/detail/$tripId"
}
