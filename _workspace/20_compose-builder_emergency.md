# 20 · compose-builder — D15 긴급 신고 흐름

## 개요
D15(운행 중) 신고 버튼을 실제 긴급 신고 흐름으로 연결. 3초 홀드 → 신고 저장 발사 + 112 다이얼러 즉시 열기(저장 성패 무관, 통화 최우선) → 앱 복귀 시 통화 여부 확인 다이얼로그 → `confirmEmergencyCall(called)` 기록.

## 상태 전이도 (DrivingViewModel.EmergencyPhase)

```
IDLE
 │  SafetyButton 3초 홀드 완료
 │  → onEmergencyHold(tripId): reportEmergency 발사(비동기) + 즉시 ACTION_DIAL tel:112
 ▼
REPORTED ── reportEmergency 실패 시 reportSaveFailed=true (상태 전이는 그대로)
 │  다이얼에서 앱 복귀 (Lifecycle ON_RESUME → onScreenResumed)
 ▼
AWAITING_CALL_RESULT ── "112와 실제로 통화하셨나요?" 다이얼로그 표시
 │   ├─ reportSaveFailed면 다이얼로그 내 NoticeBanner(ERROR) "신고 정보 저장에 실패했어요 · 통화 여부는 기록됩니다"
 │   ├─ confirmEmergencyCall 실패 → confirmError 표시, 다이얼로그 유지(재선택 가능)
 │   └─ 뒤로가기/외부 탭으로 안 닫힘. 단 화면 전이(마지막 하차 → D16) 시엔 화면과 함께 소멸 허용
 │  「통화했어요」(true) / 「통화 안 했어요」(false) → confirmEmergencyCall 성공
 ▼
DONE ── 다이얼로그 닫힘 + NoticeBanner(SUCCESS) "신고가 접수됐어요" 3초 표시 후 자동 소멸
        (DONE 이후 재홀드 가능 — 새 신고로 IDLE부터 재시작)
```

부가: 짧은 탭 → `onShortPress` → 화면 내 NoticeBanner(INFO) "3초간 길게 누르면 신고돼요" 2.5초 표시(Toast 미사용).

## 변경 파일
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/DrivingScreen.kt` (단일 파일)
  - `DrivingViewModel`: `EmergencyPhase`/`EmergencyUiState` + 별도 StateFlow(`emergency`), `onEmergencyHold`, `onScreenResumed`, `confirmEmergencyCall`, `dismissReportedBanner`
  - `DrivingRoute`: `LifecycleEventObserver`(ON_RESUME) 복귀 감지, `LocalContext.startActivity(ACTION_DIAL tel:112)` — 권한 불필요(번호만 입력, 발신은 사용자), 홀드 안내·접수 배너 타이머
  - `DrivingScreen`: SafetyButton 새 시그니처(`onHoldComplete`/`onShortPress`) 재배선, 배너 2종 추가
  - `EmergencyCallResultDialog`(private): 다크 토큰 커스텀 다이얼로그 — SurfaceCard 배경, 질문 BodyLg, PrimaryCtaButton/SecondaryButton 각 60dp, `DialogProperties(dismissOnBackPress=false, dismissOnClickOutside=false)`

designsystem·domain·data·Routes·MainNavGraph 무변경. 하드코딩 hex 없음.

## 빌드
`./gradlew :presentation:compileDebugKotlin --console=plain` → BUILD SUCCESSFUL (:app 전체 빌드는 data 병렬 작업 중이라 미실행)

## QA 재현 경로
1. 운행 중 화면(D15) 진입: 로그인 → 홈(D06) 영업 시작 → 콜 수락(D10) → 배차(D11) → 픽업(D13) → 탑승 확인(D14) 「운행 시작」 → D15
2. 신고 버튼 짧은 탭 → "3초간 길게 누르면 신고돼요" 배너 2.5초 표시 확인 (Toast 아님)
3. 신고 버튼 3초 홀드(게이지 차오름) → 112가 입력된 다이얼러 열림 확인 (자동 발신 안 됨)
4. 뒤로가기로 앱 복귀 → "112와 실제로 통화하셨나요?" 다이얼로그 표시 확인. 뒤로가기·외부 탭으로 안 닫히는지 확인
5. 「통화했어요」 또는 「통화 안 했어요」 선택 → 다이얼로그 닫힘 + "신고가 접수됐어요" SUCCESS 배너 3초 표시
6. 실패 경로: reportEmergency 실패 주입 시 → 다이얼러는 그대로 열리고, 복귀 다이얼로그에 ERROR 배너("신고 정보 저장에 실패했어요…") 표시. confirmEmergencyCall 실패 주입 시 → 다이얼로그 유지 + "통화 여부를 저장하지 못했어요 · 다시 선택해 주세요", 재선택 가능
7. 다이얼로그 미응답 상태에서 마지막 하차 처리 → D16 전이 시 다이얼로그 함께 소멸(전이 차단 없음) 확인
