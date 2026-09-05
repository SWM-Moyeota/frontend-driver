# 09 api-integrator — 백엔드 실연동 (RemoteDriverRepository)

**플래그: 실서버 미검증** — 백엔드 미기동 상태. 모든 스펙은 컨트롤러·DTO 소스
(`../backend/src/main/java/team/codingforest/moyeota/{driver,dispatch}/…`) 기준으로 확정했다.
`../backend/http/*.http`(chat/matching/place)에는 기사·dispatch 예시가 없다.

빌드/테스트: `./gradlew :app:assembleDebug :data:testDebugUnitTest` 통과 (매퍼 테스트 10/10).

## 1. 연동 엔드포인트 표 (실연동)

| 메서드 + 경로 | Repository 메서드 | 비고 |
|---|---|---|
| POST `/api/v1/drivers` | `checkQualifications` | 가입. body `{userId, qualificationNumber, bankName, accountNumber}` → `DriverResult` |
| POST `/api/v1/drivers/{driverId}/verify` | `checkQualifications` | 가입 직후 이어 호출. 204 |
| GET `/api/v1/drivers/users/{userId}` | `checkQualifications`·driverId 회수 | 이미 가입(409) 시 기존 id 회수 |
| POST `/api/v1/drivers/{driverId}/vehicle` | `registerVehicle` | body `{seats, plateNumber, type}` → 204 |
| POST·DELETE `/api/v1/drivers/{driverId}/call` | `registerVehicle` (acceptsPool) | 콜 수신 on/off (`driver.setting.callEnabled`). 204 |
| POST `/api/v1/dispatch/online/{driverId}` | `setDutyStatus(true)` | body `{latitude, longitude}` → 204 |
| DELETE `/api/v1/dispatch/online/{driverId}` | `setDutyStatus(false)` | 204 |
| GET `/api/v1/dispatch/calls/{partyId}/{driverId}` | `getCallDetail`, `acceptCall`(사전 조회) | → `PartySummary`. 콜 후보 아니면 409 CALL_CLOSED |
| POST `/api/v1/dispatch/calls/{partyId}/accept/{driverId}` | `acceptCall` | 204. 상태 전이 액션 — 실패 시 예외 전파 |
| POST `/api/v1/dispatch/calls/{partyId}/reject/{driverId}` | `declineCall` | 204 |
| POST `/api/v1/dispatch/rides/{partyId}/board/{driverId}` | `confirmBoarding` (첫 탑승 1회) | 204. 서버는 파티 단위 board — 승객별 체크는 로컬 전이 |
| POST `/api/v1/dispatch/rides/{partyId}/complete/{driverId}` | `submitFinalFare` | body `{fare}` → 204. FareResult 는 로컬 계산 |

API 인터페이스에는 선언만 해둔 것: `GET /calls/{partyId}/status`(callStatus), `POST /dispatch/location/{driverId}`(reportLocation, 위치 스트림 연동 시 사용), `PUT·DELETE /drivers/{driverId}/fcm-token`(FCM 도입 시), `POST /rides/{partyId}/arrive/{driverId}`(Repository 인터페이스에 대응 메서드 없음 — 미호출), `GET /rides/{partyId}/{memberId}`(승객용).

## 2. 실제 응답 shape (컨트롤러 소스 기준 — 실서버 미검증)

- `DriverResult`: `{id: Long, userId: Long, status: "PENDING"|"VERIFIED", callEnabled: Boolean}`
- `PartySummary`: `{id, departureLatitude, departureLongitude, destinationLatitude, destinationLongitude, departure, destination, memberCount, estimatedFare(Int?), estimatedTime(Int?), driverId(Long?)}`
- `CallStatusResponse`: `{open: Boolean}`
- `DriverLocationResponse`: `{longitude, latitude}`
- 공통 4xx: `{code: String, message: String}` (`ErrorResponse`) — 한국어 message 포함. 주요 코드:
  CALL_CLOSED(409), DRIVER_CANNOT_RECEIVE(409), DRIVER_ALREADY_RIDING(409), DRIVER_ALREADY_REGISTERED(409), DRIVER_NOT_PENDING(409), PARTY_NOT_FOUND(404)
- 액션류는 전부 **204 No Content** — Retrofit suspend fun Unit 반환으로 수신.

## 3. 더미 위임 목록 (백엔드 미구현 — 더미 연동)

| Repository 메서드 | 사유 |
|---|---|
| `login` / `requestOtp` / `verifyOtp` | 인증 API 없음 (백엔드 TODO: 토큰 도입 전) |
| `getHomeSummary` | 오늘 수입·운행수·영업시간 요약 API 없음. 영업 상태만 setDutyStatus 에서 더미와 동기화 |
| `getCalls` (D09 콜 리스트) | **조회 경로 없음** — 콜은 `DispatchService.dispatch` → `CallCandidates`(Redis, partyId 키) 적재 후 FCM push(`CallNotifier`)로만 전달. driverId → 열린 partyId 역인덱스 부재로 목록 조회 불가. 콜 리스트는 더미 유지 |
| `getMissedCalls` / `getPromotion` | 해당 API 없음 |
| `markNoShow` / `completeDropoff` | 노쇼·승객별 하차 API 없음 — remote trip 은 로컬 상태 전이, 더미 trip 은 더미 위임 |
| `getSettlementDetail` / `getRatingSummary` / `getTripHistory` / `getTripDetail` | 정산·평점·이력 API 없음 |

부분 위임: `getCallDetail`·`acceptCall`·`declineCall`은 callId 가 partyId(숫자)일 때만 실연동, 더미 콜 id("call-1" 등)는 더미 위임. `getActiveTrip` 은 remote 수락 trip 우선, 없으면 더미.

## 4. driverId · partyId 매핑 전략

- **userId**: 인증 미구현 → `DEFAULT_USER_ID = 1L` 고정 (RemoteDriverRepository 생성자 파라미터).
- **driverId**: 가입(POST /drivers) 응답 `id` 를 인메모리 캐시(`cachedDriverId`). 미가입 진입(더미 로그인)은 최초 필요 시 `GET /drivers/users/{userId}` 로 회수, 그것도 실패하면 `DEFAULT_DRIVER_ID = 1L` 폴백 — 폴백 값은 캐시하지 않아 서버 복구 시 실제 id 로 회복.
- **CallSummary.id ↔ partyId**: 서버 유래 콜은 `id = partyId.toString()`. `ActiveTrip.id` 도 동일 — trip 액션에서 `tripId.toLong()` 으로 partyId 복원. 숫자 파싱 불가 id 는 더미 데이터 소속으로 판정.
- **가입 매핑**: `checkQualifications(license…, taxiCertNumber)` → `qualificationNumber = taxiCertNumber`, 은행 계좌는 화면 입력이 없어 기본값("국민"/"000000000000") 전송. 등록+verify 성공 시 5항목 전체 통과로 취급 (서버에 개별 자격 조회 없음). `registerVehicle` 은 `type = vehicleModel`, `seats = 4`; **companyName 은 서버 스펙에 없어 미전송**.

## 5. 실패 정책 (액션 성공 → 갱신 지점)

- 조회·가입·영업 토글: 서버 불가 시 더미로 조용히 강등 — 서버가 죽어도 앱 전체 플로우 유지.
- 상태 전이 액션(acceptCall/declineCall/board/complete, 실콜 한정): 예외 전파 — 화면 유지 + 재시도 원칙. 4xx body 는 `{code, message}` (한국어 message → ViewModel 에서 그대로 노출 가능).
- accept 성공 시 서버가 콜 후보를 비우므로(CALL_CLOSED) **파티 상세를 accept 전에 조회**해 ActiveTrip 구성.
- 액션 성공 후 refresh: acceptCall → ActiveTrip 반환(D11 진입), submitFinalFare → FareResult 반환 + activeTrip 해제(D16 완료 → 홈 refresh 권장).

## 6. 매핑 채움 규칙 (서버 미제공 값 — 더미와 동일한 앱 규칙)

- callFee 3,000원 고정 / poolBonus: memberCount≥2 → 1,500원 / 수수료 5% 10원 단위 절사.
- 거리: 기사 최근 좌표(기본: 강남역 37.4980,127.0276) ↔ 출발지 하버사인, 0.1km 단위.
- 파티는 출발·도착 각 1곳 → stops = [PICKUP, DROPOFF] 2개. 승객 이름 미제공 → "승객1"… / "승객 N인".
- countdown 15초 고정, ETA = 거리×2분(최소 1, 미상 5분).

## 7. 변경/추가 파일

- `data/src/main/kotlin/com/moyeota/driver/data/remote/dto/DriverDtos.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/dto/DispatchDtos.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/DriverApi.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchApi.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchMappers.kt` (신규)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/NetworkModule.kt` (신규 — baseUrl `http://10.0.2.2:8080/` 상수, Retrofit 타입 비노출)
- `data/src/main/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepository.kt` (신규)
- `data/src/test/kotlin/com/moyeota/driver/data/remote/DispatchMappersTest.kt` (신규, 10 테스트)
- `app/src/main/kotlin/com/moyeota/driver/app/AppContainer.kt` (수정 — `USE_REMOTE` 토글, 기본 true)

**domain·presentation 무변경** — `DriverRepository` 인터페이스·도메인 모델 그대로 (필드 추가 없이 연동 가능했음).
