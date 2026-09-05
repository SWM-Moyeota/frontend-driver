package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.MoyeotaTextField
import com.moyeota.core.designsystem.component.MoyeotaTopBar
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D04 · 차량 · 소속 등록 (피그마 2513:496)
// 진입: D03b 「다음 · 차량 등록」. 「가입 완료하기」(registerVehicle) → D05b 즉시 승인.
// 합승 콜 받기 토글 기본 on + 보너스 1,500원 고지 (프로모션 첫 고지 지점).

class VehicleViewModel(private val repository: DriverRepository) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val screenError: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun register(
        plateNumber: String,
        vehicleModel: String,
        companyName: String,
        acceptsPool: Boolean,
        onRegistered: () -> Unit,
    ) {
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, screenError = null) }
            try {
                repository.registerVehicle(plateNumber, vehicleModel, companyName, acceptsPool)
                _uiState.value = UiState()
                onRegistered()
            } catch (e: Exception) {
                // 네트워크 오류는 화면 유지 + 재시도 (입력값 보존)
                _uiState.value = UiState(screenError = "등록에 실패했어요 · 다시 시도해 주세요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { VehicleViewModel(repository) }
        }
    }
}

@Composable
fun VehicleRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    val viewModel: VehicleViewModel = viewModel(factory = VehicleViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    VehicleScreen(
        submitting = state.submitting,
        screenError = state.screenError,
        onBack = onBack,
        onSubmit = { plate, model, company, acceptsPool ->
            viewModel.register(plate, model, company, acceptsPool, onRegistered)
        },
    )
}

@Composable
fun VehicleScreen(
    submitting: Boolean,
    screenError: String?,
    onBack: () -> Unit,
    onSubmit: (plate: String, model: String, company: String, acceptsPool: Boolean) -> Unit,
) {
    var plate by rememberSaveable { mutableStateOf("") }
    var isCorporate by rememberSaveable { mutableStateOf(false) }   // 개인택시 기본 선택
    var company by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var seats by rememberSaveable { mutableStateOf("") }
    var acceptsPool by rememberSaveable { mutableStateOf(true) }    // 기본 on

    val plateValid = plate.length >= 7
    // 차량번호 입력 시 등록원부 조회로 자동 채움 (더미 — 실연동 시 조회 API 값으로 대체)
    LaunchedEffect(plateValid) {
        if (plateValid && company.isBlank() && model.isBlank()) {
            company = "부산개인택시조합"
            model = "K5"
            seats = "3"
        }
    }
    val canSubmit = plateValid && model.isNotBlank() && company.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "기사 회원가입", onBack = onBack)
        StepProgressBar(step = 3, total = 3)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "차량 번호만 넣으면 자동으로 확인해요",
                style = MoyeotaType.DisplayMd,
                color = MoyeotaColor.InkDeep,
            )
            MoyeotaTextField(
                value = plate,
                onValueChange = { plate = it },
                label = "차량 번호",
                placeholder = "12가 3456",
                helperText = "자동차등록원부 · 보험을 자동 조회해요",
                enabled = !submitting,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioOption(
                    selected = !isCorporate,
                    label = "개인택시",
                    onSelect = { isCorporate = false },
                    modifier = Modifier.weight(1f),
                )
                RadioOption(
                    selected = isCorporate,
                    label = "법인택시",
                    onSelect = { isCorporate = true },
                    modifier = Modifier.weight(1f),
                )
            }
            MoyeotaTextField(
                value = company,
                onValueChange = { company = it },
                label = "소속 · 조합",
                placeholder = "차량 번호 입력 시 자동 조회",
                enabled = !submitting,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MoyeotaTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.weight(1f),
                    label = "차종",
                    placeholder = "자동 조회",
                    enabled = !submitting,
                )
                MoyeotaTextField(
                    value = seats,
                    onValueChange = { seats = it.filter(Char::isDigit).take(1) },
                    modifier = Modifier.weight(1f),
                    label = "승객 좌석",
                    placeholder = "자동 조회",
                    helperText = "합승 인원 상한 기준",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !submitting,
                )
            }
            // 합승 콜 받기 — 행 전체가 탭 영역 (v15/SettingRow 대응)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = "합승 콜 받기", style = MoyeotaType.HeadingLg, color = MoyeotaColor.InkPrimary)
                    Text(
                        text = "합승 1건당 보너스 1,500원 + 호출료를 더 드려요",
                        style = MoyeotaType.BodyLg,
                        color = MoyeotaColor.TextBody,
                    )
                }
                Spacer(Modifier.width(12.dp))
                AuthToggle(checked = acceptsPool, onToggle = { acceptsPool = !acceptsPool })
            }
            if (!acceptsPool) {
                NoticeBanner(
                    kind = NoticeKind.WAITING,
                    text = "합승 콜을 받지 않으면 합승 보너스 프로모션 대상이 아니에요",
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (screenError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = screenError)
                Spacer(Modifier.height(12.dp))
            }
            PrimaryCtaButton(
                text = "가입 완료하기",
                onClick = { onSubmit(plate, model, company, acceptsPool) },
                enabled = canSubmit,
                loading = submitting,
            )
        }
    }
}
