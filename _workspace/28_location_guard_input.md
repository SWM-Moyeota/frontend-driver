# 28. 영업 시작 위치 권한 게이트 + 기본 좌표(강남역) 전송 제거

## 요청 (사용자)
"운행 시작할 때 시스템 위치 권한 있는지 확인하고, 없으면 켜라고 알림 떠야지. 켜져 있으면 당연히 실행되는 거고,
위치 권한 안 키면 당연히 운행 못 하는 거지."

## 실측 원인 (리더 조사)
- `RemoteDriverRepository` 는 `lastLatitude/lastLongitude` 를 **강남역(37.4980 / 127.0276)** 으로 초기화한다.
  `refreshLocation()` 은 `locationSource.current()` 가 null 이면 이 값을 그대로 서버에 보낸다 →
  **한 번도 측위에 성공한 적 없으면 부산에 있어도 서버에는 강남역이 보고된다.**
- `AndroidLocationSource.current()` 는 위치 권한이 없으면 즉시 null 이다.
- 실기기(SM-N981N) 확인: `com.moyeota.driver` 의 ACCESS_FINE/COARSE_LOCATION 이 **granted=false** 인데도
  영업 시작이 그대로 되고 하트비트가 204 로 돌았다 — 잘못됐다는 신호가 화면에도 서버에도 없다.
- 덤: `setDutyStatus` 가 모든 예외를 삼키고 더미 상태로 강등한다 → 서버 goOnline 이 실패해도 화면은 "영업중".

## 리더 확정 계약

### 1. domain (api-integrator)
```kotlin
/** 위치를 확인할 수 없어 영업을 시작할 수 없음 — 화면은 [message] 를 그대로 보여준다 */
class DriverLocationUnavailableException(
    message: String = "위치를 확인할 수 없어요",
) : IllegalStateException(message)
```
`domain/model/` 에 둔다 (CallException 과 같은 자리·같은 방식: IllegalStateException 상속).

### 2. data (api-integrator)
- `DEFAULT_LATITUDE` · `DEFAULT_LONGITUDE` **상수와 그 사용처를 전부 제거**한다. 좌표 캐시는 nullable 로:
  `private var lastCoordinate: DriverCoordinate? = null` (실측 성공 시에만 갱신).
- `refreshLocation()` → 좌표 미상이면 null 을 돌려준다 (더 이상 지어낸 좌표를 만들지 않는다).
- `setDutyStatus(online = true)`:
  1. 좌표 확보 시도 — `locationSource.current()` 가 null 이면 **최대 3초간 300ms 간격으로 재시도**
     (권한을 방금 허용한 직후 첫 fix 를 기다리는 구간).
  2. 그래도 없으면 서버 호출 없이 `DriverLocationUnavailableException` 을 던진다.
     **더미 강등 금지** — 영업 상태로 만들지 않는다.
  3. 서버 `goOnline` 실패(네트워크·4xx·5xx)도 **전파**한다. 지금처럼 삼켜서 "영업중"으로 보이게 하면 안 된다
     (콜이 안 오는데 화면만 영업중인 상태가 이번 버그의 핵심이다).
  - `online = false`(영업 종료)는 기존처럼 관대하게 — 실패해도 더미 상태 전환 유지
    (서버는 하트비트 TTL 로도 자동 오프라인 처리된다).
- 하트비트: 좌표가 없으면 **그 주기를 건너뛴다**(잘못된 좌표 전송 금지). 로그만 남긴다.
- 단위 테스트: 좌표 없음 → 예외 + goOnline 미호출, 좌표 있음 → 정상, 하트비트 스킵, goOnline 실패 전파.

### 3. app (api-integrator)
`AndroidLocationSource`:
- `current()` 호출 시 아직 구독 중이 아니고 권한이 있으면 **`start()` 를 자동 호출**한다
  (홈에서 권한을 갓 허용한 직후에도 곧바로 측위가 시작되도록 — 지금은 MainActivity 경로로만 start 된다).
- `getLastKnownLocation` 결과는 **30분 이내인 것만** 채택한다 (`elapsedRealtimeNanos` 기준).
  실기기에서 5일 지난 lastKnown 이 잡히는 걸 확인했다 — 그런 좌표로 배차되면 안 된다.
  실시간 구독 콜백으로 받은 좌표(cached)는 그대로 신뢰한다.
- 권한 없으면 지금처럼 조용히 null (변경 없음).

### 4. presentation (compose-builder)
홈(D06 휴무 · D07 영업중) — `HomeRoute` / `HomeOffDutyScreen` / `HomeOnDutyScreen`:
- **「영업 시작하기」 게이트**: 탭 시 위치 권한(ACCESS_FINE 또는 COARSE)을 먼저 확인한다.
  - 권한 있음 → 기존대로 `viewModel.startDuty()`
  - 권한 없음 → 시스템 권한 요청(`rememberLauncherForActivityResult(RequestMultiplePermissions)`).
    - 허용 → 이어서 `startDuty()` 자동 진행
    - 거부 → 경고 배너(NoticeKind.WARN 계열): "위치 권한을 허용해야 영업을 시작할 수 있어요" +
      「설정에서 허용」 버튼(`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 인텐트, package uri).
      (영구 거부 시 시스템 다이얼로그가 안 뜨므로 이 경로가 유일한 출구다.)
  - **권한이 없으면 어떤 경우에도 `startDuty()` 를 호출하지 않는다.**
- 영업 시작 실패 처리: `DriverLocationUnavailableException` 이면 전용 문구
  ("위치를 확인하는 중이에요 · 실외에서 잠시 후 다시 시도해 주세요"), 그 밖의 실패는 기존 문구.
  지금은 실패 시 화면 전체가 Error 로 덮이는데, **홈은 유지하고 배너로 알리는 편이 낫다**(재시도 가능하게).
- 영업 중(D07) 권한 회수 대응: 화면 재개 시 권한을 확인해 없으면 경고 배너
  "위치 권한이 꺼져 콜을 받을 수 없어요" + 「설정에서 허용」. (서버는 하트비트 중단 → TTL 로 자동 오프라인된다.)
- 새 의존성 추가 금지 — 권한 확인은 `Context.checkSelfPermission`(minSdk 24) 로 충분하다.

## 범위 밖
- 백그라운드 위치 권한, 정확한 위치/대략적 위치 구분 UI, 위치 서비스(GPS) 자체가 꺼진 경우의 설정 유도
- 승객 앱

## 검증 (리더)
- `./gradlew :app:assembleDebug :data:testDebugUnitTest`
- 실기기(SM-N981N, 권한 거부 상태): 「영업 시작하기」 → 권한 요청 뜸 → 거부 시 영업 시작 안 됨 + 안내 배너
  → 권한 허용 후 영업 시작 성공 → `/dispatch/location` 에 **부산 좌표**가 실려 나가는지 확인(로그)
