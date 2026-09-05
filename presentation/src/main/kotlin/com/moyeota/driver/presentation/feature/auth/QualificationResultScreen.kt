package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// D03b · 자동 조회 결과 (피그마 2532:988)
// 진입: D03 「자동 조회하기」 전 항목 통과. 「다음 · 차량 등록」 → D04.
// 실패 · 불일치 항목이 하나라도 있으면 이 화면 대신 D05 로 분기되므로 여기서는 확인 항목만 보여준다.
@Composable
fun QualificationResultScreen(onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "자동 조회 결과")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NoticeBanner(kind = NoticeKind.SUCCESS, text = "✓  면허 · 자격이 모두 확인됐어요")
            Text(text = "30초 만에 확인 끝", style = MoyeotaType.DisplayLg, color = MoyeotaColor.InkDeep)
            ChecklistRow(
                title = "운전면허 진위",
                subtitle = "경찰청 조회 · 이름 일치",
                trailing = "확인",
            )
            ChecklistRow(
                title = "면허 상태",
                subtitle = "정지 · 취소 이력 없음",
                trailing = "확인",
            )
            ChecklistRow(
                title = "택시운전자격",
                subtitle = "한국교통안전공단 조회",
                trailing = "확인",
            )
            Text(
                text = "조회 결과는 30일 보관 후 자동 삭제돼요",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextMute,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PrimaryCtaButton(text = "다음 · 차량 등록", onClick = onNext)
        }
    }
}
