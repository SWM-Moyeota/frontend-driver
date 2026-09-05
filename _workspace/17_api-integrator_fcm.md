# 17 api-integrator — FCM 수신 (콜 푸시) 연동

빌드/테스트: `./gradlew :app:assembleDebug :data:testDebugUnitTest --console=plain` 통과
(테스트 38 = 기존 33 + CallFeed 5).
스펙 원천: `../backend` `dispatch/infrastructure/FcmCallNotifier.java`,
`driver/interfaces/DriverController.java`(fcm-token PUT/DELETE, 204),
`driver/application/dto/RegisterFcmTokenRequest.java`(`{token}` @NotBlank) — 전부 직접 확인.

**⚠ 실푸시 수신 미검증** — FCM 실제 발송(E2E)은 별도 검증 필요 (실기·서버·FCM 콘솔 필요, §6).

## 1. 푸시 payload ↔ 처리 매핑 (FcmCallNotifier 기준, data-only 메시지)

| payload | 처리 (`DriverFirebaseMessagingService.onMessageReceived`) |
|---|---|
| `{type:"CALL_OPENED", partyId, departure, destination}` | `repository.handleCallOpened(partyId)` → 피드 추가 + 알림 표시. 제목 "새 콜 도착", 내용 "출발 {departure} → {destination}" (+상세 조회 성공 시 " · 예상 {expectedTotal}원"). 알림 id = `partyId.hashCode()`, 탭 인텐트 = MainActivity + extra `callPartyId` (requestCode 도 partyId.hashCode() — 콜별 extras 분리, FLAG_UPDATE_CURRENT\|IMMUTABLE) |
| `{type:"CALL_CLOSED", partyId}` | `repository.handleCallClosed(partyId)`(피드 제거) + 같은 id 알림 cancel |
| 그 외 (`notification` 또는 data title/body) | 승객 앱과 동일한 일반 알림 폴백 (body 없으면 무시) |

- `handleCallOpened` 실패(미로그인 401·이미 마감 409 CALL_CLOSED·네트워크)여도 **알림은 payload 텍스트로 표시**된다 — 예상 수익만 빠진다.
- 서비스 코루틴: **프로세스 수명 companion 스코프**(SupervisorJob+IO) 사용 — FirebaseMessagingService 는 콜백 반환 직후 파괴될 수 있어 인스턴스 스코프면 상세 조회가 중간 취소된다.

## 2. 토큰 등록 시점 (PUT /api/v1/drivers/fcm-token, body `{token}`, 204)

토큰 유입 경로는 3곳 모두 `DriverFirebaseMessagingService.handleToken(application, token)` 단일 경로:
1. `onNewToken`/`onRegistered` (신규 발급·회전)
2. `DriverApplication.onCreate` 의 `FirebaseMessaging.getInstance().token` 명시 조회 (기존 설치분)

Repository(`registerFcmToken`) 동작:
- **미로그인**(TokenStore 비어있음): `lastFcmToken` 에 **pending 보관**만 하고 리턴.
- **로그인 상태**: 즉시 PUT — 실패(404 DRIVER_NOT_REGISTERED 포함)는 조용히 무시(fire-and-forget).
- **login()·signUp() 성공 직후**: `flushFcmTokenAsync()` 가 보관 토큰을 repository 내부
  `fcmScope`(SupervisorJob+IO)로 비동기 전송 — 로그인 응답을 막지 않는다.
- `lastFcmToken` 은 성공해도 지우지 않는다 — 재로그인·계정 전환 시 자동 재전송 원천.
- `DELETE /drivers/fcm-token` 은 API 선언만 유지(`DriverApi.removeFcmToken`) — 로그아웃 시 삭제 호출은
  이번 범위 밖 (로그아웃해도 서버에 토큰이 남아 푸시가 올 수 있음 — 후속 결정 필요).

## 3. 콜 피드 (`data/repository/CallFeed.kt` — RemoteDriverRepository 필드)

- LinkedHashMap(partyId→CallSummary) + synchronized — FCM(IO 스코프) 추가/제거 ↔ 화면 조회 경합 직렬화.
- `getCalls()` = **피드 실콜(최신 도착 순) 앞** + 더미 목록 뒤, 중복 id 는 실콜 우선.
- 피드 제거 시점 3곳: CALL_CLOSED 수신 / `acceptCall` 성공(재노출 방지) / (재수신 시 최신으로 교체).
- 인메모리 — 프로세스 종료 시 소실. 콜 상세는 기존 `getCallDetail`(GET /dispatch/calls/{partyId}) 재사용.
- CallSummary 채움 규칙은 기존 `toCallSummary` 그대로 (callFee 3,000 고정, 합승 보너스 1,500 등 — 09 산출물).

## 4. Repository 시그니처 (compose-builder 경계면 — 기존 메서드 전부 불변, 3개 추가)

```kotlin
suspend fun registerFcmToken(token: String)                    // 예외 없음(내부 무시)
suspend fun handleCallOpened(partyId: String): CallSummary?    // 실패 시 null
suspend fun handleCallClosed(partyId: String)
```
Dummy: registerFcmToken/handleCallClosed no-op, handleCallOpened 는 더미 콜 첫 건 반환.

**MainNavGraph 시그니처 변경** (최소 수정, Routes.kt 무변경):
`MainNavGraph(repository, initialCallPartyId: String? = null)` — 알림 탭 partyId 가 있고 현재 목적지가
`auth/` 프리픽스가 아닐 때 1회 `Routes.callDetail(partyId)` navigate (`rememberSaveable` 로 소비값 기억
— 재조합·구성변경 재트리거 방지). 미로그인(AUTH_*)이면 무시 — 콜 상세 조회가 401 로 깨지기 때문.
MainActivity 는 `callPartyIdFromPush`(mutableStateOf) 로 onCreate extra + `onNewIntent` 둘 다 반영.

## 5. 변경 파일

- `build.gradle.kts`(루트) — google-services apply false / `app/build.gradle.kts` — 플러그인 적용 + firebase-bom·messaging
- `domain/.../repository/DriverRepository.kt` — FCM 3메서드 추가
- `data/.../repository/CallFeed.kt` — 신규 (스레드 세이프 인메모리 피드)
- `data/.../repository/RemoteDriverRepository.kt` — §2·§3 (lastFcmToken·fcmScope·callFeed, getCalls 병합, acceptCall 피드 소거)
- `data/.../repository/DummyDriverRepository.kt` — 3메서드 no-op/더미 구현
- `app/.../DriverFirebaseMessagingService.kt` — 신규 (§1) + AndroidManifest 서비스 등록(MESSAGING_EVENT)
- `app/.../DriverApplication.kt` — 채널 생성 + 앱 시작 토큰 조회
- `app/.../MainActivity.kt` — extra 읽기 + onNewIntent
- `presentation/.../core/MainNavGraph.kt` — initialCallPartyId 1회 소비 navigate
- `data/src/test/.../CallFeedTest.kt` — 신규 5 (병합 순서·중복 제거·재수신 교체·remove)

## 6. 미검증 항목 (qa-verifier E2E 필요)

1. **실푸시 수신** — 서버 `fcm.service-account-path` 설정 + 기사 온라인 + 승객 파티 생성으로
   CALL_OPENED 실수신 → 알림 표시 → 탭 → D10 진입 확인 (전 구간 정적 연동만 검증됨).
2. 토큰 등록 실서버 확인 — 로그인 후 서버 로그에서 `PUT /drivers/fcm-token` 204 확인.
3. CALL_CLOSED 로 알림이 실제 사라지는지 + 콜 목록(D09) 재진입 시 피드에서 빠졌는지.
4. 미로그인 상태 알림 탭 — 로그인 화면 유지(navigate 억제)되는지.
5. google-services.json 은 배치돼 있으나 실빌드 APK 의 FCM 초기화(토큰 발급)는 에뮬레이터
   (Google Play 이미지)에서만 가능 — verify 스킬 경로로 확인 필요.
