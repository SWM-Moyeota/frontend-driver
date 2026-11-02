package com.moyeota.driver.presentation.feature.auth

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// 문자 인증 스텝의 입력 UI — D02(PhoneVerifyScreen)와 가입 4단계 ④가 공유한다.
//
// 가입에서 인증을 **마지막 단계**에 두는 이유: 서버 verificationToken 이 10분 만료라서
// ②프로필·③택시 정보를 입력하는 동안 만료되면 제출이 USER1xx 로 터진다.
// 인증 직후 곧바로 signUp 을 호출해 토큰 수명 안에서 제출을 끝낸다.

/** 재전송 쿨다운 (초) — 서버 문자 발송 레이트리밋과 맞춘 값 */
internal const val RESEND_COOLDOWN_SECONDS = 60

/**
 * 「재전송」 버튼 라벨 — 쿨다운 중에는 남은 초를 함께 보여준다.
 *
 * [verified] 는 인증이 끝나 토큰을 들고 있는 상태(제출만 실패한 재시도 경로)다. 이때 재발송은
 * 보조 출구이므로 무엇을 다시 받는 것인지 라벨에 적어 「다시 제출」과 헷갈리지 않게 한다.
 */
internal fun resendLabel(codeSent: Boolean, cooldown: Int, verified: Boolean = false): String {
    val base = when {
        verified -> "인증번호 다시 받기"
        !codeSent -> "인증번호 받기"
        else -> "재전송"
    }
    // 만료로 codeSent 가 내려가도 쿨다운은 살아 있다 — 언제 다시 받을 수 있는지 라벨에 남긴다
    return if (cooldown > 0) "$base (${cooldown}초)" else base
}

/**
 * 가입 ④ · 문자 인증 스텝 본문.
 *
 * 번호는 ①에서 입력한 값을 그대로 쓴다(이 스텝에서는 수정 불가 — 고치려면 back 으로 ①까지).
 * 진입 시 자동 1회 발송이므로 기본 라벨은 「재전송」이고, 발송 전(= 자동 발송 실패 후)에는
 * 「인증번호 받기」로 바뀐다.
 */
@Composable
internal fun PhoneCodeStepFields(
    phoneDisplay: String,
    code: String,
    onCodeChange: (String) -> Unit,
    codeSent: Boolean,
    sendingCode: Boolean,
    resendCooldown: Int,
    codeInvalid: Boolean,
    /**
     * 인증이 끝나 서버 토큰을 들고 있는 상태 — 제출만 실패해 재시도를 기다린다.
     * 인증번호를 다시 넣을 필요가 없으므로 OTP 입력을 닫고 문구도 제출 재시도로 돌린다.
     */
    verified: Boolean,
    /** 토큰이 만료돼 폐기된 상태 — 재발송만이 출구다(쿨다운은 그대로 존중한다) */
    verificationExpired: Boolean,
    onSendCode: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "문자로 받은 번호를 넣어주세요",
        style = MoyeotaType.DisplayMd,
        color = MoyeotaColor.InkDeep,
        modifier = modifier,
    )
    // 부제는 발송 상태를 따라간다 — 고정 문구로 두면 발송 실패 화면에서
    // "보냈어요"와 "보내지 못했어요"가 동시에 보인다(QA D-3).
    Text(
        text = when {
            verified -> "인증은 끝났어요 · 가입 제출만 다시 하면 돼요"
            sendingCode -> "$phoneDisplay 로 인증번호를 보내고 있어요"
            verificationExpired -> "인증이 만료됐어요 · 인증번호를 다시 받아 주세요"
            codeSent -> "$phoneDisplay 로 6자리 인증번호를 보냈어요"
            else -> "$phoneDisplay 로 인증번호를 보낼게요"
        },
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextBody,
    )
    // 오입력 표시는 박스 테두리(danger)만 — 실패 문구는 CTA 위 배너가 서버 메시지로 보여준다
    OtpCodeInput(
        code = code,
        onCodeChange = onCodeChange,
        error = codeInvalid,
        // 인증이 끝났으면 더 넣을 것이 없다 — 토큰을 들고 제출만 재시도한다
        enabled = enabled && codeSent && !verified,
    )
    Text(
        text = when {
            sendingCode -> "인증번호를 보내고 있어요"
            verified -> "인증번호는 다시 넣지 않아도 돼요"
            verificationExpired && resendCooldown > 0 ->
                "${resendCooldown}초 뒤에 인증번호를 다시 받을 수 있어요"
            verificationExpired -> "아래 버튼으로 인증번호를 다시 받아 주세요"
            !codeSent && resendCooldown > 0 -> "${resendCooldown}초 뒤에 다시 보낼 수 있어요"
            !codeSent -> "「인증번호 받기」를 누르면 문자로 보내드려요"
            resendCooldown > 0 -> "문자가 안 오면 ${resendCooldown}초 뒤에 재전송할 수 있어요"
            else -> "문자가 안 왔으면 재전송을 눌러 주세요"
        },
        style = MoyeotaType.BodyLg,
        color = if (verificationExpired) MoyeotaColor.Danger500 else MoyeotaColor.TextMute,
    )
    SecondaryButton(
        text = resendLabel(codeSent = codeSent, cooldown = resendCooldown, verified = verified),
        onClick = onSendCode,
        // 쿨다운은 어떤 경로에서도 건너뛰지 않는다 — 풀면 서버 60초 제한에 걸려 429 가 나고
        // 일일 5통 한도만 깎인다(QA 32a 상위 1번).
        enabled = enabled && !sendingCode && resendCooldown == 0,
    )
}

/**
 * 가입 제출 실패가 **인증 토큰 만료**를 뜻하는지 — 메시지로만 판별한다.
 *
 * 서버는 만료·미인증을 400 USER207(SMS_NOT_VERIFIED)로 알리지만 리포지토리가 화면에 올려주는
 * 것은 가공된 한국어 문구뿐이라(`회원가입 실패 — 계정 생성: …`) presentation 에서는 코드를 볼 수 없다.
 * 그래서 **만료로 단정할 수 있는 문구만** true 다 — 애매하면 false 로 두고 토큰을 지킨다.
 *
 * 비대칭이 그쪽을 가리킨다: 토큰을 쥔 상태에서도 「인증번호 다시 받기」는 (쿨다운만 지키면)
 * 항상 열려 있으므로 **놓친 만료**의 비용은 "「다시 제출」이 한 번 더 같은 이유로 실패"에 그친다.
 * 반대로 **잘못 만료로 본** 경우는 쓸 수 있는 토큰을 버리고 쿨다운이 끝날 때까지 재제출조차
 * 막아 버린다. 그래서 과탐지를 피한다.
 */
internal fun indicatesVerificationExpired(message: String): Boolean =
    EXPIRY_HINTS.any { message.contains(it) }

private val EXPIRY_HINTS = listOf(
    "USER207",
    "USER204",
    "인증 만료",
    "인증이 만료",
    "인증번호가 만료",
    "인증 정보가 만료",
    "인증이 완료되지 않",
    "유효하지 않은 인증",
    "다시 인증",
)
