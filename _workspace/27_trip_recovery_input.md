# 27. 운행 중 앱 종료 → 재실행 복구 (세션 유지 + 운행 단계 복원)

## 요청 (사용자)
"운행 중에 어플리케이션 종료하면 운행 중인 걸 다시 확인할 수 없다" → 복구되게 수정.

## 현상 원인 (리더 조사 결과)
1. **토큰이 인메모리** — `data/remote/auth/TokenStore.kt` 는 필드 2개(access/refresh)만 들고 있고 디스크 저장이 없다.
   프로세스가 죽으면 로그인 자체가 풀려 `auth/login`(Routes.AUTH_LOGIN, NavHost startDestination)부터 다시 시작한다.
2. **운행 상태가 인메모리** — `RemoteDriverRepository.remoteTrip` 필드가 유일한 활성 운행 보관소이고
   `getActiveTrip()` 은 이 캐시만 본다. 재실행하면 null → 운행 화면 복귀 경로가 아예 없다.
3. **서버에 "이 기사의 진행 중 운행" 조회 API가 없다** — 백엔드에 `hasOngoingRide(driverId)` 가 있지만
   내부 가드(accept 시 409 DRIVER_ALREADY_RIDING)로만 쓰이고 노출되지 않는다.
   → 그래서 **partyId 를 단말에 저장**해 두고 재실행 시 그 파티를 다시 조회하는 방식으로 복구한다.

## 서버에서 쓸 수 있는 것 (백엔드 수정 불필요)
| 엔드포인트 | 쓸 수 있나 | 비고 |
|---|---|---|
| `GET /api/v1/matching/rooms/{partyId}` (PartyDetailResult) | **복구 원천으로 사용** | 가드 없음. `status`(MATCHING/DRIVER_ASSIGNED/IN_RIDE/FINISHED/CANCELED) · `taxiDriverId` · 좌표 · 장소명 · `members[].nickname` · `currentMembers` · `estimateFare` 전부 포함. 26번에서 만든 `PartyMembersApi` 가 이미 이 URL 을 친다 |
| `GET /api/v1/dispatch/calls/{partyId}` (PartySummary) | **사용 불가** | `DispatchService.getDetailRoom` 이 `isCallOpen`(콜 후보 집합) 을 요구 — 수락 후에는 CALL_CLOSED 로 떨어진다 |
| `GET /api/v1/drivers/me` | 세션 유효성 확인용 | 401 이면 재발급 실패 = 재로그인 필요 |

## 리더 확정 계약

### 1. domain (api-integrator)
```kotlin
/** 앱 재실행 직후 1회 호출 — 저장된 세션·진행 중 운행을 복구한다 */
suspend fun restoreSession(): RestoredSession

data class RestoredSession(
    val loggedIn: Boolean,
    /** 진행 중이던 운행 (없으면 null). 복구 성공 시 리포지토리 캐시에도 심어 getActiveTrip() 이 같은 값을 준다 */
    val ongoingTrip: ActiveTrip?,
)
```
- `DriverRepository` 에 추가. Dummy 구현도 필요(더미는 `RestoredSession(loggedIn = false, ongoingTrip = null)` 로 충분 — 더미 구동 시 기존처럼 로그인 화면부터).

### 2. data (api-integrator)
- **영속 포트**: data 에 인터페이스 1개(예: `DriverSessionStorage`) — 토큰 쌍 + 활성 partyId 읽기/쓰기/삭제.
  구현은 app 모듈(Context 소유)이 담당. `DriverLocationSource`(domain 인터페이스 ↔ app 구현) 와 같은 패턴.
- **TokenStore 영속화**: 기존 인메모리 동작은 유지하되 storage 를 붙일 수 있게 한다
  (예: `attachPersistence(storage)` — 붙이는 순간 저장된 쌍을 메모리로 로드하고, 이후 `update`/`clear` 가 write-through).
  `NetworkModule.tokenStore` 가 이미 싱글턴 `val` 이므로 AppContainer 생성 시점에 붙인다.
  ⚠️ 평문 SharedPreferences 저장이면 주석에 사유(MVP·앱 전용 저장소)와 후속(암호화) 과제를 남길 것.
- **활성 운행 저장**: `acceptCall` 성공 시 partyId 저장, `submitFinalFare`(운행 완료) 성공 시 삭제, `logout` 시 토큰과 함께 삭제.
- **파티 상세 조회 확장**: 26번의 `PartyMembersApi`/`PartyMemberDtos` 를 확장(또는 형제 DTO 추가)해
  `status`·`taxiDriverId`·좌표·장소명·`currentMembers`·`estimateFare`·`estimateTime`·`members[].nickname` 을 파싱한다.
  **`route`(대형 폴리라인)는 파싱하지 말 것** (ignoreUnknownKeys 로 무시).
- **`restoreSession` 구현**:
  1. 토큰 없음 → `RestoredSession(false, null)`
  2. `driverApi.getMe()` 로 세션 확인 — 401/세션 만료면 저장된 세션 전부 비우고 `(false, null)`.
     이때 얻은 `name`(닉네임)은 기존 `cachedDriverName` 에 채워 홈 이름이 바로 실데이터로 뜨게 한다.
  3. 저장된 partyId 없음 → `(true, null)`
  4. 파티 상세 조회 → 판정:
     - `taxiDriverId` 가 내 기사 id 가 아니면 → 저장 삭제, `(true, null)`
     - `status == IN_RIDE` → ActiveTrip(phase = `TripPhase.IN_TRIP`, 전원 boarded, nextStopIndex = 첫 DROPOFF) 복원
     - `status == DRIVER_ASSIGNED` → ActiveTrip(phase = `TripPhase.ASSIGNED`) 복원
     - 그 외(FINISHED·CANCELED·MATCHING) → 저장 삭제, `(true, null)`
  5. 복원한 ActiveTrip 을 `remoteTrip` 에 넣어 이후 `getActiveTrip()`·`startRide()`·`submitFinalFare()` 가 이어서 동작하게 한다.
  6. 좌표·승객 이름 매핑은 기존 규칙 재사용 — 승객 이름은 `members[].nickname`(26번 규칙: null/공백/중복이면 "승객N" 폴백),
     `passengers` 와 `stops.passengerMaskedName` 을 함께 채운다. 좌표는 `GeoPoint`(23·24번) 로 채워 지도가 바로 실좌표를 그린다.
  7. **네트워크 실패는 저장을 지우지 않는다** — 2회까지 재시도하고 그래도 실패하면 `(true, null)` 로 홈에 착지
     (저장은 남아 있어 다음 실행에서 복구된다). 세션 만료(401)일 때만 저장을 비운다.
- 단위 테스트: 상태별 판정(IN_RIDE/DRIVER_ASSIGNED/FINISHED/다른 기사) · 토큰 없음 · 401 정리 · 네트워크 실패 시 저장 보존.

### 3. app (api-integrator)
- `DriverSessionStorage` 의 Android 구현(SharedPreferences, 앱 전용 파일) 신규 파일로 추가.
- `AppContainer` 에서 생성해 `NetworkModule.tokenStore` 에 붙이고 `RemoteDriverRepository` 에 주입.
- **`MainActivity.kt` 는 건드리지 말 것** (리더가 소유).

### 4. presentation (리더 직접 — 공유 파일 소유 규칙)
- `MainNavGraph` 에 시작 게이트 추가: 첫 컴포지션에서 `restoreSession()` 1회 호출 → 결과로 startDestination 결정.
  - `loggedIn == false` → `Routes.AUTH_LOGIN` (현행과 동일)
  - `ongoingTrip == null` → `Routes.HOME`
  - `ongoingTrip.phase == IN_TRIP` → `Routes.TRIP_DRIVING` (D15)
  - 그 외(ASSIGNED) → `Routes.TRIP_PICKUP` (D13)
    ※ 서버 상태로는 D11(배차 확정)과 D13(픽업 이동)을 구분할 수 없다. D11 은 스쳐 지나가는 확인 화면이므로
      현장 기사에게 의미 있는 D13 으로 복원한다(로컬 단계 저장은 하지 않는다 — 복잡도 대비 이득 없음).
  - 복구 중에는 로딩 표시. 복구 실패(예외)는 로그인 화면으로 폴백하지 말고 `AUTH_LOGIN` 판정 규칙 그대로 따른다.
- `Routes.kt` 변경 없음.

## 범위 밖 (이번에 하지 않음)
- 백엔드 "내 진행 중 운행" 조회 API 신설 (있으면 단말 저장 없이도 복구 가능 — 후속 제안)
- 토큰 암호화 저장, 마이페이지/로그아웃 화면, 운행 중 강제 종료 방지

## 검증 (리더)
- `./gradlew :app:assembleDebug :data:testDebugUnitTest`
- 에뮬레이터: 콜 수락 → D13/D15 진입 → **앱 강제 종료(`am force-stop`)** → 재실행 →
  (a) 로그인 화면 없이 (b) 운행 화면으로 복귀 (c) 이어서 운행 완료까지 서버 204 확인
