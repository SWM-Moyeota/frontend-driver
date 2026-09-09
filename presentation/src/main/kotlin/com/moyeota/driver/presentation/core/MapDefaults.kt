package com.moyeota.driver.presentation.core

import com.naver.maps.geometry.LatLng

/**
 * 더미 데이터 기준 좌표(강남역) — 콜 상세·운행 화면(CallDetail·TripCommon)의 지도 카메라 기본값.
 * DummyDriverRepository·RemoteDriverRepository 의 고정 좌표와 같은 지점을 바라본다.
 * 홈(D06·D07)은 기사 현재 위치(DriverLocationSource)를 쓰므로 더는 이 상수를 참조하지 않는다.
 */
val GangnamCenter: LatLng = LatLng(37.4979, 127.0276)
