# 29c. dispatch 도메인(콜·운행·위치) 백엔드 동기화 분석

> 범위: `dispatch/` 패키지 전체 (call · ride · location) + 그 경계면인 `matching.api.dto.PartySummary`
> 백엔드 기준: `e729801` (2026-10-13) / 이전 동기화 기준점 `b9a921d`
> 기사 앱 기준: `feature/app-icon` 브랜치 현재 워킹트리
> **코드 수정 없음 — 읽기 전용 분석**

---

## 머리말 — 이 절을 먼저 읽어야 하는 이유

**dispatch 도메인의 API 표면(경로·필드·에러코드)은 `b9a921d` 이후 사실상 하나도 바뀌지 않았다.** 273 커밋 동안 일어난 건 패키지 재배치(DDD 폴더 구조)뿐이고, 와이어 포맷은 동일하다. 기사 앱의 `DispatchApi.kt` / `DispatchDtos.kt` / `DispatchMappers.kt` 는 **지금 그대로 현재 백엔드와 맞는다.**

그런데 **배포 서버에서는 dispatch 엔드포인트가 단 하나도 존재하지 않는다.** 원인은 코드 변경이 아니라 **기능 플래그 `TAXI_ENABLED`** 다. 아래 §3-A 가 이번 분석의 유일한 "깨짐"이며, 나머지는 전부 무해·문서drift·기회 항목이다.

---

## 1. 현재 백엔드 API 표 (dispatch 도메인)

### 1-0. 전제 — 모든 dispatch 컨트롤러는 기능 플래그 게이트 아래 있다

세 컨트롤러 전부 동일 애노테이션을 달고 있다:

```java
@ConditionalOnProperty(prefix = "moyeota.taxi", name = "enabled", havingValue = "true", matchIfMissing = true)
```

| 설정 파일 | 값 |
|---|---|
| `src/main/resources/application.yaml:96` | `enabled: ${TAXI_ENABLED:true}` (로컬 기본 **true**) |
| `src/main/resources/application-prod.yaml:48` | `enabled: ${TAXI_ENABLED:false}` (운영 기본 **false**) |

플래그가 꺼지면 빈 자체가 등록되지 않아 **경로가 아예 없다(404)**. 함께 사라지는 빈:

| 클래스 | 꺼질 때의 영향 |
|---|---|
| `dispatch/call/DispatchCallController` | 콜 수락·거절·상태·상세 전부 404 |
| `dispatch/location/DriverLocationController` | 영업 시작/종료·위치 보고 전부 404 |
| `dispatch/ride/RideController` | 도착·탑승·종료 전부 404 |
| `driver/DriverController` | **기사 API 전체 404** (`/drivers/me` 포함 → 로그인 자체가 막힌다) |
| `dispatch/call/DispatchListener` | `MatchingStartedEvent` 수신 불가 → 콜이 뿌려지지 않는다 |
| `dispatch/call/MatchingSweeper` | 반경 확장 재탐색·타임아웃 해산 없음 |
| `dispatch/call/infrastructure/FcmCallNotifier` | 콜 FCM 미발송 |
| `matching/party/completion/DispatchCompletionPolicy` | **정원 충족 시 `MATCHING` 전이가 일어나지 않는다** |

반대로 플래그가 꺼질 때 **대신 켜지는** 빈 (`havingValue = "false"`):

| 클래스 | 동작 |
|---|---|
| `matching/party/completion/ChatOnlyCompletionPolicy` | 정원 충족 → `COMPLETED` 에서 멈춤. 배차 없음, 채팅만 |
| `matching/party/CompletedPartySweeper` | `COMPLETED` 30분 경과 방을 10분 주기로 `FINISHED` 처리 |

> 플래그 상태는 **`GET /api/v1/config`** (permitAll) 로 조회된다 → `{"taxiEnabled": boolean}`
> (`_config/AppConfigController.java`)

### 1-A. 위치 · 영업 상태 — `dispatch/location/DriverLocationController.java`

클래스 레벨 `@RequestMapping("/api/v1")`.

| # | 메서드 | 경로 | 인증 주체 | 요청 바디 | 성공 | 에러 |
|---|---|---|---|---|---|---|
| L1 | POST | `/api/v1/dispatch/online` | `@CurrentDriver` | `{latitude, longitude}` 둘 다 `@NotNull Double` | 204 | 400 좌표 누락 · **409 `DRIVER_CANNOT_RECEIVE`** · 404 `DRIVER_NOT_REGISTERED` |
| L2 | POST | `/api/v1/dispatch/location` | `@CurrentDriver` | `{latitude, longitude}` | 204 | 400 좌표 누락 · 404 `DRIVER_NOT_REGISTERED` |
| L3 | DELETE | `/api/v1/dispatch/online` | `@CurrentDriver` | — | 204 (멱등) | 404 `DRIVER_NOT_REGISTERED` |

- `L1` 만 `driverAccess.canReceiveCalls(driverId)` 가드가 있다 (`DriverLocationService.goOnline`). **`b9a921d` 와 동일 — 변경 없음.**
- `L2`(위치 보고)에는 가드가 없다 — 영업 중이 아니어도 좌표가 GEO 에 올라간다.
- `404 DRIVER_NOT_REGISTERED` 는 컨트롤러가 아니라 `CurrentDriverArgumentResolver` 가 던진다 → **`@CurrentDriver` 를 쓰는 모든 엔드포인트의 공통 선행 에러**다.

### 1-B. 콜 — `dispatch/call/DispatchCallController.java`

클래스 레벨 `@RequestMapping("/api/v1/dispatch")`.

| # | 메서드 | 경로 | 인증 주체 | 요청 | 성공 | 에러 |
|---|---|---|---|---|---|---|
| C1 | POST | `/api/v1/dispatch/calls/{partyId}/accept` | `@CurrentDriver` | — | 204 | 409 `CALL_CLOSED` / `DRIVER_CANNOT_RECEIVE` / `DRIVER_ALREADY_RIDING` / `SELF_DISPATCH_NOT_ALLOWED` / `DRIVER_ALREADY_ASSIGNED` / `PARTY_NOT_MATCHING` · 404 `PARTY_NOT_FOUND`·`DRIVER_NOT_REGISTERED` |
| C2 | POST | `/api/v1/dispatch/calls/{partyId}/reject` | `@CurrentDriver` | — | 204 | 409 `CALL_CLOSED` |
| C3 | GET | `/api/v1/dispatch/calls/{partyId}/status` | `@CurrentDriver` | — | 200 `{open: boolean}` | (가드 없음 — 항상 200) |
| C4 | GET | `/api/v1/dispatch/calls/{partyId}` | `@CurrentDriver` | — | 200 `PartySummary` | **409 `CALL_CLOSED`** · 404 `PARTY_NOT_FOUND` |

**C1 `acceptCall` 가드 순서** (`DispatchService.acceptCall`, 선착순):
1. `callCandidates.contains(partyId, driverId)` → 아니면 `CALL_CLOSED`
2. `driverAccess.canReceiveCalls(driverId)` → 아니면 `DRIVER_CANNOT_RECEIVE`
3. `partyAccess.hasOngoingRide(driverId)` → 맞으면 `DRIVER_ALREADY_RIDING`
4. 기사의 userId 가 그 방 멤버면 `SELF_DISPATCH_NOT_ALLOWED`
5. `partyAccess.assignDriver(partyId, driverId)` → `Party.assignDriver`:
   - `status != MATCHING` → `PARTY_NOT_MATCHING` (409)
   - `taxiDriverId != null` → `DRIVER_ALREADY_ASSIGNED` (409)
6. 성공 시: 패자 목록 산출 → `callCandidates.clear(partyId)` → `driverLocations.remove(driverId)` → 패자에게 `CALL_CLOSED` 푸시

**C4 `isCallOpen` 가드는 여전히 존재한다** (지시서 5번 항목):
```java
// DispatchService.getDetailRoom
if(!isCallOpen(partyId, driverId)) throw new BusinessException(DispatchErrorCode.CALL_CLOSED);
// isCallOpen == callCandidates.contains(partyId, driverId)
```
→ 수락 성공 시 5번 단계에서 `callCandidates.clear()` 가 돌기 때문에 **수락 직후부터 C4 는 영구히 409** 다. 운행 복구 원천으로 쓸 수 없다. **변경 없음.**

### 1-C. 운행 — `dispatch/ride/RideController.java`

클래스 레벨 `@RequestMapping("/api/v1/dispatch")`.

| # | 메서드 | 경로 | 인증 주체 | 요청 | 성공 | 에러 |
|---|---|---|---|---|---|---|
| R1 | POST | `/api/v1/dispatch/rides/{partyId}/arrive` | `@CurrentDriver` | — | 204 | 409 `NOT_AWAITING_PICKUP` |
| R2 | POST | `/api/v1/dispatch/rides/{partyId}/board` | `@CurrentDriver` | — | 204 | **403** `NOT_ASSIGNED_DRIVER` · 409 `NOT_AWAITING_PICKUP` |
| R3 | POST | `/api/v1/dispatch/rides/{partyId}/complete` | `@CurrentDriver` | `{fare}` `@NotNull @Positive Integer` | 204 | 400 fare 누락/0 이하 · **403** `NOT_ASSIGNED_DRIVER` · 409 `NOT_RIDING` |
| R4 | GET | `/api/v1/dispatch/rides/{partyId}` | **`@CurrentUser` (승객)** | — | 200 `{longitude, latitude}` | 403 `NOT_PARTY_MEMBER` · 404 `PARTY_NOT_FOUND`·`DRIVER_LOCATION_UNAVAILABLE` · 409 `DRIVER_NOT_ASSIGNED` |

- `R1` 은 `isAwaitingPickup` = `status == DRIVER_ASSIGNED && driverId == taxiDriverId` 일 때만 통과. 상태 전이는 **없다** (승객 FCM `DRIVER_ARRIVED` 발송만).
- `R2`/`R3` 는 `Party.ensureAssignDriver` 선행 → 배정 기사 아니면 **403**, 그 다음 상태 검사 → **409**. 상태 전이: `DRIVER_ASSIGNED → IN_RIDE → FINISHED`.
- **`R4` 는 승객 전용이다** (`@CurrentUser`). 기사 앱이 호출할 엔드포인트가 아니다.

### 1-D. 응답 모델

**`matching/api/dto/PartySummary.java`** — C4 응답 · 콜 FCM 소스 (필드 순서·타입 그대로):
```java
public record PartySummary(
    Long id,
    double departureLatitude, double departureLongitude,
    double destinationLatitude, double destinationLongitude,
    String departure, String destination,
    int memberCount,
    Integer estimatedFare,   // null 가능
    Integer estimatedTime,   // null 가능 (분)
    Long driverId            // 배정 전 null
) {}
```
> 주의: `matching/party/domain/PartySummary.java` 라는 **동명 레코드가 별도로 존재**한다 (지도 목록용: `id, departure, destination, currentMembers, capacity, status, departureLat, departureLng`). 백엔드 내부 전용이고 API 로 나오지 않는다 — 혼동 금지.

**에러 바디** — `common/exception/ErrorResponse.java`: `{code, message}` (전 도메인 공통, 변경 없음)

### 1-E. 서버 상수 (지시서 2번 항목)

| 상수 | 값 | 위치 |
|---|---|---|
| 하트비트 TTL | **30초** | `DriverLocationRedis.HEARTBEAT_TTL` |
| GEO 키 | `drivers:location` | `DriverLocationRedis.GEO_KEY` |
| 하트비트 키 | `drivers:heartbeat:{driverId}` | `DriverLocationRedis.HEARTBEAT_PREFIX` |
| 콜 후보 TTL | **5분** | `CallCandidatesRedis.TTL` |
| 후보 키 / 거절 키 | `dispatch:candidates:{partyId}` / `dispatch:rejected:{partyId}` | `CallCandidatesRedis` |
| 초기 반경 | **1000m** | `DispatchService.INITIAL_RADIUS_METERS` |
| 반경 증분 | **500m** | `DispatchService.RADIUS_STEP_METERS` |
| 최대 반경 | **3000m** | `DispatchService.MAX_RADIUS_METERS` |
| 매칭 타임아웃 | **3분** | `DispatchService.MATCHING_TIMEOUT` |
| 스윕 주기 | **30초** (`fixedDelay`) | `MatchingSweeper.sweep` |
| 반경 공식 | `min(3000, 1000 + floor(elapsed_sec / 30) * 500)` | `DispatchService.radiusFor` |

**전부 `b9a921d` 와 동일.** 유일한 추가는 `MatchingSweeper` 에 붙은 `@SchedulerLock(name="matching-sweep", lockAtMostFor="PT25S", lockAtLeastFor="PT10S")` (커밋 `963185e`, ShedLock 분산 잠금) — 다중 인스턴스에서 스윕 중복을 막는 서버 내부 변경이고 **클라이언트 영향 없음**.

**콜 후보 판정 로직** (`DispatchService.attempt`) — 필터 순서:
1. `driverLocations.findNearby(출발지 좌표, radius)` — Redis GEO + **하트비트 TTL 생존 필터**(`isAlive`)
2. `!already.contains(id)` — 이미 후보인 기사 제외
3. `!rejected.contains(id)` — **거절한 기사 재호출 금지**
4. `driverAccess.canReceiveCalls(id)`

판정 로직 자체는 변경 없음. **단 하나 추가된 가드가 `0586b96`** (§3-F 참조).

### 1-F. 콜 FCM 페이로드 — `FcmCallNotifier`

| 타입 | 데이터 키 | 비고 |
|---|---|---|
| `CALL_OPENED` | `type`, `partyId`, `departure`, `destination` | **`memberCount`·`estimatedFare` 는 실려오지 않는다** |
| `CALL_CLOSED` | `type`, `partyId` | 수락 패자 + 타임아웃 해산 시 |

둘 다 `AndroidConfig.Priority.HIGH` 데이터 메시지. `b9a921d` 대비 **페이로드 키 변경 없음** (내부적으로 `driverAccess.findFcmTokens` 일괄 조회로 바뀐 것은 `0586b96`, 와이어 영향 없음).

> `FcmCallNotifier` 는 `@ConditionalOnExpression("!'${fcm.service-account-path:}'.isEmpty()")` 도 함께 요구한다. `FCM_SERVICE_ACCOUNT_PATH` 가 비면 `LoggingCallNotifier`(로그만 찍고 발송 안 함)로 떨어진다 — 플래그를 켠 뒤에도 콜 푸시가 안 오면 이 환경변수를 의심할 것.

### 1-G. DTO 이동 — **경로만 바뀌었다 (필드 변경 전무)** · 지시서 1번 항목 확정

`b9a921d` 와 `HEAD` 의 레코드 선언을 직접 대조한 결과:

| DTO | `b9a921d` 경로 | `HEAD` 경로 | 필드 |
|---|---|---|---|
| `LocationReportRequest` | `dispatch/application/dto/` | `dispatch/location/dto/` | `(@NotNull Double latitude, @NotNull Double longitude)` — **동일** |
| `CompleteRideRequest` | `dispatch/application/dto/` | `dispatch/ride/dto/` | `(@NotNull @Positive Integer fare)` — **동일** |
| `CallStatusResponse` | `dispatch/application/dto/` | `dispatch/call/dto/` | `(boolean open)` — **동일** |
| `DriverLocationResponse` | `dispatch/application/dto/` | `dispatch/ride/dto/` | `(double longitude, double latitude)` — **동일** |
| `DispatchErrorCode` | `dispatch/domain/exception/` | `dispatch/exception/` | enum 상수 9개 + HTTP 상태 **전부 동일** (`git diff` 결과 package 선언 1줄뿐) |
| `MatchingErrorCode` | `matching/domain/exception/` | `matching/exception/` | **전부 동일** (package 선언 1줄뿐) |
| `PartySummary` | `matching/api/` | `matching/api/dto/` | 11필드 **동일** |

유일한 차이는 `@Schema(example = ...)` Swagger 애노테이션 추가 — **와이어 포맷 무영향**.

컨트롤러도 경로·시그니처가 100% 동일하다 (`dispatch/interfaces/*` 삭제 + `dispatch/{call,ride,location}/*` 신규 생성이라 git 이 rename 으로 잡지 못했을 뿐). `b9a921d` 와 `HEAD` 의 `@*Mapping` + 메서드 시그니처를 추출 대조해 11개 엔드포인트 전부 일치 확인.

> **결론: dispatch 패키지 재배치는 순수 리팩터링이다. 클라이언트가 고칠 와이어는 없다.**

### 1-H. 신규 엔드포인트 — 기사 앱 관련

| 엔드포인트 | 인증 | 응답 | 기사 앱 관점 |
|---|---|---|---|
| `GET /api/v1/config` | **permitAll** | `{taxiEnabled}` | **활용 가치 높음** — §4-A |
| `GET /api/v1/matching/rooms/{partyId}/driver` | 인증(파라미터 없음) | `DriverSummary{seats, plateNumber, type}` | 승객용(배정 기사 차량 정보). 기사 앱엔 불필요 |

**"내 진행 중 운행 조회" API 는 신설되지 않았다.** `hasOngoingRide` 전체 사용처를 확인한 결과 `DispatchService.acceptCall` 의 가드 1곳뿐이고, 어떤 컨트롤러도 노출하지 않는다. `PartyAccess` 인터페이스 전체를 봐도 기사 기준 조회 메서드는 없다.

---

## 2. 기사 앱 클라이언트 현재 상태

### 2-A. 엔드포인트 대응 — 11/11 정확히 일치

`data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchApi.kt`

| 백엔드 | 클라이언트 | 라인 | 판정 |
|---|---|---|---|
| L1 POST `/dispatch/online` | `goOnline(@Body LocationReportRequestDto)` | `:22-23` | 일치 |
| L3 DELETE `/dispatch/online` | `goOffline()` | `:25-26` | 일치 |
| L2 POST `/dispatch/location` | `reportLocation(@Body …)` | `:28-29` | 일치 |
| C1 POST `/calls/{partyId}/accept` | `acceptCall(partyId)` | `:33-34` | 일치 |
| C2 POST `/calls/{partyId}/reject` | `rejectCall(partyId)` | `:36-37` | 일치 |
| C3 GET `/calls/{partyId}/status` | `callStatus(partyId)` | `:39-40` | 일치 (**미사용** — §3-D) |
| C4 GET `/calls/{partyId}` | `getPartyDetail(partyId)` | `:43-44` | 일치 |
| R1 POST `/rides/{partyId}/arrive` | `arrive(partyId)` | `:48-49` | 일치 |
| R2 POST `/rides/{partyId}/board` | `board(partyId)` | `:51-52` | 일치 |
| R3 POST `/rides/{partyId}/complete` | `complete(partyId, @Body CompleteRideRequestDto)` | `:54-58` | 일치 |
| R4 GET `/rides/{partyId}` | *(미선언 — 승객용이라 정확한 판단)* | — | 해당 없음 |

### 2-B. DTO 대응 — 전부 일치

`data/src/main/kotlin/com/moyeota/driver/data/remote/dto/DispatchDtos.kt`

| 클라이언트 DTO | 라인 | 서버 레코드 | 판정 |
|---|---|---|---|
| `LocationReportRequestDto(latitude, longitude)` | `:15-18` | `location/dto/LocationReportRequest` | 일치 |
| `CallStatusResponseDto(open=false)` | `:21-23` | `call/dto/CallStatusResponse` | 일치 |
| `PartySummaryDto` 11필드 | `:26-39` | `matching/api/dto/PartySummary` | 일치 (서버 primitive → 클라 nullable, 안전 방향) |
| `CompleteRideRequestDto(fare: Int)` | `:42-45` | `ride/dto/CompleteRideRequest` | 일치 |
| `DriverLocationResponseDto` | `:48-52` | `ride/dto/DriverLocationResponse` | 일치하나 **완전 미사용** (§3-D) |
| `ErrorResponseDto(code, message)` | `:55-59` | `common/exception/ErrorResponse` | 일치하나 **중복 정의·미사용** (§3-E) |

`NetworkModule.kt:36-39` 의 `Json { ignoreUnknownKeys = true; coerceInputValues = true }` 가 서버 필드 추가(예: `PartyDetailResult.fingerprint`)를 흡수한다.

### 2-C. 매퍼 — `DispatchMappers.kt`

| 함수 | 라인 | 비고 |
|---|---|---|
| `PartySummaryDto.toCallSummary` | `:37-53` | `id` null 이면 `checkNotNull` 로 즉시 실패 (의도적) |
| `pushCallSummary` | `:66-88` | 푸시에 `memberCount`/`estimatedFare` 없을 때 금액 전부 0 — **§1-F 대로 실제로 항상 이 분기만 실행된다. 올바름.** |
| `PartySummaryDto.toCallDetail` | `:90-100` | — |
| `PartySummaryDto.toActiveTrip` | `:106-134` | — |
| `ActiveTrip.withPassengerNicknames` | `:198-215` | `matching/rooms/{partyId}` 의 `members[].nickname` 주입 |
| `calcFareResult` | `:230-239` | 서버가 정산 내역을 안 주므로 로컬 계산 (수수료 5%) |

앱 로컬 규칙(서버 미제공): `CALL_FEE = 3,000` · `POOL_BONUS = 1,500` · `SERVICE_FEE_PERCENT = 5` · `COUNTDOWN_SECONDS = 15` (`:30-35`)

### 2-D. Repository 호출부 — `RemoteDriverRepository.kt`

| 메서드 | 라인 | 호출 | 실패 정책 |
|---|---|---|---|
| `setDutyStatus` | `:547` | → `goOnDuty`/`goOffDuty` | — |
| `goOnDuty` | `:561-566` | `goOnline` | **엄격** — 좌표 없으면 서버 호출 안 함, 4xx/5xx 전파 |
| `goOffDuty` | `:572-581` | `goOffline` | **관대** — 실패해도 더미 휴무 전환 (서버 TTL 자동 오프라인) |
| `startHeartbeat` | `:592-600` | — | `HEARTBEAT_INTERVAL_MS = 15_000L` (`:901`) = 서버 TTL 30초의 절반 |
| `reportLocationTick` | `:609-622` | `reportLocation` | 좌표 미상이면 주기 스킵, 전송 실패 무시 |
| `getCallDetail` | `:726-739` | `getPartyDetail` | 예외 → `callFailure`. **더미 강등 금지** |
| `acceptCall` | `:744-770` | `getPartyDetail` → `acceptCall` | 전파. 성공 시 `writeActivePartyId` |
| `declineCall` | `:788-805` | `rejectCall` | **409 는 성공 취급** (의도대로 맞음) |
| `handleCallOpened` | `:669-683` | `getPartyDetail` | 409 → 피드 제거 / 그 외 → 임시 요약 유지 |
| `startRide` | `:844-856` | `board` | 전파 |
| `notifyPickupArrival` | `:861-869` | `arrive` | **전부 삼킴** (알림 전용이라 타당) |
| `submitFinalFare` | `:877-886` | `complete` | 전파. 성공 시 `clearActivePartyId` |
| `callFailure` | `:811-831` | — | 409→`CLOSED` / 401·403→`UNAUTHORIZED` / IOException→`NETWORK` / 그 외→`SERVER` |
| `restoreSession` | `:182-218` | `driverApi.getMe` → `restoreOngoingTrip` | 404 → `clearStoredSession` |
| `restoreOngoingTrip` | `:225-265` | `partyMembersApi.getPartyDetail` | C4 대신 `matching/rooms/{partyId}` 사용 |

### 2-E. 운행 복구 우회 경로 — `PartyMembersApi` + `PartyMemberDtos`

`GET /api/v1/matching/rooms/{partyId}` → `PartyDetailDto`. 백엔드 `PartyDetailResult` 와 대조:

| 클라 필드 | 서버 필드 | 판정 |
|---|---|---|
| `departureLat`/`Lng`, `destinationLat`/`Lng` | 동일 | 일치 |
| `departure`, `destination`, `currentMembers`, `status` | 동일 | 일치 |
| `members[].nickname` | `MemberInfo.nickname` | 일치 |
| `estimateFare`, `estimateTime` | 동일 (**dispatch 쪽은 `estimated*`** — 이름이 다르다) | 일치 |
| `taxiDriverId` | 동일 | 일치 |
| *(미선언)* | **`fingerprint`** ← 신규 (`8a01214`) | `ignoreUnknownKeys` 로 무해 |

`PartyStatus` enum 7값(`ACTIVE, COMPLETED, MATCHING, DRIVER_ASSIGNED, IN_RIDE, FINISHED, CANCELED`) **변경 없음**. `PartyController` 는 **taxi 플래그 게이트가 없다** → 플래그가 꺼져 있어도 이 복구 경로는 살아 있다.

---

## 3. 불일치 목록

### 3-A. 【깨짐 · 치명】 배포 서버에 dispatch·driver 엔드포인트가 존재하지 않는다 (`TAXI_ENABLED=false`)

**실서버 검증 완료 (읽기 전용 호출만 수행).**

증거 1 — 배포 서버 자기 신고:
```
GET https://api.moyeota.p-e.kr/api/v1/config  →  200  {"taxiEnabled":false}
```

증거 2 — 배포 서버 OpenAPI 문서 (`GET /v3/api-docs`, 200): **등록 경로 36개 중 `dispatch` 를 포함하는 경로 0개, `drivers` 를 포함하는 경로 0개.** 태그 목록도 `내 정보 / 인증 / App Config / 매칭방 / 장소 / 즐겨찾기 장소 / 긴급 신고 / 유저` 8개뿐 — 소스에 있는 `기사 콜`·`운행`·`기사 위치`·`기사` 태그가 전부 없다. 컴포넌트 스키마 41개 중 `PartySummary`·`CallStatusResponse`·`LocationReportRequest`·`CompleteRideRequest`·`DriverResult` **전부 ABSENT**.

증거 3 — **배포 빌드는 최신이다** (스펙 낙후가 아님을 배제): 배포 스키마의 `PartyDetailResult` 에 `fingerprint` 필드가 있고 `PartyStatusResponse` 에도 있다. `fingerprint` 는 `8a01214` 에서 추가됐고 `b9a921d` 의 후손임을 `git merge-base --is-ancestor` 로 확인했다. 즉 **배포 서버는 b9a921d 이후 빌드를 돌리고 있으면서 dispatch 를 의도적으로 끈 상태**다.

증거 4 — 직접 호출(미인증)은 Security 필터가 라우팅보다 먼저 가로채 404 를 가린다:
```
GET /api/v1/dispatch/calls/1/status  → 401 {"code":"USER005","message":"로그인이 필요합니다."}
GET /api/v1/dispatch/calls/1         → 401 (동일)
GET /api/v1/driver/me                → 401 (동일)
```
`SecurityConfig:47-49` 가 `/api/v1/auth/**`, `/api/v1/config`, `/ws-chat/**`, `/health`, `/prometheus`, `/info`, swagger 만 permitAll 이고 나머지는 `anyRequest().authenticated()` 이기 때문이다. **유효 토큰으로 호출하면 404 가 그대로 드러난다.**

**증상 (기사 앱 체감):**
1. 로그인 → `driverApi.getMe()`(`/api/v1/drivers/me`) **404** → `RemoteDriverRepository:331-334` 가 404 를 `DRIVER_NOT_REGISTERED` 로 단정해 **"기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요."** 라는 **원인과 무관한 오답**을 띄운다. 실제로는 서버에 기사 API 자체가 없는 상태다.
2. 앱 재실행 → `restoreSession`(`:196-204`)이 404 를 "기사 미등록(확인된 종료)"로 보고 **`clearStoredSession()` 으로 토큰과 활성 partyId 를 지운다** → 운행 중이었어도 복구 불가 + 재로그인 강제.
3. 영업 시작 → `goOnline` 404 → `goOnDuty` 가 전파 → 영업 전환 실패.
4. 설령 플래그를 켜도, 그 전까지 `ChatOnlyCompletionPolicy` 가 돌았으므로 정원 충족 방은 `COMPLETED` 에 머물렀고 `MatchingStartedEvent` 가 발행된 적이 없다 → **기존 방으로는 콜이 영원히 오지 않는다.** 게다가 `CompletedPartySweeper` 가 30분 뒤 `FINISHED` 로 정리한다.

**고칠 지점:** 1차 원인은 **서버 환경변수**(`TAXI_ENABLED=true`, 추가로 `FCM_SERVICE_ACCOUNT_PATH`)이고 **백엔드 배포 담당자의 몫**이다. 클라이언트 쪽에서 할 일은 "404 를 기사 미등록으로 단정하는 오진"을 걷어내는 것 → §4-A, §4-B.

### 3-B. 【동작변경 · 낮음】 dispatch 패키지 재배치 — 와이어 영향 없음, 소스 주석만 낙후

| 파일 | 라인 | 낙후된 주석 | 현재 사실 |
|---|---|---|---|
| `DispatchApi.kt` | `:12-13` | `.../dispatch/interfaces/{DriverLocationController,DispatchCallController,RideController}.java` | `dispatch/location/`, `dispatch/call/`, `dispatch/ride/` |
| `DispatchDtos.kt` | `:5-6` | `.../dispatch/application/dto` 하위 / `matching/api/PartySummary.java` | `dispatch/{call,ride,location}/dto/` / `matching/api/dto/PartySummary.java` |
| `DispatchDtos.kt` | `:7` | "(컨트롤러 소스 기준, **실서버 미검증**)" | 이번 분석으로 소스 대조 완료 — 단 §3-A 때문에 실 200 응답은 여전히 미획득 |
| `DispatchDtos.kt` | `:13` | `POST /api/v1/dispatch/online/{driverId}` — **구 경로(driverId path)** | `POST /api/v1/dispatch/online` (토큰 기반). 코드는 이미 신경로라 **주석만 틀렸다** |
| `DispatchDtos.kt` | `:25` | `GET /api/v1/dispatch/calls/{partyId}/{driverId}` — 구 경로 | `GET /api/v1/dispatch/calls/{partyId}` |
| `DispatchDtos.kt` | `:41` | `POST /api/v1/dispatch/rides/{partyId}/complete/{driverId}` — 구 경로 | `.../complete` |
| `DispatchDtos.kt` | `:47` | `GET /api/v1/dispatch/rides/{partyId}/{memberId}` — 구 경로 | `GET /api/v1/dispatch/rides/{partyId}` |
| `PartyMemberDtos.kt` | `:5` | `.../matching/application/dto/PartyDetailResult.java` | `matching/party/dto/PartyDetailResult.java` |
| `PartyMembersApi.kt` | `:7` | `.../matching/presentation/PartyController.detail` | `matching/party/PartyController.detail` |

심각도 낮음 — **실행 코드는 전부 신경로를 쓰고 있다.** 다음 담당자가 주석의 구 경로를 믿고 "되돌리는" 역행 수정을 할 위험만 남는다.

### 3-C. 【무해】 `PartyDetailResult.fingerprint` 신규 필드

`8a01214` 에서 추가. `PartyMemberDtos.kt` 가 미선언이지만 `ignoreUnknownKeys = true` 로 무시된다. 복구 로직은 `fingerprint` 를 쓰지 않으므로 대응 불필요.

### 3-D. 【무해】 미사용 선언 2건

- `DispatchApi.callStatus` (`:39-40`) — 프로덕션 호출처 0건. 테스트 스텁 3곳(`RemoteDriverRepositoryCallTest.kt:330`, `…DutyLocationTest.kt:268`, `…SessionTest.kt:458`)에서 `unused()` 로만 구현. 백엔드 C3 는 "푸시를 놓쳤을 때 앱이 확인용으로 호출"하라고 문서화돼 있는데 앱이 쓰지 않는다 → §4-D 기회.
- `DriverLocationResponseDto` (`DispatchDtos.kt:48-52`) — 호출처 0건. R4 가 승객 전용이므로 **올바르게 미사용**. 유지하려면 "승객용 참고" 주석이 이미 있으니 그대로 둬도 무방.

### 3-E. 【무해】 `ErrorResponseDto` 중복 정의

| 위치 | 패키지 | 사용 여부 |
|---|---|---|
| `remote/AuthMappers.kt:66` | `com.moyeota.driver.data.remote` | **사용 중** (`:80` `serverMessage()` 파싱, `AuthMappersTest.kt:176`) |
| `remote/dto/DispatchDtos.kt:55` | `com.moyeota.driver.data.remote.dto` | **미사용** |

패키지가 달라 컴파일은 통과한다. 동일 서버 shape 에 대한 중복 정의 — 정리 대상.

### 3-F. 【무해 → 오히려 개선】 `0586b96` "배차 중복 호출 방지" 분석 (지시서 3번 항목)

**변경 실체는 단 5줄이고, 위치는 `attempt()`(콜 뿌리기)다. `acceptCall()` 은 손대지 않았다.**

```java
// DispatchService.attempt() — partyAccess.findSummary 직후에 삽입
if(party.driverId() != null) {
    log.info("이미 기사 배정됨 - 호출 건너뜀 partyId={}", partyId);
    return;
}
```

**새 에러코드 0건, 새 409 조건 0건.** `DispatchErrorCode`·`MatchingErrorCode` 를 `b9a921d..HEAD` 로 diff 한 결과 package 선언 1줄 외 변경이 없음을 확인했다. 함께 추가된 테스트 2개(`기사가_배정된_뒤_같은_이벤트가_다시_와도_콜을_다시_뿌리지_않는다`, `기사가_배정된_방은_재탐색에서도_콜을_뿌리지_않는다`)도 "알림이 안 간다"만 검증하고 예외를 기대하지 않는다.

**클라이언트 재시도에 미치는 영향: 없음(계약 불변).**

**클라이언트 중복 수락에 미치는 영향: 없음(가드 불변).** 두 기사가 동시에 수락하면 여전히 1단계 `contains` 를 둘 다 통과한 뒤 5단계 `Party.assignDriver` 에서 늦은 쪽이 `DRIVER_ALREADY_ASSIGNED`(409) 또는 `PARTY_NOT_MATCHING`(409) 을 맞는다. 클라이언트 `callFailure:813` 이 **모든 409 를 `Kind.CLOSED` + "이미 마감된 콜이에요"** 로 묶으므로 세 코드 전부 올바른 UX 로 떨어진다. **대응 불필요.**

**오히려 클라이언트에 유리한 변화:** 이전에는 수락 성공 시 `callCandidates.clear(partyId)` 로 후보 집합이 비워지므로, 직후 `MatchingSweeper`(30초 주기)나 재발행된 `MatchingStartedEvent` 가 `attempt()` 를 돌리면 주변 기사들이 `already`·`rejected` 어느 필터에도 안 걸려 **"신규 후보"로 재분류되고 이미 배정된 방의 유령 `CALL_OPENED` 푸시를 받았다.** 이 패치가 그 경로를 원천 차단한다.

→ 기사 앱의 방어 코드(`handleCallOpened:676-678` 의 409 → `callFeed.remove`)는 **여전히 정확하고 필요하지만, 발동 빈도가 크게 줄어든다.** QA 가 "수락 후 유령 콜 알림" 재발을 확인할 가치가 있는 항목.

### 3-G. 【무해 · 문서 버그】 백엔드 Swagger 의 HTTP 상태 오기

`DispatchCallController:28` 이 `DRIVER_CANNOT_RECEIVE` 를 **403** 으로 문서화했으나 `DispatchErrorCode:11` 은 `HttpStatus.CONFLICT`(**409**) 다. 실제 응답은 409.

클라이언트 영향: `callFailure` 가 401·403 을 `UNAUTHORIZED`("다시 로그인해 주세요")로, 409 를 `CLOSED`로 보낸다. **실제가 409 이므로 현 매핑이 맞다** — Swagger 문서를 믿고 403 분기를 추가하면 오히려 틀린다. 백엔드 문서 수정 사안(우리 범위 밖, 리더 전달용).

### 3-H. 【동작변경 · 중간】 `goOnline` 의 409 `DRIVER_CANNOT_RECEIVE` 가 "콜 마감" 문구로 새어나갈 수 있다

`goOnDuty`(`:561-566`)는 `dispatchApi.goOnline` 의 예외를 **가공 없이 그대로 전파**한다 (`callFailure` 를 타지 않는다 — 이쪽은 콜 경로 전용). 즉 영업 시작 실패 시 화면이 받는 건 날것의 `HttpException(409)` 이다.

`DRIVER_CANNOT_RECEIVE` 는 `Driver.canReceiveCalls()` 가 false 인 세 경우(미검증 / 차량 미등록 / 콜 수신 꺼짐)에 발생하므로, 기사가 실제로 해야 할 행동은 **"기사 검증·차량 등록·콜 설정 확인"** 이다. 화면이 409 를 콜 문구로 보여주거나 일반 오류로 뭉개면 기사가 원인을 알 수 없다.

> 이 항목은 §3-A 가 해결돼 영업 시작이 실제로 서버에 닿기 시작하는 순간 표면화된다. 지금은 404 에 가려 보이지 않는다. **화면 쪽 문구 분기는 compose-builder 협의 사항.**

### 3-I. 【동작변경 · 낮음】 `acceptCall` 타임아웃 후 재시도 시 오답

`acceptCall`(`:744-770`)에는 재시도가 없다(`retryOnTransient` 는 복구 경로 전용 — 올바른 설계). 그런데 **수락이 서버에서 성공했는데 응답이 타임아웃으로 유실되면**, 기사가 버튼을 다시 누를 때:
- 서버는 1단계 `callCandidates.contains` 에서 이미 비워진 집합을 보고 **409 `CALL_CLOSED`**
- 클라이언트는 "이미 마감된 콜이에요 · 다음 콜을 기다려 주세요" 를 띄운다

**실제로는 그 기사가 배차를 받은 상태**(`taxiDriverId` = 본인, `status = DRIVER_ASSIGNED`)인데 콜을 놓친 것처럼 안내된다. 배포 서버 콜드 스타트가 7~8초(`NetworkModule.kt:84-86` 주석의 실측)인 환경에서 충분히 발생 가능한 경로다. 해소안 → §4-C.

---

## 4. 권장 수정안

> §3 결과상 **DTO·경로·필드 수정은 단 한 건도 필요하지 않다.** 아래는 (A) 플래그 상황의 오진 제거, (B) 404 의미 분리, (C) 수락 재시도 정합성, (D)~(F) 기회·정리 항목이다.

### 4-A. 【최우선】 `GET /api/v1/config` 로 서버 기능 플래그를 먼저 확인한다

**근거:** permitAll · 토큰 불필요 · 응답 1필드. 이걸 안 보면 `taxiEnabled=false` 를 "기사 미등록"으로 오진하는 §3-A 를 구조적으로 못 막는다.

신규 파일 `data/remote/dto/AppConfigDtos.kt`:
```kotlin
/** GET /api/v1/config 응답 — _config/AppConfigResponse. permitAll (토큰 불필요) */
@Serializable
data class AppConfigResponseDto(
    /** false 면 서버에 dispatch·driver 엔드포인트가 등록되지 않은 상태 (TAXI_ENABLED) */
    val taxiEnabled: Boolean = true,   // 구서버·조회 실패 시 기존 동작 유지
)
```

`AuthApi.kt` 에 추가 (Bearer 없는 기본 클라이언트가 맞다):
```kotlin
/** 서버 기능 플래그 — taxiEnabled=false 면 기사·dispatch API 전체가 404 다 */
@GET("api/v1/config")
suspend fun getAppConfig(): AppConfigResponseDto
```

`domain/` 경계면 — Repository 시그니처 **신규 1건** (compose-builder 통지 필요):
```kotlin
/** 서버가 기사(택시) 기능을 켜 둔 상태인지. 조회 실패 시 true (기존 동작 유지) */
suspend fun isTaxiServiceEnabled(): Boolean
```

`RemoteDriverRepository` 구현 — 베스트에포트 + 세션당 1회 캐시:
```kotlin
private var cachedTaxiEnabled: Boolean? = null

override suspend fun isTaxiServiceEnabled(): Boolean =
    cachedTaxiEnabled ?: runCatching { authApi.getAppConfig().taxiEnabled }
        .getOrDefault(true)                 // 조회 실패로 앱을 막지 않는다
        .also { cachedTaxiEnabled = it }
```

`DummyDriverRepository` 는 `= true` 로 구현.

### 4-B. 【최우선】 404 를 "기사 미등록"으로 단정하지 않는다

현재 두 곳이 `driverApi.getMe()` 의 404 를 `DRIVER_NOT_REGISTERED` 로 확정한다. `taxiEnabled=false` 면 **같은 404 가 "엔드포인트 없음"** 을 뜻하므로 오진이다.

**(1) `login` (`RemoteDriverRepository.kt:331-334`)**
```kotlin
if (e.code() == 404) {
    // taxiEnabled=false 면 /drivers/me 자체가 없다 — 가입 안내로 오진하면 안 된다
    if (!isTaxiServiceEnabled()) {
        throw IllegalStateException("서버에서 기사 서비스가 아직 열리지 않았어요. 잠시 후 다시 시도해 주세요.", e)
    }
    throw IllegalStateException("기사로 가입되지 않은 계정입니다. 회원가입을 진행해 주세요.", e)
}
```

**(2) `restoreSession` (`:196-204`)** — 이쪽이 더 치명적이다. 404 → `clearStoredSession()` 이 **활성 partyId 까지 지워 운행 복구를 영구 포기**시킨다.
```kotlin
404 -> {
    // 서버 기능이 꺼진 상태의 404 는 "확인된 종료"가 아니다 — 저장을 보존하고 홈에 착지시킨다
    if (!isTaxiServiceEnabled()) return RestoredSession(loggedIn = true, ongoingTrip = null)
    clearStoredSession()
}
```
`restoreSession` 의 기존 원칙("활성 partyId 를 지우는 경우는 **확인된** 종료뿐", `:179-181` 주석)과 정확히 같은 방향의 보강이다.

### 4-C. 【중간】 `acceptCall` 의 409 를 "내가 이미 배정받았는가"로 판별한다

§3-I 해소. 409 를 곧바로 `CLOSED` 로 단정하기 전에, **이미 확보한 복구 경로**(`matching/rooms/{partyId}` — taxi 플래그 무관하게 살아 있다)로 소유권을 한 번 확인한다.

```kotlin
// acceptCall 내부 — dispatchApi.acceptCall(partyId) catch 블록
} catch (e: Exception) {
    // 수락이 성공했는데 응답만 유실된 경우: 서버엔 내가 배정 기사로 남아 있다.
    // 이때 "이미 마감된 콜"로 안내하면 받은 운행을 놓친 것처럼 보인다.
    if (e is retrofit2.HttpException && e.code() == 409 && isAssignedToMe(partyId)) {
        // 수락 성공으로 간주하고 아래 ActiveTrip 구성으로 계속 진행
    } else {
        throw callFailure(e, "수락하지 못했어요")
    }
}

/** 내가 이 방의 배정 기사인지 — 베스트에포트(실패 시 false, 기존 409 경로 유지) */
private suspend fun isAssignedToMe(partyId: Long): Boolean = runCatching {
    val myDriverId = driverApi.getMe().id ?: return false
    val party = partyMembersApi?.getPartyDetail(partyId) ?: return false
    party.taxiDriverId == myDriverId &&
        restorablePhase(party.status) != null   // DRIVER_ASSIGNED · IN_RIDE
}.getOrDefault(false)
```
`restorablePhase` 는 이미 존재하는 복구 판정 헬퍼라 재사용된다. 실패 시 `false` 로 떨어져 **기존 동작을 그대로 유지**하는 순수 추가다.

### 4-D. 【기회】 C3 `callStatus` 를 콜 상세 화면 진입 전 프리플라이트로 쓴다

백엔드 C3 는 `isCallOpen` 을 **예외 없이 `{open}` 으로** 돌려준다 (C4 는 같은 판정으로 409 를 던진다). 지금 `handleCallOpened`/`getCallDetail` 은 C4 로 바로 들어가 409 를 예외로 받아 처리하는데, 푸시를 놓쳤다 복귀한 경우처럼 "열렸는지 모르겠는" 상황에서는 C3 가 더 싸고 명확하다.

```kotlin
/** 푸시 누락 복귀 시 — 열린 콜만 상세 조회로 진행 (서버 권장 용법) */
suspend fun isCallStillOpen(partyId: Long): Boolean =
    runCatching { dispatchApi.callStatus(partyId).open }.getOrDefault(false)
```
우선도 낮음(현 409 처리로도 기능은 성립). `callStatus` 선언을 지우지 말아야 할 근거로 기록해 둔다.

### 4-E. 【정리】 주석의 구 경로·구 패키지 경로 갱신

§3-B 표대로 `DispatchApi.kt:12-13`, `DispatchDtos.kt:5-7·13·25·41·47`, `PartyMemberDtos.kt:5`, `PartyMembersApi.kt:7` 의 경로 주석을 현재 패키지로 고친다. **실행 코드 변경 0줄.** 구 경로(`/{driverId}` path) 주석이 남아 있으면 다음 담당자가 신경로를 "되돌리는" 역행 수정을 할 위험이 실재한다.

### 4-F. 【정리】 `ErrorResponseDto` 중복 제거

`dto/DispatchDtos.kt:55-59` 의 미사용 정의를 삭제하고 `remote/AuthMappers.kt:66` 의 것만 남긴다 (후자가 `serverMessage()` 파싱에 실제 사용되고 테스트도 있다).

### 4-G. 【리더 전달 — 클라이언트 범위 밖】

1. **서버 환경변수 `TAXI_ENABLED=true`** 설정 요청. 이것 없이는 기사 앱 실연동 검증이 원리적으로 불가능하다. 함께 **`FCM_SERVICE_ACCOUNT_PATH`** 도 확인해야 한다 — 비어 있으면 `FcmCallNotifier` 대신 `LoggingCallNotifier` 가 붙어 **콜 푸시가 발송되지 않는다**(§1-F).
2. **플래그를 켠 뒤 생성한 새 방으로만 E2E 가 성립한다.** 그 전에 만들어진 방은 `ChatOnlyCompletionPolicy` 때문에 `COMPLETED` 에 머물러 `MatchingStartedEvent` 가 발행된 적이 없고, `CompletedPartySweeper` 가 30분 뒤 `FINISHED` 로 정리한다.
3. **QA 기사 계정 로그인 실패** — `POST /api/v1/auth/login` 에 `{"loginId":"mapqb3481","password":"test123!@"}` → **401 `USER102`**(아이디/비밀번호 불일치). 요청 바디 shape 은 서버 `UserLoginRequest(loginId, password)` 와 일치하므로 **계정 자체가 유효하지 않다**(SMS 가입 전환/DB 초기화 추정). 29a(auth) 담당의 확인 및 신규 QA 계정 발급이 필요하다.
4. **백엔드 Swagger 오기** — `DispatchCallController:28` 의 `DRIVER_CANNOT_RECEIVE` 403 표기는 실제 409 다(§3-G).

### 4-H. 복구 구조 단순화 가능성 — **없음** (지시서 5번 항목 결론)

| 확인 항목 | 결과 |
|---|---|
| C4 의 `isCallOpen` 가드가 풀렸는가 | **아니다.** `DispatchService.getDetailRoom` 에 그대로 있다 |
| "내 진행 중 운행 조회" API 가 생겼는가 | **아니다.** `hasOngoingRide` 사용처는 `acceptCall` 가드 1곳뿐이고 어떤 컨트롤러도 노출하지 않는다. `PartyAccess` 전체에 기사 기준 조회 메서드 없음 |
| 대안 엔드포인트가 생겼는가 | 신규는 `GET /matching/rooms/{partyId}/driver`(승객용 `DriverSummary`)뿐 — 복구에 쓸 수 없다 |

→ **`matching/rooms/{partyId}` 를 복구 원천으로 쓰는 현재 구조를 유지하는 것이 옳다.** 응답 shape 도 호환이고(§2-E), `PartyController` 는 taxi 플래그 게이트가 없어 플래그가 꺼져 있어도 살아 있다는 **추가 이점**이 확인됐다 — §4-C 가 이 성질에 의존한다.

> 단순화가 가능해지는 조건을 백엔드에 제안해 둘 가치는 있다: **`GET /api/v1/dispatch/rides/me`** (`@CurrentDriver`, 진행 중인 `DRIVER_ASSIGNED`·`IN_RIDE` 방 1건 또는 204). 생기면 `restoreOngoingTrip` 의 "저장된 partyId + 소유자·상태 교차검증" 3단 판정이 단일 호출로 접히고, 단말 저장(`writeActivePartyId`) 의존도 사라진다.

---

## 플래그 요약

| 플래그 | 내용 |
|---|---|
| **실서버 검증 — 부분 완료** | `GET /api/v1/config`·`GET /v3/api-docs` 는 200 으로 실측 완료. **dispatch 엔드포인트의 200 응답 본문은 획득하지 못했다** (서버에 경로 자체가 없음 + QA 계정 401). dispatch 스펙 확정은 **컨트롤러·서비스·DTO 소스 + `b9a921d..HEAD` git diff 대조** 기준이다. |
| **상태 변경 호출 없음** | 수행한 요청은 `POST /auth/login`(401 실패), `GET /v3/api-docs`, `GET /api/v1/config`, 미인증 `GET /dispatch/calls/1/status`·`/dispatch/calls/1`·`/driver/me`·`/matching/rooms/1`(전부 401). online·accept·board·complete **미호출**. 서버 기동/종료 **미수행**. |
| **코드 수정 없음** | 백엔드·기사 앱 모두 읽기만 수행. 산출물은 이 보고서 1개. |
| **백엔드 미구현 — 더미 유지(변동 없음)** | `getMissedCalls`·`getPromotion`·`getSettlementDetail`·`getRatingSummary`·`getTripHistory`·`getTripDetail` — 이번 273 커밋에서도 서버 측 신설 없음. 더미 위임 유지가 맞다. |
| **서버 미제공 — 앱 로컬 규칙 유지** | 호출료 3,000 / 합승 보너스 1,500 / 수수료 5% / 카운트다운 15초. `complete` 가 정산 내역을 돌려주지 않는 점도 변동 없음(`RideService.complete` 는 `void`). |
