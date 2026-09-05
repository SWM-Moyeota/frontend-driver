package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

// D03 · 면허 · 자격 자동 조회 (피그마 2513:457)
// 진입: D02 「다음」. 「자동 조회하기」 → 전 항목 통과 D03b · 불일치 D05 · 3회 조회 실패 D05.

class QualificationViewModel(private val repository: DriverRepository) : ViewModel() {

    data class UiState(
        val checking: Boolean = false,
        val failCount: Int = 0,              // 조회 오류 횟수 — 3회면 D05 예외 심사 분기
        val screenError: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun check(
        licenseNumber: String,
        licenseSerial: String,
        taxiCertNumber: String,
        onPassed: () -> Unit,
        onFailed: () -> Unit,
    ) {
        if (_uiState.value.checking) return
        viewModelScope.launch {
            _uiState.update { it.copy(checking = true, screenError = null) }
            try {
                val result = repository.checkQualifications(licenseNumber, licenseSerial, taxiCertNumber)
                _uiState.update { it.copy(checking = false) }
                if (result.allPassed) onPassed() else onFailed()
            } catch (e: Exception) {
                val fails = _uiState.value.failCount + 1
                if (fails >= MAX_FAILS) {
                    _uiState.value = UiState()
                    onFailed() // 조회 3회 실패 → 예외 심사(D05) 분기
                } else {
                    _uiState.value = UiState(
                        failCount = fails,
                        screenError = "조회에 실패했어요 · 다시 시도해 주세요 ($fails/$MAX_FAILS)",
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_FAILS = 3
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { QualificationViewModel(repository) }
        }
    }
}

@Composable
fun QualificationRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onPassed: () -> Unit,
    onFailed: () -> Unit,
) {
    val viewModel: QualificationViewModel = viewModel(factory = QualificationViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    QualificationScreen(
        checking = state.checking,
        screenError = state.screenError,
        onBack = onBack,
        onCheck = { license, serial, cert -> viewModel.check(license, serial, cert, onPassed, onFailed) },
    )
}

@Composable
fun QualificationScreen(
    checking: Boolean,
    screenError: String?,
    onBack: () -> Unit,
    onCheck: (license: String, serial: String, cert: String) -> Unit,
) {
    var license by rememberSaveable { mutableStateOf("") }
    var serial by rememberSaveable { mutableStateOf("") }
    var cert by rememberSaveable { mutableStateOf("") }
    // 형식 검사(면허 12자리 · 자격증 8자리) 통과까지 CTA disabled
    val canSubmit = license.length == 12 && serial.isNotBlank() && cert.length == 8

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "기사 회원가입", onBack = onBack)
        StepProgressBar(step = 2, total = 3)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "면허 · 자격을 자동으로 확인할게요",
                style = MoyeotaType.DisplayMd,
                color = MoyeotaColor.InkDeep,
            )
            MoyeotaTextField(
                value = license,
                onValueChange = { license = it.filter(Char::isDigit).take(12) },
                label = "운전면허 번호",
                placeholder = "11-22-334455-66 (- 없이 숫자만)",
                helperText = "숫자 12자리",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !checking,
            )
            MoyeotaTextField(
                value = serial,
                onValueChange = { serial = it.take(8).uppercase() },
                label = "암호 일련번호",
                placeholder = "ABCD1234",
                helperText = "면허증 뒷면 오른쪽 위에 있어요",
                enabled = !checking,
            )
            MoyeotaTextField(
                value = cert,
                onValueChange = { cert = it.filter(Char::isDigit).take(8) },
                label = "택시운전자격증 번호",
                placeholder = "12345678",
                helperText = "숫자 8자리",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !checking,
            )
            NoticeBanner(kind = NoticeKind.INFO, text = "사진 제출 없이 조회로 확인해요 · 약 30초")
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (screenError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = screenError)
                Spacer(Modifier.height(12.dp))
            }
            // 조회 중 CTA 비활성 + 로딩 — 중복 조회 차단
            PrimaryCtaButton(
                text = "자동 조회하기",
                onClick = { onCheck(license, serial, cert) },
                enabled = canSubmit,
                loading = checking,
            )
        }
    }
}
