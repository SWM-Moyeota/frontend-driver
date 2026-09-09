package com.moyeota.driver.presentation.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.model.DutyStatus
import com.moyeota.driver.domain.model.HomeSummary
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import com.moyeota.driver.presentation.core.rememberDriverLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Routes.HOME 하나가 D06(휴무)·D07(영업중)을 겸한다 — dutyStatus로 상태 분기.

class HomeViewModel(private val repository: DriverRepository) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState

        data class Success(
            val summary: HomeSummary,
            val pendingCallCount: Int,       // 영업중일 때 아직 수락/거절하지 않은 실제 인입 콜 수
            val startingDuty: Boolean = false, // 영업 시작 제출 중 (중복 제출 차단)
        ) : UiState

        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                _uiState.value = load()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("홈 정보를 불러오지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    // D06 「영업 시작하기」 — 성공 시 명시적 재조회로 D07 상태 반영
    fun startDuty() {
        val current = _uiState.value
        if (current !is UiState.Success || current.startingDuty) return
        _uiState.update { (it as UiState.Success).copy(startingDuty = true) }
        viewModelScope.launch {
            try {
                repository.setDutyStatus(online = true)
                _uiState.value = load()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("영업을 시작하지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    /** 콜 화면에서 돌아왔을 때 인입 콜 수만 다시 읽는다 (로딩 화면 깜빡임 없이 제자리 갱신) */
    fun refreshCallCount() {
        val current = _uiState.value
        if (current !is UiState.Success || current.summary.dutyStatus != DutyStatus.ONLINE) return
        viewModelScope.launch {
            val count = runCatching { repository.getCalls().size }.getOrNull() ?: return@launch
            _uiState.update { state ->
                if (state is UiState.Success) state.copy(pendingCallCount = count) else state
            }
        }
    }

    private suspend fun load(): UiState.Success {
        val summary = repository.getHomeSummary()
        // 데모 고정값이 아니라 실제 인입된(아직 처리 안 한) 콜 수 — 없으면 0건으로 그대로 보여준다
        val pendingCallCount = if (summary.dutyStatus == DutyStatus.ONLINE) {
            try {
                repository.getCalls().size
            } catch (e: Exception) {
                0 // 콜 수는 보조 정보 — 실패해도 홈은 띄운다
            }
        } else {
            0
        }
        return UiState.Success(summary = summary, pendingCallCount = pendingCallCount)
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}

@Composable
fun HomeRoute(
    repository: DriverRepository,
    locationSource: DriverLocationSource,
    onTabSelect: (MoyeotaTab) -> Unit,
    onNavigateOffDutyConfirm: () -> Unit,
    onNavigateCallList: () -> Unit,
    onNavigatePromotion: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()

    // 홈이 떠 있는 동안 단말 측위 캐시를 5초 폴링 — 운행 화면(D13·D15)과 공용 헬퍼
    val myLocation by rememberDriverLocation(locationSource)

    // 콜 상세에서 거절·만료로 돌아오면 인입 콜 수가 달라져 있다 — 복귀 시마다 다시 읽는다
    LifecycleResumeEffect(Unit) {
        viewModel.refreshCallCount()
        onPauseOrDispose { }
    }

    TabStateScaffold(selectedTab = MoyeotaTab.HOME, onTabSelect = onTabSelect) {
        when (val state = uiState) {
            is HomeViewModel.UiState.Loading -> LoadingBox()
            is HomeViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is HomeViewModel.UiState.Success -> when (state.summary.dutyStatus) {
                DutyStatus.OFFLINE -> HomeOffDutyScreen(
                    summary = state.summary,
                    myLocation = myLocation,
                    startingDuty = state.startingDuty,
                    onStartDuty = viewModel::startDuty,
                    onPromotionClick = onNavigatePromotion,
                )

                DutyStatus.ONLINE -> HomeOnDutyScreen(
                    summary = state.summary,
                    myLocation = myLocation,
                    pendingCallCount = state.pendingCallCount,
                    onCallListClick = onNavigateCallList,
                    onEndDutyClick = onNavigateOffDutyConfirm,
                    onPromotionClick = onNavigatePromotion,
                )
            }
        }
    }
}
