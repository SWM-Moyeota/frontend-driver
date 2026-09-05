package com.moyeota.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// moyeota-figma-tokens.json 의 core.font 값 그대로.
// 폰트 패밀리는 Pretendard 지정이지만 초안에서는 시스템 기본 sans 사용.
object MoyeotaType {
    val DisplayXl = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 1.25.em, letterSpacing = (-0.4).sp)
    val DisplayLg = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 1.25.em, letterSpacing = (-0.3).sp)
    val DisplayMd = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 1.25.em, letterSpacing = (-0.2).sp)
    val HeadingXl = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.35.em, letterSpacing = (-0.1).sp)
    val HeadingLg = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.35.em, letterSpacing = (-0.1).sp)
    val HeadingMd = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.35.em, letterSpacing = (-0.1).sp)
    // 기사 앱 공통 규칙: 본문 최소 17, 금액·남은 거리 같은 핵심 숫자는 24~34
    val BodyLg = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 1.5.em)
    val NumberXl = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 1.2.em, letterSpacing = (-0.4).sp)
    val NumberLg = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 1.2.em, letterSpacing = (-0.3).sp)
    val NumberMd = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 1.2.em, letterSpacing = (-0.2).sp)
    val BodyMd = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 1.5.em)
    val BodySm = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 1.5.em)
    val CaptionMd = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 1.35.em, letterSpacing = 0.2.sp)
    val CaptionSm = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 1.35.em, letterSpacing = 0.2.sp)
    val ButtonLg = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val ButtonMd = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val ButtonSm = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}
