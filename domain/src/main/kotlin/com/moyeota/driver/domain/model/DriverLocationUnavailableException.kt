package com.moyeota.driver.domain.model

/**
 * 위치를 확인할 수 없어 영업을 시작할 수 없음 — 화면은 [message] 를 그대로 보여준다.
 *
 * 서버는 영업 시작·하트비트로 받은 좌표로 콜 반경(findNearby)을 판정한다. 그래서 측위 실패 시
 * 기본 좌표(예전 구현의 강남역)를 대신 보내면 **부산에 있는 기사에게 서울 콜이 배차된다** —
 * 이 예외는 "좌표를 지어내느니 영업을 시작하지 않는다"는 정책의 신호다.
 *
 * 던져지는 지점은 `DriverRepository.setDutyStatus(online = true)` 하나뿐이며,
 * 이때 기사는 영업 상태로 전환되지 않는다(서버 호출도 하지 않는다).
 *
 * [CallException] 과 같은 자리·같은 방식으로 IllegalStateException 을 상속해,
 * 메시지만 읽던 기존 화면 코드(`e.message`)와 그대로 호환된다.
 */
class DriverLocationUnavailableException(
    message: String = "위치를 확인할 수 없어요",
) : IllegalStateException(message)
