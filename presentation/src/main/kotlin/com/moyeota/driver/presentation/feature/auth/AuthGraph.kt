package com.moyeota.driver.presentation.feature.auth

import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.moyeota.driver.domain.repository.DriverRepository
import com.moyeota.driver.presentation.core.Routes
import kotlinx.coroutines.launch

// GRP/A 가입 · 로그인 그래프 — D01, D02, D03, D03b, D04, D05, D05b
fun NavGraphBuilder.authGraph(navController: NavHostController, repository: DriverRepository) {
    // D01 · 기사 로그인
    composable(Routes.AUTH_LOGIN) {
        LoginRoute(
            repository = repository,
            onApproved = {
                // 승인 계정 — 로그인 화면을 스택에서 제거하고 홈으로
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.AUTH_LOGIN) { inclusive = true }
                }
            },
            onPending = { navController.navigate(Routes.AUTH_REVIEW_PENDING) },
            onSignUp = { navController.navigate(Routes.AUTH_SIGNUP) },
        )
    }
    // MVP 간소 가입 — 아이디 · 비밀번호 + 차량 · 자격 한 화면 (D02~D04 대체)
    composable(Routes.AUTH_SIGNUP) {
        SignUpRoute(
            repository = repository,
            onBack = { navController.popBackStack() }, // D01 로 복귀 (입력값 보존)
            onSignedUp = { navController.navigate(Routes.AUTH_APPROVED) },
        )
    }
    // D02 · 회원가입 — 휴대폰 인증 (MVP 플로우 미사용, 화면 보존)
    composable(Routes.AUTH_PHONE) {
        PhoneVerifyRoute(
            repository = repository,
            onBack = { navController.popBackStack() },
            onVerified = { navController.navigate(Routes.AUTH_QUALIFICATION) },
        )
    }
    // D03 · 면허 · 자격 자동 조회
    composable(Routes.AUTH_QUALIFICATION) {
        QualificationRoute(
            repository = repository,
            onBack = { navController.popBackStack() }, // D02 로 복귀 (입력값 보존)
            onPassed = { navController.navigate(Routes.AUTH_QUALIFICATION_RESULT) },
            onFailed = { navController.navigate(Routes.AUTH_REVIEW_PENDING) },
        )
    }
    // D03b · 자동 조회 결과
    composable(Routes.AUTH_QUALIFICATION_RESULT) {
        QualificationResultScreen(onNext = { navController.navigate(Routes.AUTH_VEHICLE) })
    }
    // D04 · 차량 · 소속 등록
    composable(Routes.AUTH_VEHICLE) {
        VehicleRoute(
            repository = repository,
            onBack = { navController.popBackStack() }, // D03b 로 복귀
            onRegistered = { navController.navigate(Routes.AUTH_APPROVED) },
        )
    }
    // D05 · 예외 심사 대기
    composable(Routes.AUTH_REVIEW_PENDING) {
        ReviewPendingScreen()
    }
    // D05b · 가입 완료 · 즉시 승인
    composable(Routes.AUTH_APPROVED) {
        val scope = rememberCoroutineScope()
        ApprovedScreen(
            onStartDuty = {
                // 「영업 시작하기」 — 영업중 상태로 전환한 뒤 가입 스택 전부 제거 후 홈으로 (D05b 스펙)
                scope.launch {
                    runCatching { repository.setDutyStatus(online = true) }
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.AUTH_LOGIN) { inclusive = true }
                    }
                }
            },
            onPromotion = { navController.navigate(Routes.CALL_PROMOTION) },
        )
    }
}
