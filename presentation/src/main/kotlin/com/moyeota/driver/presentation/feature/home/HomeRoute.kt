package com.moyeota.driver.presentation.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.MoyeotaTab
import com.moyeota.driver.domain.location.DriverLocationSource
import com.moyeota.driver.domain.model.DriverLocationUnavailableException
import com.moyeota.driver.domain.model.DutyStatus
import com.moyeota.driver.domain.model.HomeSummary
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.ErrorBox
import com.moyeota.driver.presentation.core.LoadingBox
import com.moyeota.driver.presentation.core.TabStateScaffold
import com.moyeota.driver.presentation.core.rememberDriverLocation
import com.moyeota.driver.presentation.core.userMessageOr
import kotlinx.coroutines.CancellationException
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
            /**
             * 영업 시작 실패 문구 — 홈 화면은 그대로 두고 CTA 위 배너로만 알린다.
             * 실패로 화면 전체를 Error 로 덮으면 그 자리에서 다시 시도할 수 없다.
             */
            val startDutyError: String? = null,
            /**
             * 서버가 택시(배차) 기능을 켜 두었는가. false 면 기사 API 가 전부 404 라
             * 영업을 시작할 수도, 콜을 받을 수도 없다 — 홈 요약이 더미 폴백으로 "멀쩡해 보이는"
             * 것을 막기 위해 경고 배너를 띄운다(QA D4·D-2).
             * **조회 실패는 true(켜짐)** — 네트워크가 한 번 흔들렸다고 정상 기사에게 거짓 경고를 띄우지 않는다.
             */
            val taxiServiceEnabled: Boolean = true,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.userMessageOr(HOME_LOAD_FAILED))
            }
        }
    }

    /**
     * D06 「영업 시작하기」 — 성공 시 명시적 재조회로 D07 상태 반영.
     *
     * 호출부(HomeRoute)가 위치 권한을 먼저 확인한다. 권한이 없으면 이 함수는 호출되지 않는다.
     * 권한이 있어도 첫 측위 전이면 repository 가 [DriverLocationUnavailableException] 을 던진다 —
     * 그때는 영업 상태로 만들지 않고(더미 강등 금지) 배너로만 알려 잠시 후 재시도하게 한다.
     */
    fun startDuty() {
        val current = _uiState.value
        if (current !is UiState.Success || current.startingDuty) return
        _uiState.value = current.copy(startingDuty = true, startDutyError = null)
        viewModelScope.launch {
            try {
                repository.setDutyStatus(online = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = current.copy(
                    startingDuty = false,
                    startDutyError = startDutyErrorMessage(e),
                )
                return@launch
            }
            // 영업 시작 자체는 성공 — 이후 재조회 실패는 홈 로딩 실패로 다룬다
            _uiState.value = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.userMessageOr(HOME_LOAD_FAILED))
            }
        }
    }

    /** 권한 요청·재시도 직전에 이전 실패 문구를 지운다 */
    fun clearStartDutyError() {
        _uiState.update { state ->
            if (state is UiState.Success && state.startDutyError != null) {
                state.copy(startDutyError = null)
            } else {
                state
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

    /**
     * 서버 기능 비활성 여부 재확인 — 화면 재개 시점에 쓴다.
     * 로딩 화면을 거치지 않고 배너만 제자리 갱신한다(서버가 복구되면 배너가 사라진다).
     */
    fun refreshServiceAvailability() {
        if (_uiState.value !is UiState.Success) return
        viewModelScope.launch {
            val enabled = taxiServiceEnabled()
            _uiState.update { state ->
                if (state is UiState.Success) state.copy(taxiServiceEnabled = enabled) else state
            }
        }
    }

    /**
     * 영업 시작 실패 문구 — 리포지토리 메시지를 우선 노출한다(QA D-4).
     * "다시 시도해 주세요"는 서버에 기사 기능이 없는 상황에서 영원히 통하지 않는 안내였다.
     */
    private fun startDutyErrorMessage(e: Exception): String = when (e) {
        // 권한은 있는데 아직 fix 가 없는 구간 — 실내·지하에서 흔하다. 원인을 짚어 준다.
        is DriverLocationUnavailableException -> "위치를 확인하는 중이에요 · 실외에서 잠시 후 다시 시도해 주세요"
        else -> e.userMessageOr("영업을 시작하지 못했어요 · 잠시 후 다시 시도해 주세요")
    }

    /** 리포지토리가 이미 낙관 폴백(실패 → true)을 하지만, 취소 외의 예외는 여기서도 켜짐으로 본다 */
    private suspend fun taxiServiceEnabled(): Boolean =
        try {
            repository.isTaxiServiceEnabled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
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
        return UiState.Success(
            summary = summary,
            pendingCallCount = pendingCallCount,
            // 홈 진입 1회 확인 — 재개 시점은 refreshServiceAvailability() 가 맡는다
            taxiServiceEnabled = taxiServiceEnabled(),
        )
    }

    companion object {
        private const val HOME_LOAD_FAILED = "홈 정보를 불러오지 못했어요. 다시 시도해 주세요."

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
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 홈이 떠 있는 동안 단말 측위 캐시를 5초 폴링 — 운행 화면(D13·D15)과 공용 헬퍼
    val myLocation by rememberDriverLocation(locationSource)

    // 위치 권한은 영업의 전제 조건이다. 설정에서 켜고 돌아오거나 영업 중 회수되는 경우가 있어
    // 화면 재개 시점마다 다시 읽는다 (권한 변경은 브로드캐스트로 오지 않는다).
    var locationGranted by remember { mutableStateOf(context.hasLocationPermission()) }
    // 시스템 권한 요청을 띄웠는데 거부당한 뒤에만 설정 안내를 노출한다 (첫 진입부터 경고하지 않는다).
    var permissionRejected by rememberSaveable { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // 정확/대략 중 하나만 허용돼도 측위는 된다
        val granted = result.values.any { it }
        locationGranted = granted
        permissionRejected = !granted
        // 허용 직후 곧바로 이어서 영업 시작 — CTA 를 두 번 누르게 하지 않는다.
        // (영구 거부 상태면 다이얼로그 없이 즉시 거부로 돌아와 배너 경로로 빠진다.)
        if (granted) viewModel.startDuty()
    }

    LifecycleResumeEffect(Unit) {
        // 콜 상세에서 거절·만료로 돌아오면 인입 콜 수가 달라져 있다 — 복귀 시마다 다시 읽는다
        viewModel.refreshCallCount()
        // 서버가 기사 기능을 되살렸는지도 재개 시점에 한 번 확인한다
        // (첫 진입 때는 아직 Loading 이라 no-op — 그 몫은 load() 가 이미 했다)
        viewModel.refreshServiceAvailability()
        // 설정에서 허용하고 돌아온 경우 / 영업 중 권한이 회수된 경우를 재개 시점에 반영
        val granted = context.hasLocationPermission()
        locationGranted = granted
        if (granted) permissionRejected = false
        onPauseOrDispose { }
    }

    // D06 「영업 시작하기」 게이트 — 권한이 없으면 어떤 경우에도 startDuty() 를 호출하지 않는다.
    val onStartDuty: () -> Unit = {
        val granted = context.hasLocationPermission()
        locationGranted = granted
        if (granted) {
            permissionRejected = false
            viewModel.startDuty()
        } else {
            viewModel.clearStartDutyError()
            locationPermissionLauncher.launch(LocationPermissions)
        }
    }
    val onOpenAppSettings: () -> Unit = { context.openAppSettings() }

    TabStateScaffold(selectedTab = MoyeotaTab.HOME, onTabSelect = onTabSelect) {
        when (val state = uiState) {
            is HomeViewModel.UiState.Loading -> LoadingBox()
            is HomeViewModel.UiState.Error -> ErrorBox(message = state.message, onRetry = viewModel::refresh)
            is HomeViewModel.UiState.Success -> when (state.summary.dutyStatus) {
                DutyStatus.OFFLINE -> HomeOffDutyScreen(
                    summary = state.summary,
                    myLocation = myLocation,
                    startingDuty = state.startingDuty,
                    startDutyError = state.startDutyError,
                    locationPermissionRejected = permissionRejected && !locationGranted,
                    taxiServiceDisabled = !state.taxiServiceEnabled,
                    onStartDuty = onStartDuty,
                    onOpenAppSettings = onOpenAppSettings,
                    onReloadHome = viewModel::refresh,
                )

                DutyStatus.ONLINE -> HomeOnDutyScreen(
                    summary = state.summary,
                    myLocation = myLocation,
                    pendingCallCount = state.pendingCallCount,
                    locationPermissionMissing = !locationGranted,
                    taxiServiceDisabled = !state.taxiServiceEnabled,
                    onCallListClick = onNavigateCallList,
                    onEndDutyClick = onNavigateOffDutyConfirm,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
        }
    }
}
