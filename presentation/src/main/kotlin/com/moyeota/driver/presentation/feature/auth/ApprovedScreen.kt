package com.moyeota.driver.presentation.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// D05b · 가입 완료 · 즉시 승인 (피그마 2532:1026)
// 진입: D04 「가입 완료하기」 — 자동 조회 전 항목 통과 케이스.
// 뒤로가기 차단 (가입 스택은 「영업 시작하기」에서 전부 제거).
@Composable
fun ApprovedScreen(
    onStartDuty: () -> Unit,
    onPromotion: () -> Unit,
) {
    BackHandler { /* 뒤로가기 차단 — 가입 플로우로 되돌아가지 않는다 */ }

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "가입 완료")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusBadge(kind = NoticeKind.SUCCESS, text = "승인 완료")
            Text(text = "바로 영업할 수 있어요", style = MoyeotaType.DisplayLg, color = MoyeotaColor.InkDeep)
            Text(
                text = "자동 조회로 확인이 끝나서 심사 대기가 없어요",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
            ChecklistRow(
                title = "면허 · 자격",
                subtitle = "자동 조회 확인",
                trailing = "완료",
                trailingColor = MoyeotaColor.Success600,
            )
            ChecklistRow(
                title = "차량 · 보험",
                subtitle = "자동차등록원부 조회",
                trailing = "완료",
                trailingColor = MoyeotaColor.Success600,
            )
            ChecklistRow(
                title = "합승 콜 받기",
                subtitle = "보너스 대상",
                trailing = "ON",
                trailingColor = MoyeotaColor.Primary500,
            )
            // 프로모션 배너 탭 → D22 합승 프로모션 안내
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onPromotion),
            ) {
                NoticeBanner(kind = NoticeKind.INFO, text = "🎁  합승 콜 1건당 보너스 1,500원 (8월 한정)")
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PrimaryCtaButton(text = "영업 시작하기", onClick = onStartDuty)
        }
    }
}
