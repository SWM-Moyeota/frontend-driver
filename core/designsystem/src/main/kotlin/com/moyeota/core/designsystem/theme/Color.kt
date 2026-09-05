package com.moyeota.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// 승객 앱 moyeota-figma-tokens.json 의 core.color 를 기반으로 한 기사 앱 다크 토큰.
// 기사 화면은 Dark 모드 고정 (야간 · 거치대 시인성) — 피그마 공통 규칙.
// 브랜드/상태 색은 승객 앱과 동일하게 유지하고, surface/ink 계열만 다크 값으로 뒤집는다.
// 컴포넌트는 승객 v15를 그대로 쓰므로 토큰 이름은 승객 앱과 동일해야 한다.
object MoyeotaColor {
    // primary
    val Primary500 = Color(0xFF3B82F6)    // 다크 배경 위 시인성 위해 한 단계 밝게
    val Primary600 = Color(0xFF085AF5)
    val Primary700 = Color(0xFF054BC7)
    val Primary50 = Color(0xFF11213D)     // 다크에서의 primary 옅은 배경

    // status
    val Success500 = Color(0xFF10B981)
    val Success600 = Color(0xFF34D399)
    val Success50 = Color(0xFF0C2B22)
    val Waiting500 = Color(0xFFF59E0B)
    val Waiting600 = Color(0xFFFBBF24)
    val Waiting50 = Color(0xFF2E2410)
    val Danger500 = Color(0xFFF87171)
    val Danger600 = Color(0xFFE63946)
    val Danger50 = Color(0xFF331418)

    // 비상 신고 전용 — 다른 곳 사용 금지
    val Safety500 = Color(0xFFDC2626)
    val Safety600 = Color(0xFFEF4444)

    // ink / text — 다크 반전
    val InkPrimary = Color(0xFFF5F7FA)    // 본문 대비 최우선 텍스트
    val InkDeep = Color(0xFFFFFFFF)
    val TextBody = Color(0xB8F5F7FA)      // rgba(245,247,250,0.72)
    val TextMute = Color(0xFF9CA3AF)
    val TextAsh = Color(0xFF6B7280)
    val TextOnDark = Color(0xFFFFFFFF)
    val Link = Color(0xFF60A5FA)

    // surface — 다크 반전
    val SurfaceCanvas = Color(0xFF0B0F17) // 화면 바탕
    val SurfaceSoft = Color(0xFF111623)   // 옅은 구분 배경
    val SurfaceCard = Color(0xFF161C2B)   // 카드
    val MapOverlay = Color(0xF0111623)    // rgba(17,22,35,0.94)
    val Hairline = Color(0xFF232B3D)

    // route / marker (지도 위 색 — 승객 앱과 동일)
    val RouteShared = Color(0xFF3B82F6)
    val RouteUserA = Color(0xFF60A5FA)
    val RouteUserB = Color(0xFFA78BFA)
    val MarkerOrigin = Color(0xFF3B82F6)
    val MarkerDestination = Color(0xFFEF4444)
    val MarkerPickup = Color(0xFF10B981)
    val MarkerDropoff = Color(0xFFF97316)

    val Scrim = Color(0x99000000)         // 다크에서는 살짝 진하게
}
