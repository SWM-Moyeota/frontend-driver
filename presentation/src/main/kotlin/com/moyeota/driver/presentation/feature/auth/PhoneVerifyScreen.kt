package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.moyeota.core.designsystem.component.SecondaryButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// D02 · 기사 회원가입 — 휴대폰 인증 (피그마 2511:452)
// 진입: D01 「기사 회원가입」. 「다음」(verifyOtp 성공 + 필수 약관 2개) → D03 자격 조회.

class PhoneVerifyViewModel(private val repository: DriverRepository) : ViewModel() {

    data class UiState(
        val requestingOtp: Boolean = false,
        val otpRequested: Boolean = false,
        val secondsLeft: Int = 0,            // 인증번호 유효 시간 (3분)
        val verifying: Boolean = false,
        val otpError: String? = null,        // 오입력 — OtpInput error 상태
        val screenError: String? = null,     // 화면 단위 오류 — CTA 위 error 배너
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var timerJob: Job? = null

    fun requestOtp(phone: String) {
        if (_uiState.value.requestingOtp) return
        viewModelScope.launch {
            _uiState.update { it.copy(requestingOtp = true, otpError = null, screenError = null) }
            try {
                repository.requestOtp(phone)
                _uiState.update {
                    it.copy(requestingOtp = false, otpRequested = true, secondsLeft = OTP_VALID_SECONDS)
                }
                startTimer()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(requestingOtp = false, screenError = "인증번호를 보내지 못했어요 · 다시 시도해 주세요")
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value.secondsLeft > 0) {
                delay(1_000)
                _uiState.update { it.copy(secondsLeft = it.secondsLeft - 1) }
            }
        }
    }

    fun verify(phone: String, code: String, onVerified: () -> Unit) {
        if (_uiState.value.verifying) return
        viewModelScope.launch {
            _uiState.update { it.copy(verifying = true, otpError = null, screenError = null) }
            try {
                val ok = repository.verifyOtp(phone, code)
                _uiState.update { it.copy(verifying = false) }
                if (ok) {
                    onVerified()
                } else {
                    _uiState.update { it.copy(otpError = "인증번호가 달라요 · 다시 확인해 주세요") }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(verifying = false, screenError = "인증에 실패했어요 · 잠시 후 다시 시도해 주세요")
                }
            }
        }
    }

    companion object {
        private const val OTP_VALID_SECONDS = 180
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { PhoneVerifyViewModel(repository) }
        }
    }
}

@Composable
fun PhoneVerifyRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onVerified: () -> Unit,
) {
    val viewModel: PhoneVerifyViewModel = viewModel(factory = PhoneVerifyViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    PhoneVerifyScreen(
        state = state,
        onBack = onBack,
        onRequestOtp = viewModel::requestOtp,
        onSubmit = { phone, code -> viewModel.verify(phone, code, onVerified) },
    )
}

@Composable
fun PhoneVerifyScreen(
    state: PhoneVerifyViewModel.UiState,
    onBack: () -> Unit,
    onRequestOtp: (phone: String) -> Unit,
    onSubmit: (phone: String, code: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var agreeTerms by rememberSaveable { mutableStateOf(false) }      // (필수)
    var agreeLocation by rememberSaveable { mutableStateOf(false) }   // (필수)
    var agreeMarketing by rememberSaveable { mutableStateOf(false) }  // (선택)

    val expired = state.otpRequested && state.secondsLeft == 0
    // 코드 6자리 + 필수 약관 2개 + 인증번호 발송 완료(미만료)까지 CTA disabled
    val canSubmit = state.otpRequested && !expired && code.length == 6 && agreeTerms && agreeLocation

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "기사 회원가입", onBack = onBack)
        StepProgressBar(step = 1, total = 3)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "본인 확인부터 할게요", style = MoyeotaType.DisplayLg, color = MoyeotaColor.InkDeep)
            MoyeotaTextField(
                value = name,
                onValueChange = { name = it },
                label = "이름",
                placeholder = "홍길동",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                MoyeotaTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                    modifier = Modifier.weight(1f),
                    label = "휴대폰 번호",
                    placeholder = "- 없이 숫자만",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                SecondaryButton(
                    text = if (state.otpRequested) "재발송" else "인증번호",
                    onClick = { onRequestOtp(phone) },
                    modifier = Modifier.width(112.dp),
                    enabled = phone.length == 11 && name.isNotBlank() && !state.requestingOtp,
                )
            }
            OtpInput(
                code = code,
                onCodeChange = { code = it },
                error = state.otpError != null,
                enabled = state.otpRequested,
            )
            if (state.otpError != null) {
                Text(text = state.otpError, style = MoyeotaType.BodyMd, color = MoyeotaColor.Danger500)
            }
            Text(
                text = when {
                    !state.otpRequested -> "인증번호를 요청하면 문자로 보내드려요"
                    expired -> "인증번호가 만료됐어요 · 재발송을 눌러 주세요"
                    else -> "남은 시간 %02d:%02d · 문자가 안 오면 재발송".format(
                        state.secondsLeft / 60, state.secondsLeft % 60,
                    )
                },
                style = MoyeotaType.BodyLg,
                color = if (expired) MoyeotaColor.Danger500 else MoyeotaColor.TextMute,
            )
            AgreeCheckRow(
                checked = agreeTerms,
                label = "(필수) 이용약관 · 개인정보 처리방침",
                onToggle = { agreeTerms = !agreeTerms },
            )
            AgreeCheckRow(
                checked = agreeLocation,
                label = "(필수) 위치기반 서비스 이용 동의",
                onToggle = { agreeLocation = !agreeLocation },
            )
            AgreeCheckRow(
                checked = agreeMarketing,
                label = "(선택) 콜 · 이벤트 알림 수신",
                onToggle = { agreeMarketing = !agreeMarketing },
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (state.screenError != null) {
                NoticeBanner(kind = NoticeKind.ERROR, text = state.screenError)
                Spacer(Modifier.height(12.dp))
            }
            PrimaryCtaButton(
                text = "다음",
                onClick = { onSubmit(phone, code) },
                enabled = canSubmit,
                loading = state.verifying,
            )
        }
    }
}

// 6자리 인증번호 입력 — 승객 v15/OtpInput 대응 (auth 전용)
@Composable
private fun OtpInput(
    code: String,
    onCodeChange: (String) -> Unit,
    error: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(6)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(6) { index ->
                        val filled = index < code.length
                        val borderColor = when {
                            error -> MoyeotaColor.Danger500
                            filled -> MoyeotaColor.Primary500
                            else -> MoyeotaColor.Hairline
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                                .background(MoyeotaColor.SurfaceCard, RoundedCornerShape(14.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = code.getOrNull(index)?.toString() ?: "",
                                style = MoyeotaType.NumberMd,
                                color = if (error) MoyeotaColor.Danger500 else MoyeotaColor.InkPrimary,
                            )
                        }
                    }
                }
                // 실제 입력 필드는 투명하게 겹쳐 두고 박스 UI 로 표시한다
                Box(Modifier.matchParentSize().alpha(0f)) { innerTextField() }
            }
        },
    )
}
