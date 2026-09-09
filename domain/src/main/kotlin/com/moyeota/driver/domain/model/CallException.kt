package com.moyeota.driver.domain.model

/**
 * 콜 조회·수락·거절 실패 — 사용자에게 그대로 보여줄 한국어 문구([message])와
 * 재시도해 볼 가치가 있는지([retryable])를 함께 나른다.
 *
 * 화면이 원인별로 다르게 반응해야 하기 때문에 종류를 남긴다:
 * - [Kind.CLOSED] · [Kind.UNAUTHORIZED] 는 같은 요청을 다시 보내도 결과가 같다 → 자동 재시도 금지.
 * - [Kind.NETWORK] · [Kind.SERVER] 는 배포 서버의 콜드 스타트(첫 응답 수 초)로도 발생하므로
 *   짧은 콜 카운트다운 안에서 한 번은 자동으로 다시 시도할 가치가 있다.
 *
 * IllegalStateException 을 상속해, 메시지만 읽던 기존 화면 코드(`e.message`)와 그대로 호환된다.
 */
class CallException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {

    enum class Kind {
        /** 409 CALL_CLOSED — 콜 TTL 만료·타 기사 선점 */
        CLOSED,

        /** 401 · 403 — 기사 인증 만료 / 권한 없음 */
        UNAUTHORIZED,

        /** 타임아웃 · 연결 실패 등 IO */
        NETWORK,

        /** 그 밖의 서버 오류(4xx/5xx) */
        SERVER,
    }

    val retryable: Boolean get() = kind == Kind.NETWORK || kind == Kind.SERVER
}
