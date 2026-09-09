package com.moyeota.driver.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.HomeSummary
import com.naver.maps.geometry.LatLng

// D07 · 홈 — 영업중 · 콜 대기. 피그마 2514:559
@Composable
internal fun HomeOnDutyScreen(
    summary: HomeSummary,
    /** 기사 현재 위치 — 측위 전이면 null (지도는 기본 카메라 폴백, 오버레이 숨김) */
    myLocation: LatLng?,
    /** 아직 수락/거절하지 않은 실제 인입 콜 수 (데모 고정값 아님 — 0건이면 0으로 표시된다) */
    pendingCallCount: Int,
    onCallListClick: () -> Unit,
    onEndDutyClick: () -> Unit,
    onPromotionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "영업중",
                style = MoyeotaType.DisplayLg,
                color = MoyeotaColor.InkPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(kind = NoticeKind.INFO, text = "콜 대기")
                // 토글 off → D08 영업 종료 확인
                DutyToggle(checked = true, onToggle = onEndDutyClick)
            }
        }

        // 지도 — 현재 위치 중심 + 내 위치 오버레이 (수요 히트맵 상세는 미연결)
        HomeMyLocationMap(
            myLocation = myLocation,
            modifier = Modifier
                .fillMaxWidth()
                .height(252.dp)
                .clip(RoundedCornerShape(14.dp)),
        )

        // 지금 들어온 콜 카드 — 탭하면 콜 리스트(D09)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MoyeotaColor.Primary600)
                .clickable(onClick = onCallListClick)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "지금 들어온 콜",
                    style = MoyeotaType.HeadingLg,
                    color = MoyeotaColor.TextOnDark,
                )
                Text(
                    text = "${pendingCallCount}건",
                    style = MoyeotaType.NumberMd,
                    color = MoyeotaColor.TextOnDark,
                )
            }
            Text(
                text = if (pendingCallCount > 0) {
                    "합승 콜은 수락 시 건당 보너스 1,500원까지 함께 정산돼요"
                } else {
                    "콜이 들어오면 수락 화면이 자동으로 열려요"
                },
                style = MoyeotaType.BodyMd,
                color = MoyeotaColor.TextOnDark,
            )
        }

        // 콜 리스트 보기 행 — D09
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MoyeotaColor.SurfaceCard)
                .clickable(onClick = onCallListClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "콜 리스트 보기",
                    style = MoyeotaType.HeadingMd,
                    color = MoyeotaColor.InkPrimary,
                )
                Text(
                    text = if (pendingCallCount > 0) "${pendingCallCount}건 대기 중" else "대기 중인 콜 없음",
                    style = MoyeotaType.BodyMd,
                    color = MoyeotaColor.TextBody,
                )
            }
            Text(
                text = "›",
                style = MoyeotaType.NumberMd,
                color = MoyeotaColor.TextBody,
            )
        }

        SecondaryButton(
            text = "영업 종료",
            onClick = onEndDutyClick,
            modifier = Modifier.height(68.dp),
        )

        summary.promotionBanner?.let { banner ->
            PromotionBanner(text = banner, onClick = onPromotionClick)
        }
    }
}
