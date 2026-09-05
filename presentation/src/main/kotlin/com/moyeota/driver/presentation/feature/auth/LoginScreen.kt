package com.moyeota.driver.presentation.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyeota.core.designsystem.component.NoticeBanner
import com.moyeota.core.designsystem.component.NoticeKind
import com.moyeota.core.designsystem.component.MoyeotaTextField
import com.moyeota.core.designsystem.component.PrimaryCtaButton
import com.moyeota.core.designsystem.component.StatusBarMock
import com.moyeota.core.designsystem.theme.MoyeotaColor
import com.moyeota.core.designsystem.theme.MoyeotaType
import com.moyeota.driver.domain.model.DriverAccountStatus
import com.moyeota.driver.domain.repository.DriverRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// D01 · 기사 로그인 (피그마 2511:426)
// 진입: 앱 실행 첫 화면. 로그인 → APPROVED 는 HOME, PENDING_REVIEW 는 D05 심사 대기.

class LoginViewModel(private val repository: DriverRepository) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val fieldError: String? = null,      // 인증 실패 — 필드 하단 danger 문구
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun login(loginId: String, password: String, onApproved: () -> Unit, onPending: () -> Unit) {
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.value = UiState(submitting = true)
            try {
                val result = repository.login(loginId, password)
                _uiState.value = UiState()
                when (result.status) {
                    DriverAccountStatus.APPROVED -> onApproved()
                    DriverAccountStatus.PENDING_REVIEW -> onPending()
                }
            } catch (e: Exception) {
                _uiState.value = UiState(fieldError = "아이디 또는 비밀번호가 맞지 않아요")
            }
        }
    }

    companion object {
        fun factory(repository: DriverRepository) = viewModelFactory {
            initializer { LoginViewModel(repository) }
        }
    }
}

@Composable
fun LoginRoute(
    repository: DriverRepository,
    onApproved: () -> Unit,
    onPending: () -> Unit,
    onSignUp: () -> Unit,
) {
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(repository))
    val state by viewModel.uiState.collectAsState()
    LoginScreen(
        submitting = state.submitting,
        fieldError = state.fieldError,
        onLogin = { loginId, password -> viewModel.login(loginId, password, onApproved, onPending) },
        onSignUp = onSignUp,
    )
}

@Composable
fun LoginScreen(
    submitting: Boolean,
    fieldError: String?,
    onLogin: (loginId: String, password: String) -> Unit,
    onSignUp: () -> Unit,
) {
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // 아이디 형식(영소문자 시작, 4~20자) + 비밀번호 8자 이상 충족까지 CTA disabled
    val canSubmit = isValidLoginId(loginId) && password.length >= 8

    Column(modifier = Modifier.fillMaxSize().background(MoyeotaColor.SurfaceCanvas)) {
        StatusBarMock()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(42.dp))
            Text(text = "모여타 기사", style = MoyeotaType.DisplayXl, color = MoyeotaColor.InkDeep)
            Text(
                text = "택시 기사 전용 앱이에요. 승인된 계정만 로그인됩니다.",
                style = MoyeotaType.BodyLg,
                color = MoyeotaColor.TextBody,
            )
            MoyeotaTextField(
                value = loginId,
                onValueChange = { input ->
                    loginId = input.lowercase()
                        .filter { it in 'a'..'z' || it.isDigit() || it == '_' }
                        .take(20)
                },
                placeholder = "아이디 (영소문자 시작, 4~20자)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                enabled = !submitting,
            )
            MoyeotaTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "비밀번호 (8자 이상)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                errorText = fieldError,
                enabled = !submitting,
            )
            PrimaryCtaButton(
                text = "로그인",
                onClick = { onLogin(loginId, password) },
                enabled = canSubmit,
                loading = submitting,
            )
            NoticeBanner(kind = NoticeKind.INFO, text = "자격 심사가 승인되면 콜을 받을 수 있어요")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "기사 회원가입",
                    style = MoyeotaType.BodyLg.copy(fontWeight = FontWeight.Bold),
                    color = MoyeotaColor.Link,
                    modifier = Modifier
                        .heightIn(min = 60.dp)
                        .clickable(onClick = onSignUp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                // 비밀번호 찾기 — 미연결: 비활성 표기 (탭 동작 없음)
                Text(
                    text = "비밀번호 찾기",
                    style = MoyeotaType.BodyLg,
                    color = MoyeotaColor.TextAsh,
                    modifier = Modifier
                        .heightIn(min = 60.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
