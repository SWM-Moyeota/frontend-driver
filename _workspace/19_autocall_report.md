# 19 · 콜 자동 팝업 · 실위치 · 운행 실배선 (구현 + E2E 검증)

작업일 2026-09-05 · 빌드 `./gradlew :app:assembleDebug :data:testDebugUnitTest` 통과(테스트 38)
검증 에뮬레이터 **emulator-5558**(Pixel_6_QA, API 37) — emulator-5554 미접촉 · 백엔드 localhost:8080 기동 상태 유지(재시작/종료 없음)

## 요구 ↔ 결과

| 요구 | 결과 |
|---|---|
| 매칭 성사 시 기사 화면에 콜 수락 팝업 **자동** 표시 | **통과** — 앱 포그라운드에서 CALL_OPENED 수신 즉시 D10 콜 상세 전체화면 전환(증적 `autocall_27_popup_foreground.png`) |
| 콜 화면 실데이터 | **통과** — 부산대학교 정문 → 부산역 · 합승 2인 · 미터기 9,840 + 호출료 3,000 + 보너스 1,500 = 14,340원 (서버 estimateFare 와 일치) |
| 수락/거절 실배선 | **통과** — accept 204 → 서버 party status **DRIVER_ASSIGNED**, taxiDriverId=1 |
| 운행 버튼 실배선 (arrive/board/complete) | **통과** — arrive 204 → board 204 → complete 204 → status **FINISHED** |
| 위치 실전송 | **통과** — goOnline·15초 하트비트가 에뮬레이터 GPS 실좌표 `{"latitude":35.2313…,"longitude":129.0838…}` 전송 (기존 강남역 고정값 제거) |
| 홈 배너 데모 고정값 제거 | **통과** — "지금 들어온 콜 0건 / 1건"이 실제 인입 콜 수를 따라간다 |

## 1. 콜 자동 팝업 (핵심)

### 구조 — `CallAlertBus` (domain, 프로세스 전역 StateFlow)

콜은 "목록에서 찾아가는 것"이 아니라 "즉시 응답해야 하는 인터럽트"다. 서버 콜 TTL 이 짧아
기사가 콜 리스트를 수동으로 여는 동안 마감된다 — 그래서 수신 즉시 화면을 바꾼다.

```
FCM CALL_OPENED ─┐
                 ├→ CallAlertBus.open(partyId) → MainNavGraph 구독 → Routes.callDetail(partyId) 자동 navigate
알림/전체화면 인텐트 탭 ─┘   (진입 후 consume → 중복 진입 차단)
```

- `domain/.../call/CallAlertBus.kt` (신규) — `pending`(대기 콜) · `handled`(진입 완료 콜) StateFlow.
  domain 에 둔 이유: app(FCM 수신)과 presentation(화면)이 모두 의존하는 유일한 공통 모듈.
  이를 위해 `domain/build.gradle.kts` 에 `api(libs.kotlinx.coroutines.core)` 추가.
- `MainNavGraph` — `initialCallPartyId` 파라미터 **제거**하고 버스 구독으로 교체.
  `currentBackStackEntryAsState()` 를 키에 함께 넣어, 로그인 화면에서 받은 콜이 홈 진입 직후 다시 판정되게 했다.
- **자동 전환 억제 규칙** (콜을 가로채면 안 되는 맥락):
  `auth/` 프리픽스(미로그인 — 조회 401) · `trip/` 프리픽스 · `call/assigned`(운행 중) · 같은 콜의 `call/detail/{id}`.

### 포그라운드 / 백그라운드 분기

- `DriverApplication` 이 `ActivityLifecycleCallbacks` 로 `isForeground` 를 추적한다.
- **포그라운드**: 화면이 즉시 전환되므로 **시스템 알림을 띄우지 않는다**.
  (그전엔 콜 화면 위에 같은 내용의 헤드업 알림이 겹쳐 상단을 가렸다 — `autocall_08/16` 대비 `autocall_27`.)
- **백그라운드**: 기존 알림 + **full-screen intent** 추가.
  `setCategory(CATEGORY_CALL)` + `setFullScreenIntent(pendingIntent, true)`,
  매니페스트에 `USE_FULL_SCREEN_INTENT` 권한과 MainActivity `launchMode=singleTop` ·
  `showWhenLocked` · `turnScreenOn` 추가 → 잠금화면 위로 콜 화면이 뜬다.
- `MainActivity` 는 인텐트 extra 를 버스에 실어 보내고(`publishCallAlert`), 소비 후 extra 를 지운다
  (구성 변경 재생성에서 같은 콜로 재진입하지 않도록).
- `CallAlertBus.handled` 를 구독해 이미 화면에 띄운 콜의 시스템 알림을 내린다
  (setAutoCancel 은 "탭했을 때"만 동작해서 자동 전환 경로에서는 알림이 남는다).

### 마감된 콜 안내

`RemoteDriverRepository.acceptCall` 이 **409 CALL_CLOSED** 를 `"이미 마감된 콜이에요 · 다음 콜을 기다려 주세요"`
로 변환하고, `CallDetailViewModel` 이 그 문구를 그대로 배너에 띄운다(기존엔 일반 실패 문구로 뭉갰다).
`declineCall` 의 409 는 성공으로 취급한다(거절하려던 결과가 이미 이뤄짐) + 피드에서 즉시 제거.

## 2. 위치 실전송

- `domain/.../location/DriverLocationSource.kt` (신규) — `fun interface { current(): DriverCoordinate? }`.
- `app/.../AndroidLocationSource.kt` (신규) — 플랫폼 `LocationManager` (play-services-location 미도입).
  GPS + NETWORK 프로바이더 동시 구독(5초/10m)으로 최신 좌표를 캐시, 캐시가 비면 `getLastKnownLocation` 폴백.
  권한 없으면 조용히 무동작 + `current()` = null.
- `MainActivity` 가 `ACCESS_FINE/COARSE_LOCATION` 을 요청하고, 허용 즉시 `start()`.
- `RemoteDriverRepository` — 생성자에 `locationSource` 추가(기본값 null 공급자라 테스트·더미 구동 무영향).
  `refreshLocation()` 이 goOnline · 15초 하트비트 · 콜 상세/수락(픽업 거리·ETA 계산) 시점에 실좌표를 읽는다.
  **측위 실패 시 직전 좌표 유지** — 하트비트가 끊기면 서버 TTL(30초) 만료로 콜 후보에서 사라지므로
  "정확한 좌표 없음"보다 "직전 좌표로라도 살아있음"이 낫다.
- 기존 고정 좌표(강남역)는 **최초 측위 전 폴백**으로만 남았다.

## 3. 콜 목록 · 홈 배너 실카운트

- `RemoteDriverRepository.getCalls()` 가 **더미 콜 3건 병합을 중단**하고 FCM 피드의 실콜만 돌려준다
  (`CallFeed.snapshot()` 추가, `mergedWith` 는 유지). 실콜이 없으면 D09 는 빈 상태("지금 받을 수 있는 콜이 없어요").
- `HomeViewModel` — `poolCallCount`(합승만) → `pendingCallCount`(전체 미처리 콜)로 교체.
  콜 화면에서 거절·만료로 돌아오면 `LifecycleResumeEffect` 가 카운트만 다시 읽는다(로딩 깜빡임 없음).
  이를 위해 presentation 에 `androidx.lifecycle.runtime.compose` 추가.
- `HomeOnDutyScreen` 문구: "지금 들어온 합승 콜 N건" → "지금 들어온 콜 N건",
  0건일 때 부제는 "콜이 들어오면 수락 화면이 자동으로 열려요".

## 4. 운행 플로우 결함 수정 (검증 중 발견)

**증상**: 탑승 확인까지 마쳐도 D15 운행 화면이 "남은 승객 0명"으로 뜨고 「하차 처리」가 영구 비활성 →
요금 화면(D16)에 도달할 수 없어 **complete 호출이 불가능**했다.

**원인**: D15 는 하차 스톱의 `passengerMaskedName` 으로 승객을 찾는데,
`DispatchMappers.toStops()` 가 합승일 때 집계 표기 `"승객 2인"` 을 넣고 승객 이름은 `"승객1"`, `"승객2"` 라 매칭 실패.

**수정**: `toActiveTrip` 전용 `toPassengerStops(passengers)` 신설 — 승객 1명당 픽업 1 + 하차 1 스톱을
승객 이름 그대로 생성(픽업 전부 앞, 하차 전부 뒤 — nextStopIndex 진행 순서와 일치).
콜 상세(D10) 표기용 `toStops()`(출발/도착 2행)는 그대로 유지.

## 5. E2E 검증 (전 구간 실서버 · 실기)

계정: 기사 `qadriver1`/`Passw0rd!`(driverId=1, VERIFIED) · 승객 `dcall1`/`Dcall1!pass`, `dcall2`/`Dcall2!pass` (신규 가입)
GPS: `adb -s emulator-5558 emu geo fix 129.0838 35.2313` (부산대)

| # | 단계 | 실측 |
|---|---|---|
| 1 | 로그인 | login 200 → users/info 200 → drivers/me 200 → **PUT /drivers/fcm-token 204** |
| 2 | 영업 시작 | **POST /dispatch/online 204** body `{"latitude":35.231298…,"longitude":129.083798…}` — 실GPS |
| 3 | 하트비트 | 15초 주기 **POST /dispatch/location 204** 반복, 좌표 동일 (리포트 18 결함 1 재검 완료) |
| 4 | 승객 매칭 | curl: dcall1 `POST /matching/rooms`(부산대→부산역, capacity 2, radius 300) → dcall2 join 200 → status MATCHING |
| 5 | **콜 팝업 자동 표시** | 파티 성사 ~1.5초 후 **콜 상세(D10) 전체화면 자동 전환**. GET `/dispatch/calls/{id}` 200 ×2(FCM 핸들러 + 화면). 카운트다운 13초, 알림 겹침 없음 |
| 6 | 수락 | **POST /dispatch/calls/8/accept 204** → D11 배차 확정(부산대학교 정문 · 승객 2명) → 서버 status **DRIVER_ASSIGNED**, taxiDriverId=1 |
| 7 | 픽업 도착 | 「도착 · 탑승 확인」 → **POST /dispatch/rides/8/arrive 204** → D14 |
| 8 | 탑승 | 승객1·승객2 코드 확인 → **POST /dispatch/rides/8/board 204**(파티 1회) → D15 "경유 2곳 · 남은 승객 2명" |
| 9 | 하차 | 하차 처리 ×2 → D16 요금 입력(9,840원) → 정산 미리보기(청구 12,840 / 수수료 −640 / 기사 정산 13,700) |
| 10 | 운행 종료 | 「요금 확정하기」 → **POST /dispatch/rides/8/complete 204** → 서버 status **FINISHED** → 홈(영업중·0건) 복귀 |

### 증적 (`_workspace/qa/`)

| 파일 | 내용 |
|---|---|
| `autocall_04_online.png` | D07 영업중 — "지금 들어온 콜 **0건**" (데모 고정값 제거 확인) |
| `autocall_16_popup2.png` | 콜 인입 시 자동 전환된 D10 (실데이터) |
| `autocall_17_assigned2.png` | 수락 → D11 배차 확정 |
| `autocall_20_driving2.png` | D15 — 결함 수정 후 "남은 승객 2명" · 하차 처리 활성 |
| `autocall_21/24_fare*.png` | D16 요금 입력 · 정산 미리보기 |
| `autocall_25_complete.png` | 운행 종료 후 홈 복귀 |
| `autocall_27_popup_foreground.png` | **최종형** — 알림 겹침 없는 전체화면 콜 팝업 |
| `autocall_28_after_decline.png` | 콜 만료 복귀 시 홈 배너 "**1건**" (실카운트 동작) |

## 6. 남은 한계 · 후속 요청

### 백엔드 요청 (요구 (b) 백그라운드 즉시성 직결) — **우선순위 높음**

`FcmCallNotifier` 가 **data-only 메시지를 기본(NORMAL) 우선순위로** 보낸다.
FCM 은 백그라운드/캐시 상태 앱에 normal 우선순위 메시지의 전달을 지연시킬 수 있고,
**실측에서 앱 백그라운드 시 CALL_OPENED 가 약 90초 지연 도착했다**(포그라운드는 1~2초).
콜 TTL 을 감안하면 사실상 백그라운드 콜을 놓친다.

```java
MulticastMessage.builder()
    .setAndroidConfig(AndroidConfig.builder()
        .setPriority(AndroidConfig.Priority.HIGH)   // ← 추가 필요
        .build())
    ...
```

클라이언트의 full-screen intent · 알림 경로는 구현·빌드까지 완료했으나,
위 지연 때문에 **백그라운드 즉시 팝업은 실기에서 관찰하지 못했다**(지연 도착 후 앱 복귀 시 자동 전환되는 것은 확인).
알림 탭 → 콜 상세 진입 자체는 리포트 18 §6 에서 실증된 동일 경로다.

### 클라이언트 한계

1. **영업중 백그라운드 하트비트** — 현재 하트비트는 일반 코루틴이라 앱이 캐시/프리즈되면 끊긴다.
   서버 TTL 30초라 백그라운드가 길어지면 콜 후보에서 사라진다. **영업중 포그라운드 서비스**(지속 알림 + 위치)로
   승격이 필요하다 — 별도 작업으로 제안.
2. **지도 중심 고정** — 전 화면의 네이버 지도가 `GangnamCenter` 상수라 기사가 부산에 있어도 강남을 보여준다.
   실좌표 반영은 도메인 모델(CallDetail·ActiveTrip)에 좌표 필드 추가 + 5개 화면 수정이 필요해 이번 범위에서 제외.
3. **수락 카운트다운 15초** — 피그마 D10 스펙값. 서버 콜 후보 TTL 은 5분, 파티 매칭 타임아웃은 3분이라
   서버가 허용하는 시간보다 훨씬 짧다. 시연 중 자주 만료되므로 값 재검토 권장(스펙 결정 필요).
4. **탑승 코드 검증 없음** — 서버에 코드 API 가 없어 4자리면 통과하고, 파티 단위 board 는 첫 확인 시 1회만 호출한다.
5. **complete 의 fare 는 화면 입력값**(미터기 키패드). 데모에서는 서버 estimateFare 와 같은 값을 입력했다.
6. **개별 하차·노쇼는 로컬 전이** — 서버에 해당 API 가 없다(complete 1회로 종료).
7. `CallAlertBus`·`CallFeed` 는 인메모리 — 프로세스 종료 시 소실(알림 탭 경로가 대체 진입로).
8. **CALL_CLOSED 수신 미검증** — 후보 기사가 1명뿐이라 서버가 취소 대상 0명으로 처리(리포트 18 §9와 동일 제약).

## 7. 변경 파일

**신규**
- `domain/.../call/CallAlertBus.kt` — 콜 인입 브로드캐스트 버스
- `domain/.../location/DriverLocationSource.kt` — 위치 공급 계약
- `app/.../AndroidLocationSource.kt` — LocationManager 구현

**수정**
- `domain/build.gradle.kts` — coroutines-core api 노출
- `presentation/build.gradle.kts` — lifecycle-runtime-compose
- `presentation/.../core/MainNavGraph.kt` — 버스 구독 자동 전환 + 억제 규칙 (파라미터 시그니처 변경)
- `presentation/.../feature/home/HomeRoute.kt` · `HomeOnDutyScreen.kt` — 실카운트 + 복귀 시 갱신 + 문구
- `presentation/.../feature/call/CallDetailScreen.kt` — 서버 사유 문구 노출(마감된 콜)
- `data/.../remote/DispatchMappers.kt` — `toPassengerStops` 신설(하차 매칭 결함 수정)
- `data/.../repository/RemoteDriverRepository.kt` — locationSource 주입 · refreshLocation · getCalls 실콜만 · 409 문구
- `data/.../repository/CallFeed.kt` — `snapshot()` 추가
- `app/.../AppContainer.kt` — Context 주입 + locationSource 배선
- `app/.../DriverApplication.kt` — 포그라운드 추적
- `app/.../MainActivity.kt` — 버스 발행 · 위치 권한 · 처리된 콜 알림 해제
- `app/.../DriverFirebaseMessagingService.kt` — 즉시 버스 발행 · 포그라운드면 알림 생략 · full-screen intent
- `app/src/main/AndroidManifest.xml` — USE_FULL_SCREEN_INTENT · singleTop · showWhenLocked · turnScreenOn

## 8. 검증 중 생성된 서버 상태 (인메모리 — 서버 재기동 시 소실)

- 승객 `dcall1`(010-7777-1111) · `dcall2`(010-7777-2222) 신규 가입
- party 7·8 FINISHED(기사 1 배차·완료) · party 6·10 CANCELED(타임아웃 해산) · party 11 MATCHING(해산 예정)
