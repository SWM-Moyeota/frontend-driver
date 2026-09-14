package com.moyeota.driver.domain.location

/** 기사 현재 좌표 (WGS84) */
data class DriverCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/**
 * 기사 단말의 실측 위치 공급자. 안드로이드 LocationManager 구현은 app 모듈이 소유하고,
 * data 계층(RemoteDriverRepository)은 이 인터페이스로만 위치를 읽는다.
 *
 * [current] 는 즉시 반환(마지막으로 알려진 좌표)이며, 권한 미허용·측위 전이면 null 이다.
 *
 * **null 을 좌표로 대체하지 않는다.** 서버는 보고된 좌표로 콜 반경을 판정하므로 기본 좌표 폴백은
 * 실제 위치와 무관한 배차를 만든다(강남역 기본값 회귀 — _workspace/28). 호출부는 null 이면
 * 보고를 건너뛰거나(하트비트) 영업 시작을 실패시킨다(DriverLocationUnavailableException).
 */
fun interface DriverLocationSource {
    fun current(): DriverCoordinate?
}
