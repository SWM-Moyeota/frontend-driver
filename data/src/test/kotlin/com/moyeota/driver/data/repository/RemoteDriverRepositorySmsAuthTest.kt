package com.moyeota.driver.data.repository

import com.moyeota.driver.data.remote.AuthApi
import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.remote.dto.SmsVerifyRequestDto
import com.moyeota.driver.data.remote.dto.SmsVerifyResponseDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 문자 인증(발송·확인) 실패 분기 보강 — 기사가 **무엇 때문에 막혔는지** 화면에 전달되는지 본다.
 *
 * 기존 `RemoteDriverRepositoryAuthSyncTest` 는 "서버 message 가 있을 때 그대로 올라오는가"(429 USER202)와
 * "토큰이 빈 200"을 못 박았다. 여기서는 그 둘이 덮지 않는 구간을 채운다:
 * - **body 가 없는 4xx/5xx** — 프록시·게이트웨이가 끼어들면 서버 문구가 아예 오지 않는다.
 *   그때도 "서버 오류(429)" 같은 숫자만 보여주면 기사는 1분 기다리면 되는 상황인지 알 수 없다.
 * - **확인(verify) 경로의 HttpException 분기** — 만료(USER204)·시도 초과(USER206)는 발송과 다른 호출이다.
 * - **취소(CancellationException)를 삼키지 않는가** — 화면을 벗어났을 때의 취소가 "실패"로 바뀌면
 *   사라진 화면에 에러 배너를 쏘고, 구조적 동시성이 깨진다.
 */
class RemoteDriverRepositorySmsAuthTest {

    // ── 발송 (POST /auth/sms/send) ──────────────────────────────────────────

    /** 쿨다운·일일 한도는 429 다. body 가 없으면 상태코드만으로 "잠시 후"를 안내해야 한다 */
    @Test
    fun `발송 429 에 본문이 없으면 과다 요청 폴백 문구를 쓴다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onSendSms = { throw edgeEmptyBody(429) }))

        val message = failureMessage { repository.requestPhoneVerification("01012345678") }

        assertEquals("요청이 너무 많아요 · 잠시 후 다시 시도해 주세요", message)
    }

    /** 서버가 일일 한도(USER203) 문구를 주면 클라이언트 폴백으로 덮지 않는다 */
    @Test
    fun `발송 일일 한도 429 USER203 은 서버 문구가 그대로 온다`() = runBlocking {
        val repository = repository(
            EdgeAuthApi(onSendSms = { throw edgeCoded(429, "USER203", "하루에 5번까지만 인증번호를 요청할 수 있습니다.") }),
        )

        assertEquals(
            "하루에 5번까지만 인증번호를 요청할 수 있습니다.",
            failureMessage { repository.requestPhoneVerification("01012345678") },
        )
    }

    /**
     * 서버는 문자 비용을 쓰기 전에 이미 가입된 번호를 409 USER104 로 먼저 거절한다.
     * 이 문구가 "로그인하세요"로 이어져야 기사가 중복 가입을 되풀이하지 않는다.
     */
    @Test
    fun `발송 409 USER104 는 이미 가입된 번호임을 알린다`() = runBlocking {
        val repository = repository(
            EdgeAuthApi(onSendSms = { throw edgeCoded(409, "USER104", "이미 가입된 전화번호입니다.") }),
        )

        assertEquals("이미 가입된 전화번호입니다.", failureMessage { repository.requestPhoneVerification("01012345678") })
    }

    /** 문자 게이트웨이 장애(502 USER201 · 503 USER208)에서 body 가 비면 "잠시 후"로 안내한다 */
    @Test
    fun `발송 5xx 에 본문이 없으면 인증 불가 폴백 문구를 쓴다`() = runBlocking {
        listOf(502, 503).forEach { status ->
            val repository = repository(EdgeAuthApi(onSendSms = { throw edgeEmptyBody(status) }))

            assertEquals(
                "$status",
                "지금은 인증을 진행할 수 없어요 · 잠시 후 다시 시도해 주세요",
                failureMessage { repository.requestPhoneVerification("01012345678") },
            )
        }
    }

    @Test
    fun `발송 네트워크 실패는 실패 사실과 원인을 함께 올린다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onSendSms = { throw SocketTimeoutException("timeout") }))

        assertEquals(
            "인증번호를 보내지 못했어요 · timeout",
            failureMessage { repository.requestPhoneVerification("01012345678") },
        )
    }

    /** 화면 이탈로 코루틴이 취소된 것을 "발송 실패"로 바꿔 보고하면 안 된다 */
    @Test
    fun `발송 중 취소는 실패로 바꾸지 않고 그대로 올린다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onSendSms = { throw CancellationException("화면 이탈") }))

        val error = runCatching { repository.requestPhoneVerification("01012345678") }.exceptionOrNull()

        assertTrue("취소는 취소로 전파돼야 한다: $error", error is CancellationException)
    }

    // ── 확인 (POST /auth/sms/verify) ────────────────────────────────────────

    /** 발송과 같은 정규화를 거쳐야 서버가 토큰에 묶는 전화번호 해시와 어긋나지 않는다 */
    @Test
    fun `인증번호 확인도 하이픈 표준형으로 정규화해 보낸다`() = runBlocking {
        var sent: SmsVerifyRequestDto? = null
        val repository = repository(
            EdgeAuthApi(onVerifySms = { sent = it; SmsVerifyResponseDto("token-xyz") }),
        )

        repository.verifyPhoneCode("010 1234 5678", "123456")

        assertEquals("010-1234-5678", sent?.phoneNumber)
        assertEquals("123456", sent?.code)
    }

    @Test
    fun `인증번호 만료 400 USER204 는 서버 문구가 그대로 온다`() = runBlocking {
        val repository = repository(
            EdgeAuthApi(onVerifySms = { throw edgeCoded(400, "USER204", "인증번호가 만료되었습니다.") }),
        )

        assertEquals("인증번호가 만료되었습니다.", failureMessage { repository.verifyPhoneCode("01012345678", "123456") })
    }

    @Test
    fun `입력 횟수 초과 429 USER206 은 서버 문구가 그대로 온다`() = runBlocking {
        val repository = repository(
            EdgeAuthApi(onVerifySms = { throw edgeCoded(429, "USER206", "인증번호 입력 횟수를 초과했습니다.") }),
        )

        assertEquals(
            "인증번호 입력 횟수를 초과했습니다.",
            failureMessage { repository.verifyPhoneCode("01012345678", "123456") },
        )
    }

    /** 코드 불일치(400 USER205)에서 body 가 비면 "서버 오류(400)"라도 실패 사실은 분명해야 한다 */
    @Test
    fun `확인 400 에 본문이 없으면 호출부 문구에 상태코드를 덧붙인다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onVerifySms = { throw edgeEmptyBody(400) }))

        assertEquals(
            "인증번호를 확인하지 못했어요 · 서버 오류(400)",
            failureMessage { repository.verifyPhoneCode("01012345678", "123456") },
        )
    }

    /**
     * 토큰 자리에 공백만 온 200 — 구서버·필드명 변경 시의 모양이다.
     * 그대로 가입에 실으면 400 USER207 로 떨어져 기사는 인증을 다 하고도 마지막 단계에서 막힌다.
     * (빈 문자열 케이스는 AuthSyncTest 가 덮는다 — 여기서는 `isNotBlank` 경계를 못 박는다)
     */
    @Test
    fun `공백뿐인 토큰 200 응답도 인증 실패로 처리한다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onVerifySms = { SmsVerifyResponseDto("   ") }))

        assertEquals(
            "인증이 완료되지 않았어요 · 인증번호를 다시 요청해 주세요",
            failureMessage { repository.verifyPhoneCode("01012345678", "123456") },
        )
    }

    @Test
    fun `확인 네트워크 실패는 실패 사실과 원인을 함께 올린다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onVerifySms = { throw SocketTimeoutException("timeout") }))

        assertEquals(
            "인증번호를 확인하지 못했어요 · timeout",
            failureMessage { repository.verifyPhoneCode("01012345678", "123456") },
        )
    }

    @Test
    fun `확인 중 취소는 실패로 바꾸지 않고 그대로 올린다`() = runBlocking {
        val repository = repository(EdgeAuthApi(onVerifySms = { throw CancellationException("화면 이탈") }))

        val error = runCatching { repository.verifyPhoneCode("01012345678", "123456") }.exceptionOrNull()

        assertTrue("취소는 취소로 전파돼야 한다: $error", error is CancellationException)
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private fun repository(authApi: AuthApi) = RemoteDriverRepository(
        authApi = authApi,
        driverApi = EdgeDriverApi(),
        dispatchApi = EdgeDispatchApi(),
        tokenStore = TokenStore(),
        fallback = DummyDriverRepository(),
    )

    /** 실패 시나리오 전용 — 성공하면 테스트가 틀린 것이므로 바로 터뜨린다 */
    private inline fun failureMessage(block: () -> Unit): String {
        val error = runCatching { block() }.exceptionOrNull()
            ?: error("실패해야 하는 시나리오인데 성공했다")
        return error.message.orEmpty()
    }
}
