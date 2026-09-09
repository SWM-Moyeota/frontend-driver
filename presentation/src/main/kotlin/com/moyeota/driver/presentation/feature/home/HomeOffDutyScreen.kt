package com.moyeota.driver.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBadge
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.HomeSummary
import com.naver.maps.geometry.LatLng

// D06 · 홈 — 휴무 (오프라인). 피그마 2514:520
@Composable
internal fun HomeOffDutyScreen(
    summary: HomeSummary,
    /** 기사 현재 위치 — 측위 전이면 null (지도는 기본 카메라 폴백, 오버레이 숨김) */
    myLocation: LatLng?,
    startingDuty: Boolean,
    onStartDuty: () -> Unit,
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
                text = "${summary.driverName} 기사님",
                style = MoyeotaType.DisplayLg,
                color = MoyeotaColor.InkPrimary,
            )
            StatusBadge(kind = NoticeKind.ERROR, text = "휴무")
        }

        // 지도 — 현재 위치 중심 + 내 위치 오버레이 (수요 히트맵 상세는 미연결)
        HomeMyLocationMap(
            myLocation = myLocation,
            modifier = Modifier
                .fillMaxWidth()
                .height(236.dp)
                .clip(RoundedCornerShape(14.dp)),
        )

        // 오늘 운행 요약 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MoyeotaColor.SurfaceCard)
                .border(1.dp, MoyeotaColor.Hairline, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "오늘 운행",
                    style = MoyeotaType.HeadingLg,
                    color = MoyeotaColor.InkPrimary,
                )
                Text(
                    text = "${summary.todayTripCount}건 · ${formatWon(summary.todayEarnings)}",
                    style = MoyeotaType.NumberMd,
                    color = MoyeotaColor.TextMute,
                )
            }
            Text(
                text = "합승 콜을 받으면 1건당 보너스 1,500원이 붙어요",
                style = MoyeotaType.BodyMd,
                color = MoyeotaColor.TextMute,
            )
        }

        PrimaryCtaButton(
            text = "영업 시작하기",
            onClick = onStartDuty,
            loading = startingDuty,
            modifier = Modifier.height(68.dp),
        )

        summary.promotionBanner?.let { banner ->
            PromotionBanner(text = banner, onClick = onPromotionClick)
        }
    }
}
