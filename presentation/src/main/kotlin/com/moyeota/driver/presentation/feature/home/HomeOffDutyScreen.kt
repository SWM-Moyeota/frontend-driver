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
    /** 영업 시작 실패 문구 — null 이면 숨김. 화면을 덮지 않고 CTA 위 배너로만 알린다 */
    startDutyError: String?,
    /** 시스템 권한 요청을 거부당한 상태 — 설정 이동이 유일한 출구다(영구 거부 포함) */
    locationPermissionRejected: Boolean,
    /**
     * 서버가 택시(배차) 기능을 끈 상태 — 기사 API 가 전부 404 라 영업 시작이 성공할 수 없다.
     * 눌러도 실패할 CTA 를 열어 두면 기사가 비밀번호·네트워크를 의심하며 반복하게 되므로
     * 배너로 원인을 알리고 CTA 를 닫는다. 「새로 고침」으로 서버 복구를 바로 반영할 수 있다.
     */
    taxiServiceDisabled: Boolean,
    onStartDuty: () -> Unit,
    onOpenAppSettings: () -> Unit,
    /** 서버 상태 재확인 — 홈 전체 재조회 (배너·CTA 가 함께 풀린다) */
    onReloadHome: () -> Unit,
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

        // 공통 규칙: 화면 단위 오류는 CTA 위 배너.
        // 서버 기능 비활성이 가장 앞이다 — 기사가 할 수 있는 일이 없는 상태이고, 그 밑의 권한·실패
        // 문구는 모두 "영업을 시작해 보려는" 전제에서 나오는데 그 전제 자체가 성립하지 않는다.
        // 그다음이 권한 거부다 — 권한이 없으면 영업 시작 자체를 시도하지 않으므로 실패 문구는 뜨지 않는다.
        if (taxiServiceDisabled) {
            HomeNotice(
                kind = NoticeKind.ERROR,
                text = "서버에서 기사 기능이 꺼져 있어요 · 지금은 콜을 받을 수 없어요",
                actionText = "서버 상태 새로 고침",
                onAction = onReloadHome,
            )
        } else if (locationPermissionRejected) {
            HomeNotice(
                kind = NoticeKind.ERROR,
                text = "위치 권한을 허용해야 영업을 시작할 수 있어요",
                actionText = "설정에서 허용",
                onAction = onOpenAppSettings,
            )
        } else if (startDutyError != null) {
            HomeNotice(kind = NoticeKind.ERROR, text = startDutyError)
        }

        PrimaryCtaButton(
            text = "영업 시작하기",
            onClick = onStartDuty,
            enabled = !taxiServiceDisabled,
            loading = startingDuty,
            modifier = Modifier.height(68.dp),
        )
    }
}
