package com.moyeota.driver.presentation.feature.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.moyeota.core.designsystem.component.AvatarCircle
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.model.ActiveTrip
import com.moyeota.driver.domain.model.StopKind
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.Routes
import com.moyeota.driver.presentation.core.rememberDriverLocation
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D13 · 픽업 이동 — 네비게이션. 진입: D11 「픽업 안내 시작」.
// 도착 시 「도착 · 운행 시작」 한 번으로 arrive(알림) + board(파티 단위)를 처리하고 D15 로 직행 (D14 제거).
// 운행 플로우 공통: 뒤로가기 차단 + 화면 꺼짐 방지.

class PickupViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val trip: ActiveTrip,
            val starting: Boolean = false,     // startRide 진행 중 — CTA 로딩
            val started: Boolean = false,      // startRide 성공 — D15 이동 트리거
            val startError: String? = null,    // startRide 실패 — 배너 + 재시도
        ) : UiState

        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val trip = repository.getActiveTrip()
                _uiState.value = if (trip == null) {
                    UiState.Error("진행 중인 운행이 없어요")
                } else {
                    UiState.Success(trip)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("운행 정보를 불러오지 못했어요")
            }
        }
    }

    /**
     * 도착 · 운행 시작 — arrive 는 알림 전용(실패 무시), board 는 파티 단위 1회(실패 시 배너 + 재시도).
     * 성공하면 started 로 전환해 Route 가 D15 로 이동한다.
     */
    fun startRide() {
        val current = _uiState.value as? UiState.Success ?: return
        if (current.starting || current.started) return
        viewModelScope.launch {
            _uiState.update { (it as UiState.Success).copy(starting = true, startError = null) }
            // 도착 통보(승객 "기사 도착" 푸시)는 fire-and-forget — 실패해도 운행 시작 진행
            runCatching { repository.notifyPickupArrival(current.trip.id) }
            try {
                repository.startRide(current.trip.id)
                _uiState.update { (it as UiState.Success).copy(starting = false, started = true) }
            } catch (e: Exception) {
                _uiState.update {
                    (it as UiState.Success).copy(
                        starting = false,
                        startError = "운행을 시작하지 못했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { PickupViewModel(repository) }
        }
    }
}

@Composable
fun PickupRoute(
    navController: NavHostController,
    repository: DriverRepository,
    locationSource: DriverLocationSource,
) {
    val viewModel: PickupViewModel = viewModel(factory = PickupViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    // 픽업지로 가는 동안 지도에 내 위치 오버레이 — 홈과 같은 5초 폴링
    val myLocation by rememberDriverLocation(locationSource)

    BackHandler { /* 운행 플로우 — 뒤로가기 차단 */ }
    KeepScreenOn()

    // 운행 시작(board) 성공 → D15 운행 중으로 직행 (D14 탑승 화면 제거)
    val started = (state as? PickupViewModel.UiState.Success)?.started == true
    LaunchedEffect(started) {
        if (started) {
            navController.navigate(Routes.TRIP_DRIVING) {
                popUpTo(Routes.TRIP_PICKUP) { inclusive = true }
            }
        }
    }

    when (val s = state) {
        is PickupViewModel.UiState.Loading -> LoadingBox()
        is PickupViewModel.UiState.Error -> ErrorBox(message = s.message, onRetry = viewModel::refresh)
        is PickupViewModel.UiState.Success -> PickupScreen(
            trip = s.trip,
            myLocation = myLocation,
            starting = s.starting,
            startError = s.startError,
            onStartRide = viewModel::startRide,
        )
    }
}

@Composable
private fun PickupScreen(
    trip: ActiveTrip,
    myLocation: LatLng?,
    starting: Boolean,
    startError: String?,
    onStartRide: () -> Unit,
) {
    val nextStop = trip.stops.getOrNull(trip.nextStopIndex)
        ?: trip.stops.first { it.kind == StopKind.PICKUP }
    val passengers = trip.passengers

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 다음 목적지(픽업지) 카드 — 문구는 한 줄 3어절 이내
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.Primary600, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "픽업지 이동 중", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextOnDark)
                Text(text = nextStop.place, style = MoyeotaType.NumberLg, color = MoyeotaColor.TextOnDark)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatKm(trip.remainingKm),
                        style = MoyeotaType.NumberXl,
                        color = MoyeotaColor.TextOnDark,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${trip.remainingMin}분 남음",
                        style = MoyeotaType.NumberMd,
                        color = MoyeotaColor.TextOnDark,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }

            // 내비 — 픽업지로 가는 중이라 픽업지 + 내 위치만 (하차 마커는 D15 에서)
            TripMap(
                pillText = "${formatKm(trip.remainingKm)} · ${trip.remainingMin}분 남음",
                departure = trip.departurePoint,
                destination = null,
                myLocation = myLocation,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // 승객 요약 카드 — 파티 단위 탑승이라 인원 요약만 (승객별 항목 없음). 표시 전용
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarCircle(size = 40.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val nameLabel = when {
                            passengers.isEmpty() -> "승객 없음"
                            passengers.size == 1 -> passengers.first().maskedName
                            else -> "${passengers.first().maskedName} 외 ${passengers.size - 1}명"
                        }
                        Text(text = nameLabel, style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
                        Text(
                            text = "승객 ${passengers.size}명 대기 중",
                            style = MoyeotaType.BodySm,
                            color = MoyeotaColor.TextMute,
                            modifier = Modifier
                                .background(MoyeotaColor.SurfaceSoft, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = "${nextStop.place} · ${trip.remainingMin}분 후 도착",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextBody,
                    )
                }
            }

            // 운행 시작 실패 — CTA 위 에러 배너 (버튼 재탭으로 재시도)
            if (startError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = startError)
            }
        }

        // 하단 CTA — 좌: 승객 연락(안심번호·메시지 미연결, 비활성), 우: 도착 · 운행 시작 (arrive + board → D15)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton(
                text = "승객 연락",
                onClick = {},
                enabled = false, // 안심번호 통화 · 메시지 시트 미연결
                modifier = Modifier.weight(1f).height(60.dp),
            )
            PrimaryCtaButton(
                text = "도착 · 운행 시작",
                onClick = onStartRide,
                loading = starting,
                modifier = Modifier.weight(2f).height(60.dp),
            )
        }
    }
}
