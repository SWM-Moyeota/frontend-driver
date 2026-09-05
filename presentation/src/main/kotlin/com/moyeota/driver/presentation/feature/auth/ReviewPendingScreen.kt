package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType

// D05 · 예외 심사 대기 (피그마 2513:541)
// 진입: D01 로그인(심사 중 계정) · D03/D04 자동 조회 실패 · 불일치 케이스.
// 서류 제출 · 고객센터는 미연결 — Toast 대체 금지, 비활성 표기로 남긴다.
@Composable
fun ReviewPendingScreen() {
    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "가입 심사")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusBadge(kind = NoticeKind.WAITING, text = "심사 중")
            Text(text = "추가 확인이 필요해요", style = MoyeotaType.DisplayLg, color = MoyeotaColor.InkDeep)
            Text(
                text = "자동 조회로 확인되지 않은 항목만 담당자가 확인해요. 보통 1 영업일 안에 끝나요.",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
            ChecklistRow(
                title = "운전면허 진위",
                subtitle = "경찰청 조회 완료",
                trailing = "자동 확인",
                trailingColor = MoyeotaColor.Success600,
            )
            ChecklistRow(
                title = "택시운전자격",
                subtitle = "조회 불일치 · 담당자 확인",
                trailing = "확인 중",
                trailingColor = MoyeotaColor.Waiting600,
            )
            ChecklistRow(
                title = "범죄경력 회보서",
                subtitle = "제출 필요 (첫 운행 전)",
                trailing = "대기",
                trailingColor = MoyeotaColor.TextMute,
            )
            NoticeBanner(kind = NoticeKind.INFO, text = "확인이 끝나면 알림으로 알려드려요")
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    text = "서류 제출하기",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    enabled = false, // 미연결 — 비활성 표기
                )
                PrimaryCtaButton(
                    text = "고객센터",
                    onClick = {},
                    modifier = Modifier.weight(1.5f),
                    enabled = false, // 미연결 — 비활성 표기
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "서류 제출 · 고객센터 연결은 준비 중이에요",
                style = MoyeotaType.BodyMd,
                color = MoyeotaColor.TextAsh,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
