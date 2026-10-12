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

/** 「재전송」 버튼 라벨 — 쿨다운 중에는 남은 초를 함께 보여준다 */
internal fun resendLabel(codeSent: Boolean, cooldown: Int): String = when {
    !codeSent -> "인증번호 받기"
    cooldown > 0 -> "재전송 (${cooldown}초)"
    else -> "재전송"
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
    Text(
        text = "$phoneDisplay 로 6자리 인증번호를 보냈어요",
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextBody,
    )
    // 오입력 표시는 박스 테두리(danger)만 — 실패 문구는 CTA 위 배너가 서버 메시지로 보여준다
    OtpCodeInput(
        code = code,
        onCodeChange = onCodeChange,
        error = codeInvalid,
        enabled = enabled && codeSent,
    )
    Text(
        text = when {
            sendingCode -> "인증번호를 보내고 있어요"
            !codeSent -> "「인증번호 받기」를 누르면 문자로 보내드려요"
            resendCooldown > 0 -> "문자가 안 오면 ${resendCooldown}초 뒤에 재전송할 수 있어요"
            else -> "문자가 안 왔으면 재전송을 눌러 주세요"
        },
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextMute,
    )
    SecondaryButton(
        text = resendLabel(codeSent = codeSent, cooldown = resendCooldown),
        onClick = onSendCode,
        enabled = enabled && !sendingCode && resendCooldown == 0,
    )
}
