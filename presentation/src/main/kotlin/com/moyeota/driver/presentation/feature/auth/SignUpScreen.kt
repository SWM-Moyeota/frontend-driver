package com.moyeota.driver.presentation.feature.auth

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.moyeota.driver.domain.model.DriverSignUpForm
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// phone-first 3단계 가입 (Routes.AUTH_SIGNUP) — 단일 라우트 안의 내부 스텝 상태 머신.
// 진입: D01 「기사 회원가입」.
//   ① 휴대폰 확인  — isPhoneRegistered 조회. 기가입이면 로그인 유도(D01 복귀), 신규면 ②로
//   ② 회원 프로필  — 이름 · 아이디 · 비밀번호
//   ③ 택시 정보    — 차종 · 좌석수 · 번호판 · 운수종사자 번호 → signUp 일괄 제출 → D05b(AUTH_APPROVED)
// 뒤로가기(시스템 back 포함)는 스텝 후퇴 — 스텝 1에서만 popBackStack. 입력값은 스텝 이동 간 보존.
// 휴대폰 OTP 인증은 이번 범위 아님 (추후 추가 예정 — 존재 조회만 수행).

/** 백엔드 @Pattern(`^01[016-9]-?\d{3,4}-?\d{4}$`)과 동일 — 하이픈 제거한 숫자 10~11자리 기준 */
private val PHONE_REGEX = Regex("^01[016-9]\\d{7,8}$")

/** 백엔드 @Pattern 동일 — 영소문자 시작, 4~20자 영소문자 · 숫자 · _ */
private val LOGIN_ID_REGEX = Regex("^[a-z][a-z0-9_]{3,19}$")

internal fun isValidPhone(digits: String): Boolean = PHONE_REGEX.matches(digits)

internal fun isValidLoginId(loginId: String): Boolean = LOGIN_ID_REGEX.matches(loginId)

/** 백엔드 규칙 동일 — 8~64자 + 영문 · 숫자 · 특수문자 각 1개 이상 */
internal fun isValidPassword(password: String): Boolean =
    password.length in 8..64 &&
        password.any { it.isLetter() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() && !it.isWhitespace() }

/** 숫자만 받은 번호를 서버 형식(하이픈)으로 — "01012345678" → "010-1234-5678" */
internal fun formatPhoneNumber(digits: String): String = when (digits.length) {
    11 -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
    10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
    else -> digits
}

class SignUpViewModel(private val repository: DriverRepository) : ViewModel() {

    data class UiState(
        // 스텝 1 — 휴대폰 조회
        val checkingPhone: Boolean = false,     // isPhoneRegistered 호출 중
        val phoneRegistered: Boolean = false,   // true → 기가입 안내 + 로그인 유도
        val phoneCheckError: String? = null,    // 조회 실패(네트워크 등) — 화면 유지 + 재시도
        // 스텝 3 — 최종 제출
        val submitting: Boolean = false,
        val submitError: String? = null,        // 실패 — CTA 위 NoticeBanner(ERROR), 입력 보존
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 스텝 1 「다음」 — 번호 존재 조회. 미등록(신규)일 때만 onNewPhone 으로 스텝 2 진행 */
    fun checkPhone(phoneDigits: String, onNewPhone: () -> Unit) {
        if (_uiState.value.checkingPhone) return
        viewModelScope.launch {
            _uiState.update { it.copy(checkingPhone = true, phoneCheckError = null) }
            try {
                val registered = repository.isPhoneRegistered(formatPhoneNumber(phoneDigits))
                _uiState.update { it.copy(checkingPhone = false, phoneRegistered = registered) }
                if (!registered) onNewPhone()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        checkingPhone = false,
                        phoneCheckError = e.message ?: "번호 확인에 실패했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    /** 번호를 고치기 시작하면 이전 조회 결과 · 에러를 무효화 */
    fun resetPhoneCheck() {
        _uiState.update { it.copy(phoneRegistered = false, phoneCheckError = null) }
    }

    fun signUp(form: DriverSignUpForm, onSignedUp: () -> Unit) {
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, submitError = null) }
            try {
                repository.signUp(form)
                _uiState.update { it.copy(submitting = false) }
                onSignedUp()
            } catch (e: Exception) {
                // 실패 시 화면 유지 + 입력값 보존 — repository 예외 message 를 그대로 노출
                _uiState.update {
                    it.copy(
                        submitting = false,
                        submitError = e.message ?: "가입에 실패했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { SignUpViewModel(repository) }
        }
    }
}

@Composable
fun SignUpRoute(
    repository: DriverRepository,
    onBack: () -> Unit,
    onSignedUp: () -> Unit,
) {
    val viewModel: SignUpViewModel = viewModel(factory = SignUpViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    SignUpScreen(
        state = state,
        onBack = onBack,
        onCheckPhone = viewModel::checkPhone,
        onPhoneEdited = viewModel::resetPhoneCheck,
        onSubmit = { form -> viewModel.signUp(form, onSignedUp) },
    )
}

@Composable
fun SignUpScreen(
    state: SignUpViewModel.UiState,
    onBack: () -> Unit,
    onCheckPhone: (phoneDigits: String, onNewPhone: () -> Unit) -> Unit,
    onPhoneEdited: () -> Unit,
    onSubmit: (DriverSignUpForm) -> Unit,
) {
    // 스텝 · 입력값 전부 rememberSaveable — 스텝 전환 · 프로세스 재생성에도 보존
    var step by rememberSaveable { mutableIntStateOf(1) }

    // 스텝 1 — 휴대폰 (숫자만, 최대 11자리)
    var phoneDigits by rememberSaveable { mutableStateOf("") }
    // 스텝 2 — 프로필
    var name by rememberSaveable { mutableStateOf("") }
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // 스텝 3 — 택시 정보
    var vehicleType by rememberSaveable { mutableStateOf("") }
    var seats by rememberSaveable { mutableStateOf("") }
    var plateNumber by rememberSaveable { mutableStateOf("") }
    var qualificationNumber by rememberSaveable { mutableStateOf("") }

    // 시스템 back 도 스텝 후퇴 — 스텝 1에서만 라우트 이탈(popBackStack)
    val goBack: () -> Unit = { if (step > 1) step -= 1 else onBack() }
    BackHandler(onBack = goBack)

    val busy = state.checkingPhone || state.submitting

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "기사 회원가입", onBack = goBack)
        StepProgressBar(step = step, total = 3)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                1 -> PhoneStepFields(
                    phoneDigits = phoneDigits,
                    onPhoneChange = { input ->
                        phoneDigits = input.filter(Char::isDigit).take(11)
                        onPhoneEdited()
                    },
                    registered = state.phoneRegistered,
                    enabled = !busy,
                )
                2 -> ProfileStepFields(
                    name = name,
                    onNameChange = { name = it },
                    loginId = loginId,
                    onLoginIdChange = { input ->
                        loginId = input.lowercase()
                            .filter { it in 'a'..'z' || it.isDigit() || it == '_' }
                            .take(20)
                    },
                    password = password,
                    onPasswordChange = { password = it },
                    enabled = !busy,
                )
                else -> TaxiStepFields(
                    vehicleType = vehicleType,
                    onVehicleTypeChange = { vehicleType = it },
                    seats = seats,
                    onSeatsChange = { seats = it.filter(Char::isDigit).take(1) },
                    plateNumber = plateNumber,
                    onPlateChange = { plateNumber = it },
                    qualificationNumber = qualificationNumber,
                    onQualificationChange = { qualificationNumber = it },
                    enabled = !busy,
                )
            }
        }
        // 하단 CTA — 스텝별 활성 조건 · 로딩. 화면 단위 에러는 CTA 위 NoticeBanner(ERROR)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (step) {
                1 -> {
                    if (state.phoneCheckError != null) {
                        NoticeBanner(kind = NoticeKind.ERROR, text = state.phoneCheckError)
                        Spacer(Modifier.height(12.dp))
                    }
                    if (state.phoneRegistered) {
                        // 기가입 번호 — 가입 대신 기존 계정 로그인 유도 (D01 복귀)
                        PrimaryCtaButton(text = "로그인하러 가기", onClick = onBack)
                    } else {
                        PrimaryCtaButton(
                            text = "다음",
                            onClick = { onCheckPhone(phoneDigits) { step = 2 } },
                            enabled = isValidPhone(phoneDigits),
                            loading = state.checkingPhone,
                        )
                    }
                }
                2 -> PrimaryCtaButton(
                    text = "다음",
                    onClick = { step = 3 },
                    enabled = name.isNotBlank() && isValidLoginId(loginId) && isValidPassword(password),
                )
                else -> {
                    if (state.submitError != null) {
                        NoticeBanner(kind = NoticeKind.ERROR, text = state.submitError)
                        Spacer(Modifier.height(12.dp))
                    }
                    val seatsValue = seats.toIntOrNull()
                    PrimaryCtaButton(
                        text = "가입 완료",
                        onClick = {
                            onSubmit(
                                DriverSignUpForm(
                                    phoneNumber = formatPhoneNumber(phoneDigits),
                                    name = name.trim(),
                                    loginId = loginId,
                                    password = password,
                                    vehicleType = vehicleType.trim(),
                                    seats = seatsValue ?: 0,
                                    plateNumber = plateNumber.trim(),
                                    qualificationNumber = qualificationNumber.trim(),
                                ),
                            )
                        },
                        enabled = vehicleType.isNotBlank() && seatsValue != null && seatsValue >= 2 &&
                            plateNumber.isNotBlank() && qualificationNumber.isNotBlank(),
                        loading = state.submitting,
                    )
                }
            }
        }
    }
}

// ── 스텝 1 · 휴대폰 확인 ────────────────────────────────────────────────

@Composable
private fun PhoneStepFields(
    phoneDigits: String,
    onPhoneChange: (String) -> Unit,
    registered: Boolean,
    enabled: Boolean,
) {
    Text(
        text = "휴대폰 번호부터 확인할게요",
        style = MoyeotaType.DisplayMd,
        color = MoyeotaColor.InkDeep,
    )
    Text(
        text = "이미 모여타 계정이 있는지 먼저 확인해요",
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextBody,
    )
    MoyeotaTextField(
        value = phoneDigits,
        onValueChange = onPhoneChange,
        label = "휴대폰 번호",
        placeholder = "01012345678",
        helperText = "숫자만 입력 · 본인 인증은 추후 추가 예정이에요",
        errorText = if (phoneDigits.length == 11 && !isValidPhone(phoneDigits)) {
            "휴대폰 번호 형식이 아니에요"
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
    )
    if (registered) {
        // 기가입 안내 — 화면 유지, 하단 CTA 가 「로그인하러 가기」로 바뀐다
        NoticeBanner(
            kind = NoticeKind.INFO,
            text = "이미 가입된 번호예요. 기존 계정으로 로그인 후 기사 정보를 등록해 주세요",
        )
    }
}

// ── 스텝 2 · 회원 프로필 ────────────────────────────────────────────────

@Composable
private fun ProfileStepFields(
    name: String,
    onNameChange: (String) -> Unit,
    loginId: String,
    onLoginIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean,
) {
    Text(
        text = "기사님 정보를 알려주세요",
        style = MoyeotaType.DisplayMd,
        color = MoyeotaColor.InkDeep,
    )
    Text(
        text = "로그인에 쓸 계정을 만들어요",
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextBody,
    )
    MoyeotaTextField(
        value = name,
        onValueChange = onNameChange,
        label = "이름",
        placeholder = "홍길동",
        enabled = enabled,
    )
    MoyeotaTextField(
        value = loginId,
        onValueChange = onLoginIdChange,
        label = "아이디",
        placeholder = "moyeota_driver",
        helperText = "영소문자 시작, 4~20자 영소문자 · 숫자 · _",
        errorText = if (loginId.isNotEmpty() && !isValidLoginId(loginId)) {
            "영소문자로 시작하는 4~20자여야 해요"
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        enabled = enabled,
    )
    MoyeotaTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = "비밀번호",
        placeholder = "비밀번호 입력",
        helperText = "8자 이상, 영문 · 숫자 · 특수문자 포함",
        errorText = if (password.isNotEmpty() && !isValidPassword(password)) {
            "영문 · 숫자 · 특수문자를 모두 포함한 8자 이상이어야 해요"
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        enabled = enabled,
    )
}

// ── 스텝 3 · 택시 정보 ─────────────────────────────────────────────────

@Composable
private fun TaxiStepFields(
    vehicleType: String,
    onVehicleTypeChange: (String) -> Unit,
    seats: String,
    onSeatsChange: (String) -> Unit,
    plateNumber: String,
    onPlateChange: (String) -> Unit,
    qualificationNumber: String,
    onQualificationChange: (String) -> Unit,
    enabled: Boolean,
) {
    val seatsValue = seats.toIntOrNull()
    Text(
        text = "마지막이에요, 택시 정보만 남았어요",
        style = MoyeotaType.DisplayMd,
        color = MoyeotaColor.InkDeep,
    )
    Text(
        text = "가입 즉시 승인되고 바로 영업할 수 있어요",
        style = MoyeotaType.BodyLg,
        color = MoyeotaColor.TextBody,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MoyeotaTextField(
            value = vehicleType,
            onValueChange = onVehicleTypeChange,
            modifier = Modifier.weight(1f),
            label = "차종",
            placeholder = "K5",
            enabled = enabled,
        )
        MoyeotaTextField(
            value = seats,
            onValueChange = onSeatsChange,
            modifier = Modifier.weight(1f),
            label = "좌석수",
            placeholder = "4",
            helperText = "2석 이상",
            errorText = if (seats.isNotEmpty() && (seatsValue == null || seatsValue < 2)) {
                "좌석수는 2 이상이어야 해요"
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
        )
    }
    MoyeotaTextField(
        value = plateNumber,
        onValueChange = onPlateChange,
        label = "차량 번호",
        placeholder = "12가 3456",
        enabled = enabled,
    )
    MoyeotaTextField(
        value = qualificationNumber,
        onValueChange = onQualificationChange,
        label = "운수종사자 번호",
        placeholder = "운수종사자 자격 번호 입력",
        enabled = enabled,
    )
}
