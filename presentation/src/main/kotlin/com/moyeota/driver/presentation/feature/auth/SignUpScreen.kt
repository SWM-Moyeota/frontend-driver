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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// phone-first 4단계 가입 (Routes.AUTH_SIGNUP) — 단일 라우트 안의 내부 스텝 상태 머신.
// 진입: D01 「기사 회원가입」.
//   ① 휴대폰 번호  — isPhoneRegistered 조회. 기가입이면 로그인 유도(D01 복귀), 신규면 ②로
//   ② 회원 프로필  — 이름 · 닉네임(중복 조회) · 아이디 · 비밀번호
//   ③ 택시 정보    — 차종 · 좌석수 · 번호판 · 운수종사자 번호
//   ④ 휴대폰 인증  — ①의 번호로 문자 발송 → 6자리 코드 → verifyPhoneCode 토큰
//                   → 그 토큰을 폼에 담아 **즉시** signUp 제출 → D05b(AUTH_APPROVED)
//
// 인증이 ④(마지막)인 이유: 서버 verificationToken 이 10분 만료다. 인증을 ①에 붙이면
// ②③을 입력하는 동안 토큰이 만료돼 제출이 실패한다. 인증 성공 직후 제출까지 한 번에 끝낸다.
//
// 뒤로가기(시스템 back 포함)는 스텝 후퇴 — 스텝 1에서만 popBackStack. 입력값은 스텝 이동 간 보존.

/** 백엔드 @Pattern(`^01[016-9]-?\d{3,4}-?\d{4}$`)과 동일 — 하이픈 제거한 숫자 10~11자리 기준 */
private val PHONE_REGEX = Regex("^01[016-9]\\d{7,8}$")

/** 백엔드 @Pattern 동일 — 영소문자 시작, 4~20자 영소문자 · 숫자 · _ */
private val LOGIN_ID_REGEX = Regex("^[a-z][a-z0-9_]{3,19}$")

internal fun isValidPhone(digits: String): Boolean = PHONE_REGEX.matches(digits)

internal fun isValidLoginId(loginId: String): Boolean = LOGIN_ID_REGEX.matches(loginId)

/** 백엔드 Nickname 규칙: 2~10자, 한글·영문·숫자만 (중복은 ②단계에서 isNicknameTaken 으로 선조회) */
internal fun isValidNickname(value: String): Boolean = NICKNAME_REGEX.matches(value)

private val NICKNAME_REGEX = Regex("^[가-힣a-zA-Z0-9]{2,10}$")

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
        // ① 휴대폰 조회
        val checkingPhone: Boolean = false,     // isPhoneRegistered 호출 중
        val phoneRegistered: Boolean = false,   // true → 기가입 안내 + 로그인 유도
        val phoneCheckError: String? = null,    // 조회 실패(네트워크 등) — 화면 유지 + 재시도
        // ② 닉네임 중복 선조회 (제출 때 USER108 로 터지지 않게)
        val checkingNickname: Boolean = false,
        val nicknameTaken: Boolean = false,
        val nicknameAvailable: String? = null,  // 「사용 가능」 확인이 끝난 닉네임 값
        val nicknameCheckError: String? = null, // 조회 실패 — 배너 + 「다음」 재시도
        // ④ 문자 인증
        val sendingCode: Boolean = false,       // requestPhoneVerification 호출 중
        val codeSent: Boolean = false,          // 1회 이상 발송 성공 — OTP 입력 활성
        val resendCooldown: Int = 0,            // 재전송 쿨다운 남은 초 (0 = 재전송 가능)
        val verifying: Boolean = false,         // verifyPhoneCode 호출 중
        val codeInvalid: Boolean = false,       // 오입력 — OTP 박스 danger 테두리
        // 제출 (인증 성공 직후 연속 호출)
        val submitting: Boolean = false,
        // ④ 화면 단위 오류 — CTA 위 NoticeBanner(ERROR). 발송 · 인증 · 가입 실패 공용, 서버 메시지 노출
        val submitError: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    // ── ① 휴대폰 번호 ──────────────────────────────────────────────────

    /** ① 「다음」 — 번호 존재 조회. 미등록(신규)일 때만 onNewPhone 으로 ② 진행 */
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

    /**
     * 번호를 고치기 시작하면 이전 조회 결과 · 에러를 무효화한다.
     * ④에서 이미 보낸 인증번호도 **다른 번호의 것**이 되므로 발송 상태 · 쿨다운까지 함께 리셋한다.
     */
    fun resetPhoneCheck() {
        cooldownJob?.cancel()
        _uiState.update {
            it.copy(
                phoneRegistered = false,
                phoneCheckError = null,
                sendingCode = false,
                codeSent = false,
                resendCooldown = 0,
                codeInvalid = false,
                submitError = null,
            )
        }
    }

    // ── ② 닉네임 중복 ──────────────────────────────────────────────────

    /**
     * 닉네임 중복 선조회 — ② 포커스 아웃 · 「다음」 직전에 호출된다.
     * 사용 가능할 때만 [onAvailable] 을 실행한다(「다음」이 그대로 ③으로 이어지는 경로).
     */
    fun checkNickname(nickname: String, onAvailable: () -> Unit) {
        if (_uiState.value.checkingNickname) return
        viewModelScope.launch {
            _uiState.update { it.copy(checkingNickname = true, nicknameCheckError = null) }
            try {
                val taken = repository.isNicknameTaken(nickname)
                _uiState.update {
                    it.copy(
                        checkingNickname = false,
                        nicknameTaken = taken,
                        nicknameAvailable = if (taken) null else nickname,
                    )
                }
                if (!taken) onAvailable()
            } catch (e: Exception) {
                // 조회 실패 — 진행시키지 않는다(제출 때 USER108 로 터지는 것보다 여기서 재시도가 낫다)
                _uiState.update {
                    it.copy(
                        checkingNickname = false,
                        nicknameCheckError = e.message ?: "닉네임 확인에 실패했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    /** 닉네임을 고치면 이전 중복 조회 결과를 무효화 */
    fun resetNicknameCheck() {
        _uiState.update {
            it.copy(nicknameTaken = false, nicknameAvailable = null, nicknameCheckError = null)
        }
    }

    // ── ④ 문자 인증 ───────────────────────────────────────────────────

    /** ④ 진입 시 자동 1회 + 「재전송」 — 쿨다운 중이면 무시(중복 발송 차단) */
    fun sendCode(phoneNumber: String) {
        val current = _uiState.value
        if (current.sendingCode || current.resendCooldown > 0) return
        viewModelScope.launch {
            _uiState.update { it.copy(sendingCode = true, submitError = null, codeInvalid = false) }
            try {
                repository.requestPhoneVerification(phoneNumber)
                _uiState.update {
                    it.copy(
                        sendingCode = false,
                        codeSent = true,
                        resendCooldown = RESEND_COOLDOWN_SECONDS,
                    )
                }
                startCooldown()
            } catch (e: Exception) {
                // 발송 실패 — codeSent 를 올리지 않아 CTA 가 「인증번호 받기」로 남는다
                _uiState.update {
                    it.copy(
                        sendingCode = false,
                        submitError = e.message ?: "인증번호를 보내지 못했어요 · 다시 시도해 주세요",
                    )
                }
            }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            while (_uiState.value.resendCooldown > 0) {
                delay(1_000)
                _uiState.update { it.copy(resendCooldown = (it.resendCooldown - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** 코드를 고치면 오입력 표시를 지운다 */
    fun onCodeEdited() {
        if (_uiState.value.codeInvalid || _uiState.value.submitError != null) {
            _uiState.update { it.copy(codeInvalid = false, submitError = null) }
        }
    }

    /**
     * ④ 「가입 완료」 — 인증 → 토큰 → 제출을 **한 번에** 수행한다.
     * 토큰이 10분 만료라 중간에 사용자 입력을 끼우지 않는 것이 요점.
     */
    fun verifyAndSignUp(form: DriverSignUpForm, code: String, onSignedUp: () -> Unit) {
        val current = _uiState.value
        if (current.verifying || current.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(verifying = true, codeInvalid = false, submitError = null) }
            val token = try {
                repository.verifyPhoneCode(form.phoneNumber, code)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        verifying = false,
                        codeInvalid = true,
                        submitError = e.message ?: "인증번호가 달라요 · 다시 확인해 주세요",
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(verifying = false, submitting = true) }
            try {
                repository.signUp(form.copy(verificationToken = token))
                _uiState.update { it.copy(submitting = false) }
                onSignedUp()
            } catch (e: Exception) {
                // 인증 토큰은 1회용 — 제출이 실패하면 처음부터 다시 인증해야 한다.
                // 발송 상태 · 쿨다운을 풀어 바로 재전송할 수 있게 하고, 입력값은 전부 보존한다.
                cooldownJob?.cancel()
                _uiState.update {
                    it.copy(
                        submitting = false,
                        codeSent = false,
                        resendCooldown = 0,
                        submitError = e.message ?: "가입에 실패했어요 · 인증번호를 다시 받아 주세요",
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
        onCheckNickname = viewModel::checkNickname,
        onNicknameEdited = viewModel::resetNicknameCheck,
        onSendCode = viewModel::sendCode,
        onCodeEdited = viewModel::onCodeEdited,
        onSubmit = { form, code -> viewModel.verifyAndSignUp(form, code, onSignedUp) },
    )
}

@Composable
fun SignUpScreen(
    state: SignUpViewModel.UiState,
    onBack: () -> Unit,
    onCheckPhone: (phoneDigits: String, onNewPhone: () -> Unit) -> Unit,
    onPhoneEdited: () -> Unit,
    onCheckNickname: (nickname: String, onAvailable: () -> Unit) -> Unit,
    onNicknameEdited: () -> Unit,
    onSendCode: (phoneNumber: String) -> Unit,
    onCodeEdited: () -> Unit,
    onSubmit: (DriverSignUpForm, code: String) -> Unit,
) {
    // 스텝 · 입력값 전부 rememberSaveable — 스텝 전환 · 프로세스 재생성에도 보존
    var step by rememberSaveable { mutableIntStateOf(1) }

    // ① 휴대폰 (숫자만, 최대 11자리)
    var phoneDigits by rememberSaveable { mutableStateOf("") }
    // ② 프로필
    var name by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // ③ 택시 정보
    var vehicleType by rememberSaveable { mutableStateOf("") }
    var seats by rememberSaveable { mutableStateOf("") }
    var plateNumber by rememberSaveable { mutableStateOf("") }
    var qualificationNumber by rememberSaveable { mutableStateOf("") }
    // ④ 문자 인증번호
    var code by rememberSaveable { mutableStateOf("") }

    // 시스템 back 도 스텝 후퇴 — 스텝 1에서만 라우트 이탈(popBackStack)
    val goBack: () -> Unit = { if (step > 1) step -= 1 else onBack() }
    BackHandler(onBack = goBack)

    val phoneNumber = formatPhoneNumber(phoneDigits)

    // ④ 진입 시 자동 1회 발송 — ①에서 번호를 이미 받았으니 한 번 더 누르게 하지 않는다.
    // 재진입(③↔④ 왕복) 시에는 codeSent · 쿨다운 가드가 중복 발송을 막는다.
    LaunchedEffect(step, phoneDigits) {
        if (step == 4 && !state.codeSent && !state.sendingCode && state.resendCooldown == 0) {
            code = ""
            onSendCode(phoneNumber)
        }
    }

    val busy = state.checkingPhone || state.checkingNickname || state.verifying || state.submitting
    val nicknameTrimmed = nickname.trim()
    val profileValid = name.isNotBlank() && isValidNickname(nicknameTrimmed) &&
        isValidLoginId(loginId) && isValidPassword(password)
    val seatsValue = seats.toIntOrNull()
    val taxiValid = vehicleType.isNotBlank() && seatsValue != null && seatsValue >= 2 &&
        plateNumber.isNotBlank() && qualificationNumber.isNotBlank()

    /** 닉네임 중복 확인 요청 — 이미 「사용 가능」으로 확인된 값이면 조회 없이 통과 */
    val ensureNicknameChecked: (onAvailable: () -> Unit) -> Unit = { onAvailable ->
        when {
            !isValidNickname(nicknameTrimmed) -> Unit                   // 형식부터 미달 — 조회 안 함
            state.nicknameAvailable == nicknameTrimmed -> onAvailable()  // 확인 완료 — 바로 진행
            else -> onCheckNickname(nicknameTrimmed, onAvailable)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        MoyeotaTopBar(title = "기사 회원가입", onBack = goBack)
        StepProgressBar(step = step, total = 4)
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
                    nickname = nickname,
                    onNicknameChange = {
                        nickname = it.take(10)
                        onNicknameEdited()
                    },
                    // 포커스 아웃 즉시 중복 조회 — 「다음」까지 기다리지 않고 알려준다
                    onNicknameFocusOut = { ensureNicknameChecked {} },
                    nicknameTaken = state.nicknameTaken,
                    nicknameAvailable = state.nicknameAvailable == nicknameTrimmed,
                    checkingNickname = state.checkingNickname,
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
                3 -> TaxiStepFields(
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
                // ④ D02 입력 UI 재사용 (PhoneCodeStep.kt — OtpCodeInput + 재전송)
                else -> PhoneCodeStepFields(
                    phoneDisplay = phoneNumber,
                    code = code,
                    onCodeChange = {
                        code = it
                        onCodeEdited()
                    },
                    codeSent = state.codeSent,
                    sendingCode = state.sendingCode,
                    resendCooldown = state.resendCooldown,
                    codeInvalid = state.codeInvalid,
                    // 재전송하면 새 코드가 오므로 이전에 넣은 숫자를 비운다
                    onSendCode = {
                        code = ""
                        onSendCode(phoneNumber)
                    },
                    enabled = !state.verifying && !state.submitting,
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
                2 -> {
                    if (state.nicknameCheckError != null) {
                        NoticeBanner(kind = NoticeKind.ERROR, text = state.nicknameCheckError)
                        Spacer(Modifier.height(12.dp))
                    }
                    PrimaryCtaButton(
                        text = "다음",
                        // 「다음」 직전에도 한 번 더 확인 — 포커스 아웃을 건너뛴 경로 방어
                        onClick = { ensureNicknameChecked { step = 3 } },
                        enabled = profileValid && !state.nicknameTaken,
                        loading = state.checkingNickname,
                    )
                }
                3 -> PrimaryCtaButton(
                    text = "다음",
                    onClick = { step = 4 },
                    enabled = taxiValid,
                )
                else -> {
                    if (state.submitError != null) {
                        NoticeBanner(kind = NoticeKind.ERROR, text = state.submitError)
                        Spacer(Modifier.height(12.dp))
                    }
                    PrimaryCtaButton(
                        text = "인증하고 가입 완료",
                        onClick = {
                            onSubmit(
                                DriverSignUpForm(
                                    phoneNumber = phoneNumber,
                                    name = name.trim(),
                                    nickname = nicknameTrimmed,
                                    loginId = loginId,
                                    password = password,
                                    vehicleType = vehicleType.trim(),
                                    seats = seatsValue ?: 0,
                                    plateNumber = plateNumber.trim(),
                                    qualificationNumber = qualificationNumber.trim(),
                                    // 인증 성공 후 ViewModel 이 발급 토큰으로 채운다
                                    verificationToken = "",
                                ),
                                code,
                            )
                        },
                        enabled = state.codeSent && code.length == OTP_CODE_LENGTH && taxiValid,
                        loading = state.verifying || state.submitting,
                    )
                }
            }
        }
    }
}

// ── ① 휴대폰 번호 ──────────────────────────────────────────────────────

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
        helperText = "숫자만 입력 · 문자 인증은 마지막 단계에서 해요",
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

// ── ② 회원 프로필 ──────────────────────────────────────────────────────

@Composable
private fun ProfileStepFields(
    name: String,
    onNameChange: (String) -> Unit,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onNicknameFocusOut: () -> Unit,
    nicknameTaken: Boolean,
    nicknameAvailable: Boolean,
    checkingNickname: Boolean,
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
    val nicknameFormatOk = isValidNickname(nickname.trim())
    MoyeotaTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        label = "닉네임",
        placeholder = "번개기사",
        helperText = when {
            checkingNickname -> "쓸 수 있는 닉네임인지 확인하고 있어요"
            nicknameAvailable -> "쓸 수 있는 닉네임이에요"
            else -> "동승자에게 보이는 이름 · 2~10자 한글 · 영문 · 숫자"
        },
        errorText = when {
            nickname.isNotEmpty() && !nicknameFormatOk -> "2~10자의 한글 · 영문 · 숫자만 쓸 수 있어요"
            nicknameTaken -> "이미 누군가 쓰고 있는 닉네임이에요"
            else -> null
        },
        enabled = enabled,
        // 포커스 아웃 시점에 중복 조회 — 제출 때 USER108 로 터지는 것을 ②에서 막는다
        onFocusChange = { focused -> if (!focused) onNicknameFocusOut() },
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

// ── ③ 택시 정보 ───────────────────────────────────────────────────────

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
        text = "택시 정보를 알려주세요",
        style = MoyeotaType.DisplayMd,
        color = MoyeotaColor.InkDeep,
    )
    Text(
        text = "다음 단계에서 문자 인증만 하면 끝이에요",
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
