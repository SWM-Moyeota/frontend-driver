# 28. api-integrator — 영업 시작 위치 게이트 + 기본 좌표(강남역) 전송 제거

지시서: `_workspace/28_location_guard_input.md` 의 **1(domain) · 2(data) · 3(app: AndroidLocationSource)**.
4(presentation)은 compose-builder 담당 — `presentation/` 미수정.

## 백엔드 스펙 확인 (컨트롤러 소스 기준, 실서버 미검증)

원천: `../backend/src/main/java/team/codingforest/moyeota/dispatch/presentation/DriverLocationController.java`

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/v1/dispatch/online` | `LocationReportRequest { latitude, longitude }` (`@Valid`) | 204 / 400 좌표 누락 |
| POST | `/api/v1/dispatch/location` | 동일 | 204 / 400 좌표 누락 |
| DELETE | `/api/v1/dispatch/online` | — | 204 (멱등) |

driverId 는 전부 토큰(`@CurrentDriver`). **엔드포인트·DTO 변경 없음** — 이번 작업은 "무엇을 보내는가"가 아니라
"좌표가 없을 때 무엇을 하는가"의 정책 수정이다. `LocationReportRequestDto` 그대로 사용.

서버 주석 확인: "위치 보고 — 승객의 기사 위치 조회와 **반경 검색**에 쓰인다". 지어낸 좌표가 곧 오배차라는 근거.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `domain/model/DriverLocationUnavailableException.kt` | **신규** — 영업 시작 실패 예외 |
| `domain/location/DriverLocationSource.kt` | KDoc — "null 이면 기본 좌표 폴백" 지침 삭제 |
| `domain/repository/DriverRepository.kt` | `setDutyStatus` KDoc — 시작/종료 실패 정책 명문화 |
| `data/repository/RemoteDriverRepository.kt` | 기본 좌표 제거 · 좌표 대기 · 실패 전파 · 하트비트 스킵 |
| `data/build.gradle.kts` | `testOptions.unitTests.isReturnDefaultValues = true` |
| `app/AndroidLocationSource.kt` | `current()` 자동 `start()` · lastKnown 30분 신선도 게이트 |
| `data/src/test/.../RemoteDriverRepositoryDutyLocationTest.kt` | **신규** — 9 케이스 |

## 1. domain — 예외 시그니처 (presentation 이 catch 할 계약)

```kotlin
package com.moyeota.driver.domain.model

class DriverLocationUnavailableException(
    message: String = "위치를 확인할 수 없어요",
) : IllegalStateException(message)
```

지시서의 클래스명·패키지 그대로. `CallException` 과 같은 자리·같은 방식(IllegalStateException 상속).

## 2. data — RemoteDriverRepository

### 제거된 것
- `const val DEFAULT_LATITUDE = 37.4980` / `DEFAULT_LONGITUDE = 127.0276` (강남역) — **상수와 사용처 전부 삭제**.
- `lastLatitude: Double` / `lastLongitude: Double` → `lastCoordinate: DriverCoordinate? = null` (실측 성공 시에만 갱신).
- 레포 전체에 `37.4980`·`127.0276` 기본값 잔존 없음 (grep 확인).

### 좌표 미상 시 동작

| 경로 | 좌표 있음 | **좌표 미상** |
|---|---|---|
| `setDutyStatus(online = true)` | `POST /dispatch/online` → 영업중 | **`DriverLocationUnavailableException`**. 서버 호출 안 함, 더미 영업 상태로도 전환 안 함 |
| 하트비트 1주기 (15초) | `POST /dispatch/location` | **그 주기를 건너뜀**. `Log.w("MoyeotaDriverLocation", "위치 미확인 — 하트비트 1주기 건너뜀")` |
| 콜 요약/상세/수락/운행복구 거리·ETA | 실좌표로 haversine | `driverLat/Lng = null` → 기존 `haversineKmOrZero` 가 0.0 (매퍼 이미 `Double?` 지원, 변경 불필요) |

### 영업 시작 좌표 대기
`awaitCoordinate()` — `locationSource.current()` 가 null 이면 **300ms 간격으로 최대 3초** 재시도 후 포기.
권한을 방금 허용한 직후 첫 GPS fix 를 기다리는 구간. 상수 `LOCATION_WAIT_TIMEOUT_MS = 3_000L`,
`LOCATION_WAIT_INTERVAL_MS = 300L`. 테스트가 줄일 수 있도록 생성자 파라미터
`locationWaitTimeoutMs: Long = LOCATION_WAIT_TIMEOUT_MS` 추가 (프로덕션 주입 불필요 — 기본값 사용).

### 실패 전파 정책 (이번 수정의 핵심)

```kotlin
override suspend fun setDutyStatus(online: Boolean): HomeSummary =
    if (online) goOnDuty() else goOffDuty()
```

- `goOnDuty()` — 좌표 실패·서버 실패(네트워크/4xx/5xx) **모두 전파**. `fallback.setDutyStatus(true)` 는
  goOnline 성공 후에만 호출 → 어떤 실패에서도 화면이 "영업중"이 되지 않는다.
- `goOffDuty()` — **기존 관대 정책 유지**. `stopHeartbeat()` 후 goOffline 실패를 삼키고 휴무로 전환
  (서버는 하트비트 TTL 30초로 자동 오프라인).

### 하트비트
`reportLocationTick()` 을 `internal` 로 분리 — 루프가 15초 delay 후 이걸 호출한다.
단위 테스트가 15초를 기다리지 않고 한 주기를 직접 돌린다.

## 3. app — AndroidLocationSource

- `current()` 가 권한이 있는데 아직 구독 전이면 **`start()` 를 자동 호출**한다
  (기존엔 MainActivity 경로로만 start — 홈에서 권한을 갓 허용하면 구독이 열리지 않았다).
  `start()` 에 `@Synchronized` 추가 — 이제 하트비트 IO 스레드에서도 호출된다.
- `getLastKnownLocation` 결과는 **30분 이내만 채택** (`SystemClock.elapsedRealtimeNanos()` 기준 단조 시계).
  실기기에서 5일 지난 lastKnown 이 잡히던 문제.
- **lastKnown 결과를 `cached` 에 심지 않는다** (기존 구현은 심었다). 심으면 낡은 좌표가 영구히
  신선한 척하며 신선도 게이트를 우회한다. `cached` 는 실시간 구독 콜백 전용.
- 권한 없으면 기존처럼 조용히 null (변경 없음).

## 4. Repository 시그니처 — compose-builder 통지 사항

**시그니처 자체는 불변**: `suspend fun setDutyStatus(online: Boolean): HomeSummary`.
바뀐 것은 **예외 계약**이다:

1. `setDutyStatus(online = true)` 는 이제 **던진다**:
   - `com.moyeota.driver.domain.model.DriverLocationUnavailableException` — 좌표 미상. `message` 기본값 `"위치를 확인할 수 없어요"`
   - 그 밖의 예외(`HttpException`, `IOException` 등) — 서버 goOnline 실패, 그대로 전파
   - 두 경우 모두 **영업 상태로 전환되지 않는다** (이전엔 조용히 더미 ONLINE 으로 강등됐다)
2. `setDutyStatus(online = false)` — **변경 없음**. 실패해도 휴무 전환 (`OffDutyConfirmRoute` 영향 없음).
3. **`startDuty()` 는 최대 3초 블로킹될 수 있다** (첫 fix 대기). 화면에 로딩 상태가 필요하다.

## 5. 리더 확인 요청 — 남은 이슈 2건

1. **`AuthGraph.kt:83` (D05b 가입 완료 → 「영업 시작하기」)**
   `runCatching { repository.setDutyStatus(online = true) }` 후 결과와 무관하게 HOME 으로 이동한다.
   새 정책에서 위치 권한이 없으면 이 호출이 조용히 실패하고 기사는 **휴무 상태로** 홈에 착지한다
   (이전엔 가짜 강남역 좌표로 "영업중"이 됐다). 동작은 더 정확해졌지만 **사용자에게 아무 안내가 없다** —
   홈의 권한 게이트로 흡수할지 compose-builder 판단 필요. presentation 범위라 손대지 않았다.

2. **좌표 캐시 만료 정책 미지정 (지시서 범위 밖 — 의도 확인 필요)**
   `lastCoordinate` 는 한 번 실측에 성공하면 만료되지 않는다. 즉 **영업 중 권한을 회수하면**
   `AndroidLocationSource.cached` 와 이 캐시가 모두 남아 하트비트가 계속 **마지막 실측 좌표**를 보고한다.
   지시서 4번의 "권한 회수 → 하트비트 중단 → TTL 자동 오프라인" 기대와는 다르다.
   지시서가 "실측 성공 시에만 갱신"·"cached 는 그대로 신뢰"로 명시해 그대로 구현했다.
   이번 버그(한 번도 측위 못 한 단말이 강남역 보고)와는 무관하고, 최소한 **그 기사의 실제 좌표**이긴 하다.
   만료가 필요하면 별도 작업으로 분리 권장.

## 6. 테스트

신규: `data/src/test/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepositoryDutyLocationTest.kt` (9 케이스)

- 좌표 미상 → `DriverLocationUnavailableException` + `goOnline` 미호출 + 더미 OFFLINE 유지
- 측위 성공 → 그 좌표(부산 35.1796/129.0756)로 goOnline + ONLINE
- 첫 fix 지연(2회 null 후 성공) → 대기 구간 안에서 시작 성공
- 대기 시간 소진 → 실패 + 서버 미호출
- goOnline 500 / SocketTimeout → **전파** + 더미 OFFLINE 유지 (강등 금지)
- 영업 종료 503 → 관대하게 OFFLINE 전환
- 하트비트: 좌표 미상 → `reportLocation` 미호출
- 하트비트: 측위 성공 후 실패 주기 → 마지막 실측 좌표 유지, **37.4980 은 어떤 주기에도 안 나감**
- 하트비트 전송 실패 → 삼킴(루프 생존)

기존 테스트 중 강남역 기본 좌표나 "goOnline 실패 시 강등"에 의존하는 케이스는 **없었다**
(기존 `37.4980` 출현은 전부 출발지 좌표 픽스처). 갱신 불필요.

```
./gradlew :data:testDebugUnitTest --rerun-tasks
→ BUILD SUCCESSFUL, 112 tests, 0 failures/errors
```

`data/build.gradle.kts` 에 `testOptions.unitTests.isReturnDefaultValues = true` 추가 —
`reportLocationTick()` 의 `android.util.Log.w` 가 JVM 단위 테스트에서 `Stub!` 로 터지는 것을 막는다.
(logcat 태그는 `AndroidLocationSource` 와 같은 `MoyeotaDriverLocation` — 실기 검증 시 한 번에 본다.)

## 7. 미검증

- `:app:compileDebugKotlin` / `:app:assembleDebug` — presentation 동시 작업 중이라 미실행 (리더가 최종 빌드).
  `app/AndroidLocationSource.kt` 는 육안 검토만.
- 실서버 호출 미검증 (컨트롤러 소스 기준). 실기 검증은 지시서 "검증(리더)" 항목 참조 —
  권한 거부 상태에서 영업 시작 불가, 허용 후 `/dispatch/location` 에 **부산 좌표**가 실리는지 로그 확인.
