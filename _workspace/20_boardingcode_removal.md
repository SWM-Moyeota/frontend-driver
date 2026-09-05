# 20 · 탑승 코드 입력 제거 (D14)

사용자 결정: "탑승 코드 부분이 불필요해서 그냥 없애는 방향으로 가자"
배경: 서버에 코드 발급/검증 API가 없어 아무 4자리나 통과하는 무의미한 단계였음(리포트 19 §6-4). 승객 앱에도 코드 표시 화면 없음.

## 1. 변경 내용

| 파일 | 변경 |
|---|---|
| `presentation/.../feature/trip/BoardingScreen.kt` | 승객 카드에서 코드 입력 필드·"승객이 말한 4자리 코드를 입력해 주세요" 안내·「코드 확인」 버튼 제거 → **「탑승 확인」** 버튼으로 대체(항시 활성, 처리 중 잠금만 유지). 「노쇼 처리」 유지. `code` rememberSaveable 상태·자릿수 검증·`MoyeotaTextField`/`KeyboardOptions` 임포트 제거. VM `confirmBoarding(passengerId, code)` → `confirmBoarding(passengerId)` |
| `domain/.../repository/DriverRepository.kt` | `confirmBoarding(tripId, passengerId, boardingCode)` → `confirmBoarding(tripId, passengerId)` + 주석(코드 검증 API 부재로 제거) |
| `data/.../repository/RemoteDriverRepository.kt` | 시그니처 동기화. **파티 단위 board 1회 호출 로직 그대로 유지**(첫 탑승 확인 시에만 `POST /dispatch/rides/{partyId}/board`) |
| `data/.../repository/DummyDriverRepository.kt` | 시그니처 동기화(로컬 전이 로직 동일) |

동작 전이는 기존 코드 확인 성공과 동일: 대기 → 탑승 배지, 탑승 1명 이상이면 「운행 시작」 활성, 전원 노쇼 시 콜 대기 복귀. 다른 화면·FCM·위치 로직 변경 없음.

## 2. 빌드 · 테스트

```
./gradlew :app:assembleDebug :data:testDebugUnitTest   → BUILD SUCCESSFUL
```
코드 검증 관련 단위 테스트는 원래 존재하지 않아 수정 대상 없음(`confirmBoarding` 참조 테스트 0건 확인).

## 3. 실기 E2E (emulator-5558 · 실서버 localhost:8080)

계정: 기사 `qadriver1`(driverId=1) · 승객 `dcall2` / GPS `geo fix 129.0838 35.2313`(부산대)

| # | 단계 | 실측 |
|---|---|---|
| 1 | APK 재설치 → 재로그인 → 영업 시작 | 영업중 · 콜 0건 |
| 2 | dcall2 `POST /matching/rooms` (부산대→부산역광장, **capacity 1**, radius 300) | roomId=4, 즉시 MATCHING |
| 3 | 콜 팝업 자동 표시 | D10 「단독 콜 · 승객 1명」 전체화면 자동 전환, 카운트다운 내 수락 |
| 4 | 수락 → 픽업 이동 → 「도착 · 탑승 확인」 | D11 배차 확정 → D13 → D14 |
| 5 | **D14 신규 UI** | 코드 입력란·안내문·「코드 확인」 없음. 「노쇼 처리」+「탑승 확인」 2버튼 (`boarding_01_no_code_ui.png`) |
| 6 | **「탑승 확인」 탭 (코드 입력 없이)** | 대기 → **탑승** 배지 · 「운행 시작」 활성 (`boarding_02_boarded_badge.png`). 서버 status **IN_RIDE**, taxiDriverId=1 — board 1회 호출 확인 |
| 7 | 운행 시작 → 하차 처리 → 요금 18,300원 입력 | D15 → D16 정산 미리보기(청구 21,300 / 수수료 −1,060 / 기사 정산 20,240) |
| 8 | 「요금 확정하기」 | 서버 status **FINISHED** → 영업중 홈 복귀 (`boarding_03_complete_home.png`) — 서버 상태 정리 완료 |

증적: `_workspace/qa/boarding_00_call_popup.png` ~ `boarding_03_complete_home.png`

## 4. 비고

- 승객별 탑승 체크·노쇼는 종전대로 로컬 상태 전이(서버에 승객 단위 API 없음). 서버 호출은 파티 단위 board 1회 + arrive/complete 그대로.
- 「탑승 확인」은 처리 중(`processingPassengerId`) 잠금 외 항시 활성 — 코드 자릿수 조건이 사라진 것 외 활성화 규칙 변화 없음.
