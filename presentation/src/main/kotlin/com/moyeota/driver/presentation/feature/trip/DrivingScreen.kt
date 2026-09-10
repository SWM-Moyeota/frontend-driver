package com.moyeota.driver.presentation.feature.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.moyeota.core.designsystem.component.PrimaryCtaButton
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
import kotlinx.coroutines.launch

// D15 · 운행 중 — 네비 · 하차 안내. 진입: D13 「도착 · 운행 시작」.
// 승차·하차가 파티 단위라 승객별 하차 처리는 없다 — 하차지 안내 + 「운행 완료」(→ D16 요금 입력)만.
// 운행 플로우 공통: 뒤로가기 차단 + 화면 꺼짐 방지.

class DrivingViewModel(private val repository: DriverRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val trip: ActiveTrip) : UiState
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

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { DrivingViewModel(repository) }
        }
    }
}

@Composable
fun DrivingRoute(
    navController: NavHostController,
    repository: DriverRepository,
    locationSource: DriverLocationSource,
) {
    val viewModel: DrivingViewModel = viewModel(factory = DrivingViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    // 운행 중 지도에 내 위치 오버레이 — 홈과 같은 5초 폴링
    val myLocation by rememberDriverLocation(locationSource)

    BackHandler { /* 운행 플로우 — 뒤로가기 차단 */ }
    KeepScreenOn()

    when (val s = state) {
        is DrivingViewModel.UiState.Loading -> LoadingBox()
        is DrivingViewModel.UiState.Error -> ErrorBox(message = s.message, onRetry = viewModel::refresh)
        is DrivingViewModel.UiState.Success -> DrivingScreen(
            trip = s.trip,
            myLocation = myLocation,
            onComplete = {
                // 서버 complete 는 D16 요금 입력의 submitFinalFare 가 담당 — 여기서는 화면 이동만
                navController.navigate(Routes.TRIP_FARE) {
                    popUpTo(Routes.TRIP_DRIVING) { inclusive = true }
                }
            },
        )
    }
}

@Composable
private fun DrivingScreen(
    trip: ActiveTrip,
    myLocation: LatLng?,
    onComplete: () -> Unit,
) {
    // startRide 후 nextStopIndex 는 첫 하차 스톱 — 인덱스가 어긋나도 하차 스톱으로 폴백
    val nextStop = trip.stops.getOrNull(trip.nextStopIndex)?.takeIf { it.kind == StopKind.DROPOFF }
        ?: trip.stops.firstOrNull { it.kind == StopKind.DROPOFF }

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 다음 하차지 카드 — 문구는 한 줄 3어절 이내
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoyeotaColor.Primary600, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "다음 하차", style = MoyeotaType.BodyLg, color = MoyeotaColor.TextOnDark)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = nextStop?.place ?: "하차지 없음",
                        style = MoyeotaType.NumberLg,
                        color = MoyeotaColor.TextOnDark,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatKm(trip.remainingKm),
                        style = MoyeotaType.NumberXl,
                        color = MoyeotaColor.TextOnDark,
                    )
                }
                Text(
                    text = "도착 예정 ${trip.remainingMin}분",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextOnDark,
                )
            }

            // 내비 — 출발·하차 두 지점 조망 + 내 위치
            TripMap(
                pillText = "${formatKm(trip.remainingKm)} · ${trip.remainingMin}분 남음",
                departure = trip.departurePoint,
                destination = trip.destinationPoint,
                myLocation = myLocation,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        // 하단 CTA — 운행 완료 → D16 요금 입력 직행 (신고 기능은 승객 앱 전용이라 기사 화면에는 두지 않는다)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoyeotaColor.SurfaceCard)
                .padding(16.dp),
        ) {
            PrimaryCtaButton(
                text = "운행 완료",
                onClick = onComplete,
                modifier = Modifier.weight(1f).height(60.dp),
            )
        }
    }
}
