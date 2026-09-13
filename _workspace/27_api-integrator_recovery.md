# 27. api-integrator — 운행 중 앱 종료 → 재실행 복구 (domain·data·app 파트)

작업 지시: `_workspace/27_trip_recovery_input.md` 의 **1번(domain)·2번(data)·3번(app)**.
4번(presentation `MainNavGraph` 시작 게이트)은 리더 담당 — 이 작업에서 건드리지 않음.

## 연동한 엔드포인트

| 메서드 | 경로 | 용도 | 비고 |
|---|---|---|---|
| GET | `/api/v1/drivers/me` | 세션 유효성 확인 + 내 driver id + 표시 이름 | 기존 API 재사용 (신규 없음) |
| GET | `/api/v1/matching/rooms/{partyId}` | **복구 원천** — 파티 상태·배정 기사·좌표·장소명·인원·승객 닉네임 | 26번 `PartyMembersApi` 확장 |

- **백엔드 수정 없음.** "이 기사의 진행 중 운행" 조회 API 는 여전히 없다(`hasOngoingRide` 는 accept 409 가드 전용).
  단말에 partyId 를 저장했다가 재실행 시 그 파티를 다시 조회하는 방식으로 복구한다.
- `GET /api/v1/dispatch/calls/{partyId}` 는 **복구에 쓸 수 없다** — `DispatchService.getDetailRoom` 이 `isCallOpen`
  (콜 후보 집합)을 요구해 수락 직후 409 CALL_CLOSED 로 떨어진다. (지시서 판단 그대로 확인)

### 응답 shape (컨트롤러 소스 기준 + 26번 실측 shape)

원천: `../backend/.../matching/application/dto/PartyDetailResult.java`, `PartyController.detail`(가드 없음),
`../backend/.../matching/domain/enums/PartyStatus.java`.

```json
{"id":42,"departureLat":37.4980,"departureLng":127.0276,
 "destinationLat":37.3948,"destinationLng":127.1112,
 "departure":"강남역 2번 출구","destination":"판교역 1번 출구",
 "capacity":2,"currentMembers":2,"departureRadius":300,"destinationRadius":300,
 "status":"IN_RIDE","createdAt":"2026-09-10T01:23:45Z",
 "members":[{"publicId":"…","nickname":"승객검D417","imageUrl":null,"badgeId":null,"rideCount":1,"joinedAt":"…"}],
 "estimateFare":18400,"estimateTime":25,"route":"(대형 폴리라인)","taxiDriverId":7}
```

- ⚠️ **좌표 필드명이 dispatch 콜 상세와 다르다**: 이 응답은 `departureLat`/`departureLng`,
  `PartySummaryDto`(콜 상세)는 `departureLatitude`/`departureLongitude`. 잘못 쓰면 좌표가 통째로 null 이 된다.
- `route`(대형 폴리라인)·`createdAt`(Instant)·`capacity`·radius 는 **의도적으로 파싱하지 않는다**(ignoreUnknownKeys).
- `taxiDriverId` = **driver 엔티티 id** = `GET /drivers/me` 의 `id`.
  확인 근거: `CurrentDriverArgumentResolver` 가 `drivers.findByUserId(...).getId()` 를 주입하고,
  `Party.assignDriver(driverId)` 가 그 값을 그대로 저장한다. (userId 아님 — 비교 대상 혼동 주의)
- `PartyStatus` = `ACTIVE | COMPLETED | MATCHING | DRIVER_ASSIGNED | IN_RIDE | FINISHED | CANCELED`.
- `estimateFare`·`estimateTime` 은 파싱하지만 `ActiveTrip` 에 대응 필드가 없어 매핑하지 않는다(요금은 D16 미터기 입력이 원천).

**실서버 미검증** — 엔드포인트 자체는 26번에서 기사 토큰으로 조회 가능함이 실측 확인됐고, 복구 플로우 전체(강제 종료 → 재실행)는
리더 에뮬레이터 검증 항목이다.

## 확정 시그니처

### domain (presentation 경계면)

```kotlin
// domain/repository/DriverRepository.kt
suspend fun restoreSession(): RestoredSession

// domain/model/DriverModels.kt  →  import com.moyeota.driver.domain.model.RestoredSession
data class RestoredSession(
    val loggedIn: Boolean,
    val ongoingTrip: ActiveTrip?,
)
```

- 지시서 계약과 **문자 그대로 동일**. 변경 없음.
- `RestoredSession` 위치는 `domain/model/DriverModels.kt` (다른 도메인 모델과 같은 패키지).
  리더의 `MainNavGraph` 가 이미 `com.moyeota.driver.domain.model.RestoredSession` 로 import 해 컴파일 통과 확인됨.
- **예외를 던지지 않는다** — 시작 게이트 전용이라 모든 실패를 판정 결과로 흡수한다(CancellationException 만 전파).

### data (영속 포트)

```kotlin
// data/session/DriverSessionStorage.kt — 구현은 app 모듈 (DriverLocationSource 와 같은 패턴)
interface DriverSessionStorage {
    fun readTokens(): SessionTokens?
    fun writeTokens(accessToken: String, refreshToken: String)
    fun clearTokens()
    fun readActivePartyId(): Long?
    fun writeActivePartyId(partyId: Long)
    fun clearActivePartyId()
}
data class SessionTokens(val accessToken: String, val refreshToken: String)

// data/remote/auth/TokenStore.kt — 인메모리 유지 + write-through
fun attachPersistence(storage: DriverSessionStorage)

// data/repository/RemoteDriverRepository.kt — 생성자 옵션 파라미터 (기본 null = 복구 비활성)
RemoteDriverRepository(..., sessionStorage: DriverSessionStorage? = null)

// data/remote/PartyRecoveryMappers.kt
fun restorablePhase(status: String?): TripPhase?
fun PartyDetailDto.toRestoredTrip(partyId: Long, phase: TripPhase, vehicleInfoLabel: String,
                                  driverLat: Double? = null, driverLng: Double? = null): ActiveTrip

// data/remote/PartyMembersApi.kt — 메서드명 변경 (응답이 상세 전체가 됐다)
suspend fun getPartyDetail(@Path("partyId") partyId: Long): PartyDetailDto   // 구 getPartyMembers
// data/remote/dto/PartyMemberDtos.kt — PartyDetailMembersDto → PartyDetailDto 로 확장(이름 변경)
```

## 복구 판정 규칙

`restoreSession()` 순서 — **저장을 지우는 것은 "확인된 종료"뿐**이다.

| # | 조건 | 결과 | 저장(토큰/partyId) |
|---|---|---|---|
| 1 | 저장된 토큰 없음 | `(false, null)` → 로그인 | 손대지 않음 (서버 호출 0회) |
| 2 | `getMe` 401·403 (재발급까지 실패) | `(false, null)` → 로그인 | **토큰만 삭제 · partyId 보존** |
| 3 | `getMe` 404 (기사 미등록) | `(false, null)` → 로그인 | **전부 삭제** (홈에 넣으면 이후 기사 API 전부 404) |
| 4 | `getMe` 네트워크·5xx 실패 (3회 시도) | `(true, null)` → 홈 | **보존** (다음 실행에서 재복구) |
| 5 | 저장된 partyId 없음 | `(true, null)` → 홈 | 보존 |
| 6 | 파티 조회 401·403 | `(false, null)` → 로그인 | **토큰만 삭제 · partyId 보존** |
| 7 | 파티 조회 404 PARTY_NOT_FOUND | `(true, null)` → 홈 | partyId 삭제 (토큰 유지) |
| 8 | 파티 조회 네트워크·5xx 실패 (3회 시도) | `(true, null)` → 홈 | **보존** |
| 9 | `taxiDriverId != 내 driver id` (배정 전 null 포함) | `(true, null)` → 홈 | partyId 삭제 |
| 10 | `status == IN_RIDE` | `(true, trip)` — phase `IN_TRIP` | 보존 |
| 11 | `status == DRIVER_ASSIGNED` | `(true, trip)` — phase `ASSIGNED` | 보존 |
| 12 | 그 밖의 status (FINISHED·CANCELED·MATCHING·ACTIVE·COMPLETED) | `(true, null)` → 홈 | partyId 삭제 |
| 13 | `getMe` 의 `id` 가 null (구서버) | `(true, null)` → 홈 | 보존 (판정 불가 — 지우지 않음) |

- 재시도: 일시적 실패(IOException·5xx)만 **최초 1 + 재시도 2 = 총 3회**, 간격 300ms
  (`RemoteDriverRepository.RESTORE_MAX_ATTEMPTS` / `RESTORE_RETRY_DELAY_MS`). 4xx 는 즉시 판정으로 넘어간다.
- 복구 성공 시 `remoteTrip` 캐시에 심어 `getActiveTrip()`·`startRide()`·`submitFinalFare()` 가 이어서 동작한다
  (테스트로 "복구 → 곧바로 운행 완료 204" 경로 확인).

### 복원된 ActiveTrip 구성 (콜 수락 경로와 같은 앱 규칙)

- `id` = 조회에 쓴 partyId (`startRide`·`submitFinalFare` 가 Long 파싱해 서버를 부르므로 반드시 채운다)
- 승객 수 = `currentMembers ?: members.size`, 최소 1 / 2명 이상이면 `CallType.POOL` + 보너스 1,500 (`DispatchRules`)
- 승객 이름 = `members[].nickname` 순서 매칭, null·공백·중복은 "승객N" 폴백 (26번 `withPassengerNicknames` 재사용)
  — `passengers` 와 `stops.passengerMaskedName` 을 함께 교체(D15 하차 매칭이 문자열 대응)
- 스톱 = 승객 1명당 픽업 1 + 하차 1 (콜 수락 경로와 동일 — `passengerStops` 공용화)
- `IN_TRIP`: 전원 `boarded = true`, `nextStopIndex` = 첫 DROPOFF / `ASSIGNED`: 전원 미탑승, `nextStopIndex = 0`
- 남은 거리·ETA: `ASSIGNED` 는 기사→출발지, `IN_TRIP` 은 기사→목적지 (하버사인, 시속 30km 가정)
- 좌표: `GeoPoint`(23·24번) — 지도가 첫 프레임부터 실좌표를 그린다

### 저장 시점

| 시점 | 동작 |
|---|---|
| `acceptCall` 성공 후 | `writeActivePartyId(partyId)` |
| `submitFinalFare` 성공 후 | `clearActivePartyId()` + `remoteTrip = null` |
| `logout()` | 토큰(write-through) + partyId + 운행 캐시 전부 삭제 |
| 로그인·토큰 재발급 | `TokenStore.update` write-through 로 토큰 쌍 저장 |
| 세션 만료(401/403) | `clearExpiredTokens()` — **토큰만** (partyId 는 재로그인 복구용으로 남긴다) |

## 재로그인 직후 운행 재수화 (`login()`)

`login()` 이 성공하면 마지막에 `rehydrateOngoingTripQuietly(driver.id)` 를 한 번 돌려 저장된 partyId 로 운행을
되살리고 `remoteTrip` 에 심는다. **이 호출이 끝난 시점에 `getActiveTrip()` 이 복원된 운행을 돌려준다**
(화면은 그 값으로 운행 화면 복귀를 판단).

- 판정은 `restoreSession` 과 **완전히 같은 비공개 경로**(`restoreOngoingTrip`)를 쓴다 — 소유자·상태 가드 동일.
  낡은 partyId 가 남아 있어도 `taxiDriverId` 불일치·FINISHED 가드가 걸러내고 그때 저장을 지운다.
- **베스트에포트**: 어떤 실패(네트워크·404·매핑)도 삼키고 로그인을 실패시키지 않는다(CancellationException 만 전파).
- 저장된 partyId 가 없으면 **네트워크 호출 0회**.
- `signUp()` 은 신규 가입이라 대상이 아니다 — 건드리지 않았다.
- 이로써 세션 만료(규칙 2·6) → 로그인 화면 → 재로그인 → **운행 복귀** 경로가 이어진다.

## ⚠️ 플래그 · 남은 과제

1. **토큰 평문 저장(MVP)** — `AndroidDriverSessionStorage` 가 앱 전용 SharedPreferences(MODE_PRIVATE)에 평문 저장.
   사유와 후속(EncryptedSharedPreferences 교체)은 클래스 KDoc 에 기록. 교체해도 `DriverSessionStorage` 계약은 그대로.
2. ~~401 복구 시 운행 저장도 함께 삭제~~ → **리더 검토 반영(보완 1)으로 해소.**
   401/403 은 토큰만 비우고 partyId 를 남기며, 재로그인 시 `login()` 이 운행을 되살린다.
   여전히 남는 구멍은 "재로그인조차 하지 않고 앱을 지운 경우"뿐 — 백엔드에 "내 진행 중 운행" 조회 API 가 생기면
   단말 저장 없이 복구할 수 있다(후속 제안).
3. **시작 게이트·로그인 최대 대기 시간** — 서버가 연결은 받되 응답하지 않는 최악의 경우
   각 요청이 3회 × read timeout 20초 = 최대 약 60초까지 길어질 수 있다(연결 거부는 즉시 실패라 해당 없음).
   `login()` 재수화도 같은 재시도 정책을 공유하지만, 그 시점엔 login·getMe 두 왕복이 이미 성공한 **웜 서버**라
   재시도가 실제로 걸릴 확률은 낮다. 화면 쪽 상한이 필요하면 게이트에 `withTimeout` 을 두는 편이 낫다 —
   복구는 타임아웃 시 `(true, null)` 과 같은 결과다.
4. **실서버 미검증** — 컨트롤러 소스 + 26번 실측 shape 기준. 강제 종료 → 재실행 실기 검증은 리더 몫.
5. **D11/D13 구분 불가** — 서버 상태로는 배차 확정(D11)과 픽업 이동(D13)이 같은 `DRIVER_ASSIGNED` 다.
   매퍼는 `ASSIGNED` 로 복원하고, 화면 선택(D13)은 리더의 게이트 규칙대로.

## 변경 파일

**신규**
- `domain/…` 없음 (모델은 기존 파일에 추가)
- `data/src/main/kotlin/com/moyeota/driver/data/session/DriverSessionStorage.kt`
- `data/src/main/kotlin/com/moyeota/driver/data/remote/PartyRecoveryMappers.kt`
- `app/src/main/kotlin/com/moyeota/driver/app/AndroidDriverSessionStorage.kt`
- 테스트: `data/src/test/…/remote/PartyRecoveryMappersTest.kt`(9), `data/src/test/…/repository/RemoteDriverRepositorySessionTest.kt`(15)

**수정**
- `domain/…/model/DriverModels.kt` — `RestoredSession` 추가
- `domain/…/repository/DriverRepository.kt` — `restoreSession()` 추가
- `data/…/remote/auth/TokenStore.kt` — `attachPersistence` + write-through
- `data/…/remote/NetworkModule.kt` — tokenStore 주석(영속화 가능)
- `data/…/remote/PartyMembersApi.kt` — `getPartyMembers` → `getPartyDetail`, 반환 `PartyDetailDto`
- `data/…/remote/dto/PartyMemberDtos.kt` — `PartyDetailMembersDto` → `PartyDetailDto` 로 확장(status·taxiDriverId·좌표·장소명·currentMembers·estimateFare/Time)
- `data/…/remote/DispatchMappers.kt` — `buildPassengers`·`passengerStops` 공용화(복구 매퍼와 중복 제거)
- `data/…/repository/RemoteDriverRepository.kt` — `restoreSession`·`restoreOngoingTrip`(공통 복구 경로)·
  `rehydrateOngoingTripQuietly`(login 재수화)·`clearExpiredTokens`/`clearStoredSession`·`retryOnTransient`
  + 저장 시점 4곳 + 생성자 `sessionStorage`
- `data/…/repository/DummyDriverRepository.kt` — `restoreSession()` = `(false, null)` (더미는 기존처럼 로그인부터)
- `app/…/AppContainer.kt` — 저장소 생성 → `tokenStore.attachPersistence` → 리포지토리 주입
- 테스트: `PartyMemberDtosTest`(+2, DTO 이름 반영), `TokenStoreTest`(+4 영속화), `RemoteDriverRepositoryCallTest`(이름 반영)

**건드리지 않음**: `presentation/`, `app/…/MainActivity.kt`, `Routes.kt`, 백엔드.

## 검증

- `./gradlew :data:testDebugUnitTest` **통과** — 9개 스위트 102 테스트, 0 실패
  (PartyRecoveryMappersTest 9 · RemoteDriverRepositorySessionTest 19 · TokenStoreTest 7 · PartyMemberDtosTest 5 ·
   DispatchMappersTest 21 · RemoteDriverRepositoryCallTest 16 · AuthMappersTest 15 · DriverDtosTest 5 · CallFeedTest 5)
  - 리더 보완 반영분: 401 토큰만 삭제(getMe·파티 조회 2건) + 로그인 재수화 3건
    (복원 성공 / 조회 실패해도 로그인 성공 / partyId 없으면 파티 API 호출 0회)
- `./gradlew :app:compileDebugKotlin` **통과** (리더의 `MainNavGraph` 작업본 포함 — domain 경계면 일치 확인)
- `:app:assembleDebug` 전체와 에뮬레이터 실기(강제 종료 → 재실행 → 운행 복귀 → 완료 204)는 리더 검증 항목
