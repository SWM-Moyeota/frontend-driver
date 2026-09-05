# 10 qa-verifier — API 연동 경계면 검증 리포트

검증 대상: `_workspace/09_api-integrator_remote.md` 산출물 (RemoteDriverRepository 실연동)
검증 방식: 양쪽 동시 읽기(앱 ↔ `../backend` 컨트롤러/DTO 소스) + **실서버 대조**(백엔드가 이미 기동 중이어서 curl GET 프로브 수행 — 서버를 새로 띄우지 않았고, 상태를 바꾸는 POST/DELETE 프로브는 하지 않음).

## 결과 요약

| # | 항목 | 결과 |
|---|------|------|
| 1 | API 경로·메서드 전건 대조 (13 실연동 + 5 선언만) | **통과** |
| 2 | 요청 body shape (4종) | **통과** |
| 3 | 응답 shape · 래핑 여부 · nullable 비대칭 | **통과** (실응답 대조 포함) |
| 4 | 매퍼→도메인 (필드·단위·절사 규칙) | **통과** (경미 1건: A) |
| 5 | Repository↔화면 예외 전파 (D10 accept · D16 complete) | **통과** |
| 6 | id 흐름 (partyId↔CallSummary.id, 더미 id 격리) | **통과** (경미 1건: G는 id 회수 경로) |
| 7 | 빌드 + 유닛 테스트 | **통과** (assembleDebug 성공, DispatchMappersTest 10/10) |

**결함: 차단(blocking) 0건, 경미 2건(A·G), 참고 3건(N1~N3).**

## 1. API 경로·메서드 — 통과

앱 `DriverApi.kt`·`DispatchApi.kt` ↔ 백엔드 `DriverController`·`DispatchCallController`·`DriverLocationController`·`RideController` 전건 일치.

- driver 8건: POST `/api/v1/drivers`(200+body — 유일하게 204가 아님, 앱도 DTO 수신으로 일치), POST `…/{driverId}/verify`, POST `…/{driverId}/vehicle`, POST·DELETE `…/{driverId}/call`, GET `…/users/{userId}`, PUT·DELETE `…/{driverId}/fcm-token` — path variable 이름까지 일치.
- dispatch 10건: online POST·DELETE, location POST, calls accept/reject POST, calls status GET(`driverId`는 **@RequestParam** — 앱 `@Query` 일치), calls 상세 GET `/{partyId}/{driverId}`(백엔드 Java 시그니처는 `(driverId, partyId)` 순이지만 Spring은 이름 바인딩 — 앱의 path 순서 정확), rides arrive/board/complete POST, rides GET `/{partyId}/{memberId}`.
- `arrive` 미호출은 안전 확인: `RideService.board`는 DRIVER_ASSIGNED → IN_RIDE 직접 전이이고 `arrive`는 승객 알림 전용(상태 전이 선행조건 아님). RideService.java 참조.

## 2. 요청 body — 통과

| 앱 DTO | 백엔드 record | 대조 |
|---|---|---|
| `RegisterDriverRequestDto(userId:Long, qualificationNumber, bankName, accountNumber)` | `RegisterDriverRequest` 동일 4필드 | 일치 |
| `RegisterVehicleRequestDto(seats:Int, plateNumber, type)` | `RegisterVehicleRequest(@Min(2) seats, …)` | 일치. 앱 DEFAULT_SEATS=4 ≥ 2 충족 |
| `LocationReportRequestDto(latitude:Double, longitude:Double)` | `LocationReportRequest` | 일치 (JSON 이름 바인딩 — 선언 순서 무관) |
| `CompleteRideRequestDto(fare:Int)` | `CompleteRideRequest(@Positive fare)` | 일치. D16 `FareViewModel.submit`이 `meterFare <= 0` 가드 → @Positive 위반 불가 |

## 3. 응답 shape — 통과 (실응답 대조)

- **공통 래퍼 없음** 확정: 컨트롤러가 DTO를 직접 반환하며, 실서버 curl로 재확인 —
  - `GET /api/v1/dispatch/calls/999/status?driverId=1` → 200 `{"open":false}` (CallStatusResponseDto 일치)
  - `GET /api/v1/dispatch/calls/999/1` → 409 `{"code":"CALL_CLOSED","message":"호출받지 않았거나 이미 마감된 콜입니다."}` (ErrorResponseDto 일치)
  - `GET /api/v1/drivers/users/1` → 404 `{"code":"DRIVER_NOT_REGISTERED",…}` (현 서버 DB에 driver 미등록 상태)
- `DriverResultDto` ↔ `DriverResult(id, userId, status:enum, callEnabled)` — enum은 문자열 직렬화, 앱 `status: String?` 수신 적합.
- `PartySummaryDto` ↔ `matching.api.PartySummary` — 11필드 이름·타입 일치. 서버 primitive(double/int)를 앱이 nullable+기본값으로 받는 것은 방어적 방향의 비대칭(안전). `estimatedFare`·`estimatedTime`·`driverId`의 서버 Integer/Long(null 가능)을 앱도 nullable로 수신 — 대칭.
- `NetworkModule` Json: `ignoreUnknownKeys` + `coerceInputValues` — 서버 필드 추가·null 방어 적절. cleartext HTTP(10.0.2.2)는 매니페스트 `usesCleartextTraffic="true"`로 허용 확인.
- 에러코드 6종(CALL_CLOSED 등)은 백엔드 `DriverErrorCode`·`DispatchErrorCode`와 일치. 추가로 DRIVER_NOT_REGISTERED(404)·NOT_AWAITING_PICKUP(409) 등도 존재(산출물 미기재였으나 앱 처리엔 영향 없음 — 메시지 그대로 노출 원칙).

## 4. 매퍼→도메인 — 통과 (경미 A)

- 필드 뒤바뀜 없음: departure→pickupPlace, destination→dropoffPlace, estimatedFare→expectedFare 등 확인.
- 수수료 규칙 3곳 일치: `DispatchMappers.calcFareResult`(`(meter+callFee)*5/100/10*10`) ≡ `DummyDriverRepository.submitFinalFare:245` — 5% 후 10원 단위 내림 동일. 더미 이력 데이터(serviceFee 1070 = (18400+3000)×5%)와도 정합.
- 하버사인 0.1km 반올림, ETA(km×2분, 최소 1, 미상 5분), countdown 15초 — 산출물 규칙대로 구현·테스트됨.

**[A] 경미** — `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchMappers.kt:40` `id = (id ?: 0L).toString()`: 서버 id가 null이면 콜 id가 `"0"`(숫자 파싱 가능)이 되어 이후 액션이 partyId=0으로 remote 경로를 타게 됨. 서버 `PartySummary.id`는 실질 항상 존재해 위험 낮음. 수정 제안(api-integrator): null id는 매핑 실패로 예외 처리하거나 더미 판정용 non-numeric sentinel(`"invalid"`) 사용.

## 5. Repository↔화면 예외 전파 — 통과

- **D10 accept**: `CallDetailScreen.kt:95-108` — 실패 시 `submitting` 리셋 + `actionError` 세팅, UiState는 Success 유지 → 화면 유지 + 재시도 가능. decline 동일 패턴.
- **D16 complete**: `FareScreen.kt:103-121` — 실패 시 Success 상태 유지 + `submitError`, `meterFare`는 `rememberSaveable`로 보존 → 입력값 보존 + 재시도 가능. 성공 시에만 `result` 세팅 → HOME 복귀.
- RemoteDriverRepository의 전파 경계 확인: acceptCall(:195)·declineCall(:205)·confirmBoarding 첫 board(:230)·submitFinalFare(:267)는 try-catch 없이 전파, 조회·가입·영업 토글은 더미 강등 — 산출물 실패 정책과 코드 일치. `CancellationException` re-throw도 전 지점 처리.

## 6. id 흐름 — 통과

- 왕복 일관성: 매퍼 `id = partyId.toString()` → 액션에서 `callId.toLongOrNull()`/`tripId.toLongOrNull()` 복원. `ActiveTrip.id`도 동일 문자열이라 board/complete의 partyId 복원 성립(`requireNotNull` 가드 포함).
- 더미 id 격리: 더미 콜 id는 `"call-1"~"call-3"`, 더미 trip id는 `"trip-call-1"` 형식(DummyDriverRepository:51,57,63,167) — 전부 숫자 파싱 불가 → remote 경로로 새지 않음. 역방향(실 partyId가 더미 경로로)은 `toLongOrNull` 성공 시 무조건 remote이므로 발생 불가.
- `confirmBoarding`/`submitFinalFare`의 `trip.id != tripId → fallback` 가드로 remote trip과 더미 trip 혼선 차단.

**[G] 경미** — `data/src/main/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepository.kt:95-98` `checkQualifications`: register 실패 시 **모든** `HttpException`(400 validation 포함)을 "409 기가입" 케이스로 간주하고 `getByUserId` 회수를 시도. 400이면 회수도 404로 실패 → 더미 강등이라 크래시는 없지만, 주석("409 DRIVER_ALREADY_REGISTERED")과 실제 동작이 다르고 잘못된 입력이 더미 통과로 위장될 수 있음. 수정 제안(api-integrator): `if (e.code() == 409) getByUserId(userId) else throw e` (또는 else 더미 강등)로 409 한정.

## 7. 빌드·테스트 — 통과

`./gradlew :app:assembleDebug :data:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL (exit 0).
`data/build/test-results/testDebugUnitTest/TEST-….DispatchMappersTest.xml`: tests=10, failures=0, errors=0.

## 참고 (결함 아님)

- **[N1] 실 remote 콜 경로는 현재 UI에서 도달 불가**: 콜 목록(`getCalls`)이 더미 전용(id 비숫자)이고 FCM 미도입이라, 숫자 callId가 화면에 유입될 경로가 없음 — accept/board/complete 실연동은 FCM 또는 콜 유입 경로가 생겨야 실제 발동. 산출물 §3에 이미 기재된 한계와 일치.
- **[N2] 실서버 DB에 driver 미등록 상태**(GET /drivers/users/1 → 404): 가입 플로우를 타지 않고 진입하면 `resolveDriverId`가 DEFAULT_DRIVER_ID=1 폴백(캐시 안 함) — 설계대로. dispatch 액션은 서버에서 4xx가 나며 ViewModel 에러 분기로 흡수됨.
- **[N3] CALL_FEE=3000 상수 3곳 중복**: `DispatchRules.CALL_FEE`, `DummyDriverRepository:244`, `FareScreen.kt:64`(표시용). 현재 값 일치하나 규칙 변경 시 3곳 동시 수정 필요 — 상수 단일화 권장(우선순위 낮음).

## 미검증 (사유 명시)

- 상태 변경 액션(POST register/accept/board/complete)의 **실서버 왕복**: 기동 중인 서버 DB를 오염시키므로 curl 프로브 생략. 컨트롤러 소스 + GET 프로브로 shape만 확정.
- 에뮬레이터 실기: 지시에 따라 이번 회차 제외.
