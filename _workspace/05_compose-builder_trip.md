# GRP/D 운행 · 네비게이션 5화면 (D13~D16b) — compose-builder 산출물

## 변경 파일

| 파일 | 내용 |
|------|------|
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/TripGraph.kt` | 스텁 → 실제 화면 등록 (TRIP_PICKUP/BOARDING/DRIVING/FARE) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/TripCommon.kt` | 신규 — `KeepScreenOn`(화면 꺼짐 방지), `TripMap`(MapPlaceholder+MapPill), `formatWon`/`formatKm` |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/PickupScreen.kt` | 신규 — D13 픽업 이동 (PickupViewModel + PickupRoute + PickupScreen) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/BoardingScreen.kt` | 신규 — D14 승객 탑승 확인 (BoardingViewModel + BoardingRoute + BoardingScreen) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/DrivingScreen.kt` | 신규 — D15 운행 중 · 하차 처리 (DrivingViewModel + DrivingRoute + DrivingScreen) |
| `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/trip/FareScreen.kt` | 신규 — D16 최종 요금 입력 + D16b 키패드(화면 내부 전환 뷰) (FareViewModel + FareRoute) |

## 화면 진입 경로

- D13 `trip/pickup` — D11 배차 확정 「픽업 안내 시작」 → `Routes.TRIP_PICKUP` (call 에이전트 담당 연결)
- D14 `trip/boarding` — D13 「도착 · 탑승 확인」 → `Routes.TRIP_BOARDING` (popUpTo TRIP_PICKUP inclusive)
- D15 `trip/driving` — D14 「운행 시작」 → `Routes.TRIP_DRIVING` (popUpTo TRIP_BOARDING inclusive)
- D16 `trip/fare` — D15 마지막 하차 완료(phase=FARE_INPUT) 시 자동 이동 (popUpTo TRIP_DRIVING inclusive)
- D16b — D16 금액 필드 탭 → 화면 내부 전환 뷰(rememberSaveable `keypadOpen`), 확정/뒤로가기로 D16 복귀
- 완료: D16 「요금 확정하기」 → `submitFinalFare` 성공 → `Routes.HOME` (popUpTo HOME inclusive=false, launchSingleTop — 운행 스택 전부 제거)
- D14 승객 전원 노쇼 → 「콜 대기로 복귀」 → `Routes.HOME` (동일 패턴)

## 구현된 공통 규칙

- 전 화면 `getActiveTrip()` 재조회 기반 (화면 간 상태 공유 없음). trip == null이면 ErrorBox「진행 중인 운행이 없어요」
- 운행 플로우 전체 `BackHandler`로 뒤로가기 차단 (D16은 키패드 열림 시 키패드 닫기로 동작)
- D13·D15 `KeepScreenOn` — `LocalView.keepScreenOn` DisposableEffect
- 다크 토큰만 사용(하드코딩 hex 없음), 본문 BodyLg 17sp, 핵심 숫자 NumberLg(28)/NumberXl(34), CTA·키 터치 타깃 60dp+ (키패드 키 74dp)
- 제출 중 CTA loading + 비활성, 액션 실패 시 CTA 위 NoticeBanner(ERROR) + 입력값 보존
- D16b: 오른쪽부터 입력·콤마 표기, +1,000/+500/+100 가산, ⌫ 한 자리 삭제, 전체 지우기, 최대 999,990원 초과 입력 무시, 10원 단위 아닐 때 danger 안내 + CTA 비활성, CTA에 입력값 그대로 표기(「N원으로 확정」)

## 스펙 대비 미구현 항목

- D13: 픽업지 100m 자동 진입 → D14 전환 제안 배너 (위치 스트림 없음 — CTA 수동 진입만), 승객 연락 버튼은 비활성 표기(안심번호 통화·메시지 시트 미연결), GPS 끊김 처리
- D14: 노쇼 5분 경과 후 활성 + 경과 시간 실시간 표시 (도착 알림 시각이 도메인 모델에 없음 — 노쇼 버튼 항상 활성), 노쇼 사유 선택 시트, 코드 3회 불일치 고객센터 안내 (더미 리포지토리가 코드를 검증하지 않아 4자리면 통과), 탑승 코드 표시(모델에 코드 값 없음)
- D15: 하차 처리 200m 이내 제한·원거리 확인 모달 (위치 없음), 긴급 신고 시트(SafetyButton 표시만·no-op), 경유 순서 변경, 앱 강제 종료 후 복구 라우팅(리더 소관)
- D16: 미터기 예상값 ±50% 초과 확인 모달 및 키패드의 「예상 미터기 · 차이」 표시 (ActiveTrip에 예상 요금 필드 없음), 이의 신청·영수증 발행, 심야 할증 자동 계산, 진동 피드백
- D16 요금 구성 미리보기의 호출료는 `CALL_FEE = 3_000` 상수 (더미 리포지토리 기준값 — ActiveTrip에 호출료 필드가 없음. 실연동 시 서버 계산 FareResult로 대체 필요, api-integrator에 `ActiveTrip.callFee` 또는 요금 미리보기 API 시그니처 제안)

## 빌드

- `./gradlew :presentation:compileDebugKotlin` 통과 (아래 검증 로그 참조 — 타 에이전트 파일 컴파일 에러와 병행 중이라 재시도로 확인)
