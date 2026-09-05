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
 * [current] 는 즉시 반환(마지막으로 알려진 좌표)이며, 권한 미허용·측위 전이면 null 이다 —
 * 호출부는 null 일 때 마지막 좌표나 기본 좌표로 폴백한다.
 */
fun interface DriverLocationSource {
    fun current(): DriverCoordinate?
}
