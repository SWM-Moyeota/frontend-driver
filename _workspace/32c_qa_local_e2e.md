# 32c. L3 — 로컬 백엔드 E2E (기사 가입 + 콜 흐름 실측)

담당: api-integrator · 대상: PR #27 `feature/backend-sync-sms-auth`
백엔드: `/Users/sungyoon/Desktop/moyeota/backend` @ `d04b54c` (HEAD, 미커밋 변경 없음)
실행 시각: 2026-10-13 10:36~10:50 KST

## 0. 결론 요약

| 항목 | 결과 |
|---|---|
| 로컬 기동 | **성공** — `local` 프로필, 기동 4.1초 |
| 기사 E2E | **끝까지 통과** — 문자 인증 → 가입 → 로그인 → 기사 등록/검증 → 영업 → 콜 → 수락 → 도착 → 탑승 → 운행 종료 |
| 배포 서버 제약 해소 여부 | **해소됨** — `taxiEnabled=true`, 기사·배차 컨트롤러 28개 전부 등록 |
| 클라이언트 수정 필요 | **없음** (P1/P2 결함 0건). 계약 전제 3건이 실측으로 **검증 확인**됨 |
| 백엔드 이슈 | 3건 (문서↔코드 불일치 2, 자기 방 콜 1) — 모두 클라이언트 영향 없음 |

**핵심**: `moyeota.taxi.enabled` 는 기사/배차 컨트롤러를 `@ConditionalOnProperty` 로 통째로 켜고 끈다.
로컬 기본값이 `true`(`TAXI_ENABLED:true`)라 **로컬에서는 기사 E2E 가 전 구간 가능하다.**
배포 서버가 막혀 있던 건 플래그 때문이지 구현 부재가 아니었다.

---

## 1. 기동 요건 (실측)

### 1.1 필요한 것 / 필요 없는 것

| 의존 | 필요? | 비고 |
|---|---|---|
| **Redis** | **필수** | `local` 프로필이 `localhost:6379` 를 본다. 문자 인증 코드·기사 위치 GEO·콜 후보·인증 캐시가 전부 Redis |
| Postgres | **불필요** | `local` 은 `application.yaml` 기본값인 **H2 인메모리**(`MODE=PostgreSQL`) + `flyway.enabled=false`. Postgres 는 `dev`/`prod` 전용 |
| Firebase 키 | 있음 (선택) | `secrets/moyeota-service-account.json` 존재 → `FcmCallNotifier` 가 `@Primary` 로 활성. 없어도 `LoggingCallNotifier` 로 폴백 |
| SOLAPI 키 | **불필요** | `moyeota.sms.provider` 기본값 `log` → 실제 문자 미발송, 코드를 **로그에 평문 출력** |
| Naver/Kakao 키 | 있음 (경로 계산에 필요) | `application-local.yaml` 에 포함. 방 생성 시 경로·예상요금 산출에 실제 호출됨 (인터넷 필요) |
| JDK 25 | 필수 | 설치됨 (Temurin 25.0.2). Gradle 9.5.1 wrapper |

### 1.2 포트 점유 — 기동 전 전부 비어 있었음

```
8080 free / 3306 free / 6379 free / 3307 free / 6380 free
```
→ **남의 프로세스를 죽인 일 없음.** 8080·6379 만 새로 점유했다.

### 1.3 Docker

`docker info` 결과 **클라이언트만 있고 데몬 미기동**이었다 (`Cannot connect to the Docker daemon`).
`open -a Docker` 로 Docker Desktop 을 띄워 해결 (기존 컨테이너 0개, 죽인 것 없음).

주의 — `docker compose up -d redis` 는 **새로 만든 게 아니라 2026-09-28 에 만들어져 멈춰 있던
`moyeota-redis` 컨테이너를 재시작**했다. 그 안에 이전 세션의 `sms:*` 키 11개가 남아 있었고
**그대로 보존**했다(`flushall` 등 미실행). 종료도 데이터를 지우지 않는 `docker stop` 으로 한다.

### 1.4 프로필별 `taxi.enabled` / `SMS_FIXED_CODE`

| | `moyeota.taxi.enabled` | `moyeota.sms.fixed-code` | DB |
|---|---|---|---|
| `local` | `true` (`application.yaml` 의 `${TAXI_ENABLED:true}`) | **정의 없음 → 빈 값 = 랜덤 코드** | H2 인메모리 |
| `dev` | `true` (동일 기본값 상속) | `${SMS_FIXED_CODE:000000}` → **000000** | Postgres + Flyway |

`local` 에는 고정번호 설정이 **없다.** 두 가지 방법 중 하나를 쓴다.

1. 기동 인자로 주입 (이번에 쓴 방법) — `--moyeota.sms.fixed-code=000000`
   `SmsVerificationService` 가 `Assert` 로 `provider=log` + 숫자 6자리만 허용한다.
2. 서버 로그에서 랜덤 코드를 읽는다 — `LoggingSmsSender` 가 평문으로 찍는다.
   ```
   [SMS 미발송] to=010-****-0001 text=[모여타] 인증번호 [000000]를 입력해 주세요.
   ```

### 1.5 기동 명령 (재현용)

```bash
cd /Users/sungyoon/Desktop/moyeota/backend
open -a Docker                       # 데몬이 꺼져 있을 때만
docker compose up -d redis           # Postgres 는 local 프로필에 불필요
./gradlew bootRun --console=plain \
  --args='--spring.profiles.active=local --moyeota.sms.fixed-code=000000'
```

기동 로그: `Started MoyeotaApplication in 4.071 seconds`

### 1.6 taxi 컨트롤러 등록 확인

`GET /api/v1/config` → `{"taxiEnabled":true}`
`GET /v3/api-docs` 에 기사·배차 엔드포인트 **전부 등록됨**:

```
/api/v1/drivers              POST
/api/v1/drivers/me           GET
/api/v1/drivers/verify       POST
/api/v1/drivers/vehicle      POST
/api/v1/drivers/call         POST, DELETE
/api/v1/drivers/fcm-token    PUT, DELETE
/api/v1/dispatch/online      POST, DELETE
/api/v1/dispatch/location    POST
/api/v1/dispatch/calls/{partyId}         GET
/api/v1/dispatch/calls/{partyId}/status  GET
/api/v1/dispatch/calls/{partyId}/accept  POST
/api/v1/dispatch/calls/{partyId}/reject  POST
/api/v1/dispatch/rides/{partyId}         GET
/api/v1/dispatch/rides/{partyId}/arrive  POST
/api/v1/dispatch/rides/{partyId}/board   POST
/api/v1/dispatch/rides/{partyId}/complete POST
/api/v1/auth/sms/send        POST
/api/v1/auth/sms/verify      POST
/api/v1/matching/rooms/{partyId}  GET
```

> 에뮬레이터에서 이 서버를 쓰려면 `NetworkModule.BASE_URL` 을 `http://10.0.2.2:8080/` 로 바꿔야 한다
> (현재 `https://api.moyeota.p-e.kr/`). **L2 담당 영역이라 이번에 건드리지 않았다.**

---

## 2. HTTP E2E 시나리오 결과

모든 요청 바디는 `data/src/main/kotlin/.../remote/dto/*.kt` 의 클라이언트 DTO shape 그대로 보냈다.

### 2.1 문자 인증 → 가입 → 로그인

| # | 요청 | 결과 | 응답 |
|---|---|---|---|
| 1 | `POST /api/v1/auth/sms/send` `{"phoneNumber":"010-9999-0001"}` | **204** | (본문 없음) |
| 2 | `POST /api/v1/auth/sms/verify` `{"phoneNumber":"...","code":"000000"}` | **200** | `{"verificationToken":"7f10aff5-2991-4e99-9ac6-6376d48fbc38"}` |
| 3 | `POST /api/v1/auth/register` (클라 `UserRegisterRequestDto` 전체 9필드) | **201** | `{"uuid":"01a132e9-...","name":"기사큐에이"}` |
| 4 | `POST /api/v1/auth/login` | **200** | `{"accessToken":"eyJ...","refreshToken":"eyJ..."}` |

**클라이언트 DTO 대조 — 전부 일치.**
- `SmsVerifyResponseDto.verificationToken` ✓
- `UserResponseDto{uuid, name}` ✓ — 내부 Long `userId` 는 응답에 **없다**(DTO 주석과 일치). `name` 은 닉네임이 들어온다
- `TokenResponseDto{accessToken, refreshToken}` ✓

> `local` 프로필의 `access-token-validity: 2m` 때문에 E2E 중 토큰이 계속 만료됐다.
> 시나리오마다 재로그인했다. 실기 테스트 시 2분마다 reissue 가 도는 셈이니 refresh 회전 경로도 덤으로 밟힌다.

### 2.2 기사 등록 → 검증 → 조회

| # | 요청 | 결과 | 응답 |
|---|---|---|---|
| 5 | `GET /api/v1/drivers/me` (등록 전) | **404** | `{"code":"DRIVER_NOT_REGISTERED","message":"기사로 등록되지 않은 유저입니다."}` |
| 6 | `POST /api/v1/drivers` (`vehicle` 중첩 객체 포함) | **200** | `{"id":1,"userId":1,"name":"기사큐에이","status":"PENDING","callEnabled":true}` |
| 7 | `POST /api/v1/drivers/verify` | **204** | |
| 8 | `GET /api/v1/drivers/me` (검증 후) | **200** | `{"id":1,"userId":1,"name":"기사큐에이","status":"VERIFIED","callEnabled":true}` |

**클라이언트 DTO 대조 — 전부 일치.**
- `RegisterDriverRequestDto{qualificationNumber, bankName, accountNumber, vehicle{seats, plateNumber, type}}` ✓
- `DriverResultDto{id, userId, name, status, callEnabled}` ✓ — `name` = 유저 닉네임 확인, `callEnabled` 기본 `true` 확인
- **`DRIVER_NOT_REGISTERED` 실측 확인** → 클라의 `ServerErrorCodes.isDriverMissing()` 전제 성립.
  PR #27 의 "404 오진" 수정(코드 없는 404 를 미등록으로 읽지 않기)이 올바른 판단이었음이 확인된다.

> 참고: `POST /api/v1/drivers` 는 **200**이다(201 아님). 클라는 상태코드로 분기하지 않아 무해.

### 2.3 영업 시작 → 콜 후보 생성

| # | 요청 | 결과 |
|---|---|---|
| 9 | `POST /api/v1/dispatch/online` `{"latitude":37.4979,"longitude":127.0276}` | **204** |
| 10 | `POST /api/v1/dispatch/location` | **204** |
| 11 | `POST /api/v1/matching/rooms` (승객 계정, `capacity:1`) | **200** `status:"MATCHING"`, `estimateFare:17700`, `estimateTime:25` |
| 12 | `GET /api/v1/dispatch/calls/1/status` | **200** `{"open":true}` |

Redis 실측:
```
drivers:location   → GEO member "1" @ (127.0277, 37.4980)
drivers:heartbeat:1 → TTL 30
```
서버 로그: `기사 호출 partyId=..., radius=1000m, 신규=1명`

**경로 산출이 실제로 동작했다** — `경로 산출(네이버) key=37.4979,127.0276:37.3948,127.1112, fare=17700, minutes=25`.
즉 로컬 E2E 는 Naver 경로 API 까지 실제로 타며, 예상 요금/시간이 더미가 아니다.

#### ⚠️ 첫 시도는 "신규 후보 없음" 으로 실패했다 — 원인 규명

```
INFO DispatchService : 신규 후보 없음 partyId=1, radius=1000m, 기존 후보=0명
```

원인은 **`DriverLocationRedis.HEARTBEAT_TTL = 30초`**. `findNearby` 가 GEO 검색 결과를
`isAlive()`(= `drivers:heartbeat:{id}` 키 존재)로 한 번 더 거른다. 내가 스펙을 읽는 동안
30초가 지나 하트비트가 만료돼 기사가 후보에서 빠졌다. GEO 좌표는 TTL 이 없어 그대로 남아 있었다.

```
geosearch drivers:location ... byradius 1000 m  →  1   (좌표는 살아 있음)
ttl drivers:heartbeat:1                         →  -2  (하트비트는 만료)
```

**클라이언트는 이미 대응돼 있다** — `RemoteDriverRepository.HEARTBEAT_INTERVAL_MS = 15_000L`
(30초 TTL 의 절반). 주석에도 "QA 리포트 18 실측" 으로 근거가 적혀 있다.
이번 실측은 그 15초 주기가 **여전히 유효한 선택**임을 독립적으로 재확인한 것이다. 수정 불필요.

### 2.4 콜 수락 → 운행 → 종료

정상 순서(accept → arrive → board → complete) 2회 전부 통과.

| # | 요청 | 결과 |
|---|---|---|
| 13 | `GET /api/v1/dispatch/calls/{id}` (콜 상세) | **200** `{"id":1,"departureLatitude":37.4979,...,"memberCount":1,"estimatedFare":17700,"estimatedTime":25,"driverId":null}` |
| 14 | `POST /api/v1/dispatch/calls/{id}/accept` | **204** |
| 15 | `POST /api/v1/dispatch/rides/{id}/arrive` | **204** |
| 16 | `POST /api/v1/dispatch/rides/{id}/board` | **204** |
| 17 | `POST /api/v1/dispatch/rides/{id}/complete` `{"fare":15500}` | **204** |

**클라이언트 DTO 대조 — 전부 일치.**
- `PartySummaryDto` — `departureLatitude`/`departureLongitude` (긴 이름) ✓.
  `PartyDetailDto` 는 `departureLat`/`departureLng` (짧은 이름) ✓.
  **두 응답의 좌표 필드명이 실제로 다르다**는 DTO 주석이 실측으로 확인됐다. 둘 다 올바르게 매핑돼 있다.
- `CallStatusResponseDto{open}` ✓ / `CompleteRideRequestDto{fare}` ✓

### 2.5 상태 전이 실측 (복구 계약 = B6/27번 전제)

`GET /api/v1/matching/rooms/{partyId}` 를 **기사 토큰으로** 조회해 단계별 상태를 찍었다.

| 단계 | `status` | `taxiDriverId` |
|---|---|---|
| 방 생성 직후 | `MATCHING` | `null` |
| accept 후 | `DRIVER_ASSIGNED` | **1** |
| **arrive 후** | `DRIVER_ASSIGNED` (**변화 없음**) | 1 |
| board 후 | `IN_RIDE` | 1 |
| complete 후 | `FINISHED` | 1 |

`arrive` 가 상태를 바꾸지 않는다는 점이 실측으로 확인됐다 — **순수 알림 트리거**(승객 FCM `DRIVER_ARRIVED`)다.
그래서 복구 시 "도착 통보를 이미 보냈는지" 는 서버 상태로 알 수 없다. 클라가 `arrive` 를
멱등 호출로 취급하는 현재 구조가 맞다.
매칭 타임아웃(3분) 시 방은 `CANCELED` 가 된다 — 실측: `타임아웃으로 방 해산 partyId=3` → `status: CANCELED`.

`members[].nickname` = `"승객큐에이"` — **기사 토큰으로 승객 실닉네임 조회 가능** 확인
(26번 "승객 실닉네임 주입" 전제 성립).

### 2.6 409 재수락 ↔ 소유권 확인 (B6 로직 전제) — **검증 성립**

```
POST /dispatch/calls/1/accept   → 204
POST /dispatch/calls/1/accept   → 409 {"code":"CALL_CLOSED",...}   (재수락)
GET  /matching/rooms/1          → 200 "taxiDriverId": 1
GET  /drivers/me                → 200 "id": 1
```

`taxiDriverId(1) == /drivers/me.id(1)` → **클라의 `claimOwnedTripOn409()` 전제가 그대로 성립한다.**
`CALL_CLOSED` 하나로 "남이 선점" 과 "내가 이미 수락" 을 구분할 수 없다는 문제 인식이 정확했고,
파티 소유권을 진실의 원천으로 삼은 설계가 실서버 응답으로 뒷받침된다.

추가 확인: **수락 후에는 `GET /dispatch/calls/{id}` 가 409** 가 된다
(`getDetailRoom` 이 `isCallOpen` 을 먼저 보기 때문). 그래서 수락 성공 뒤 운행 정보를
`matching/rooms` 에서 가져오는 클라 구조가 맞다.

---

## 3. 에러 코드 실측 (클라 분기 코드 전수 확인)

클라는 **표시 문구는 서버 `message` 를 쓰고 코드는 분기에만 쓴다**(`authFailureMessage`).
`ServerErrorCodes` 의 분기 코드를 실서버 응답으로 전부 맞춰 봤다.

| 코드 | 상황 | HTTP | 클라 사용처 | 일치 |
|---|---|---|---|---|
| `DRIVER_NOT_REGISTERED` | `GET /drivers/me` 미등록 | 404 | `isDriverMissing()` | ✓ |
| `PARTY_NOT_FOUND` | `GET /matching/rooms/99999` | 404 | `isPartyMissing()` | ✓ |
| `USER104` | `sms/send` 이미 가입된 번호 | **409** | `PHONE_NUMBER_DUPLICATED` | ✓ |
| `USER207` | `register` 토큰 위조 / 인증번호≠가입번호 | 400 | `SMS_NOT_VERIFIED` | ✓ |
| `USER202` | `sms/send` 1분 내 재전송 | **429** | 서버 message 표시 | ✓ |
| `USER204` | 코드 만료/미발송 번호 verify | 400 | 서버 message 표시 | ✓ |
| `USER205` | 코드 불일치 | 400 | 서버 message 표시 | ✓ |
| `USER102` | 로그인 실패 | 401 | 서버 message 표시 | ✓ |
| `INVALID_REQUEST` | 검증 실패 (`fare: 0보다 커야 합니다`) | 400 | 서버 message 표시 | ✓ |
| `CALL_CLOSED` | 재수락·마감된 콜·reject 재호출 | 409 | B6 소유권 재확인 | ✓ |
| `SELF_DISPATCH_NOT_ALLOWED` | 본인 참여 방 콜 수락 | 409 | B6 → null → 서버 message | ✓ |
| `NOT_AWAITING_PICKUP` | `arrive` 를 `IN_RIDE` 에서 호출 | 409 | `notifyPickupArrival` 이 삼킴 | ✓ |
| `NOT_RIDING` | `complete` 중복 | 409 | 예외 전파 | ✓ |
| `DRIVER_ALREADY_REGISTERED` | `POST /drivers` 중복 | 409 | 서버 message 표시 | ✓ |
| `DRIVER_NOT_PENDING` | `drivers/verify` 재호출 | 409 | 서버 message 표시 | ✓ |

`USER207` 은 **두 경로 모두** 확인했다. 토큰 위조, 그리고 "010-9999-0003 으로 인증하고
010-9999-0005 로 가입" 하는 번호 불일치 — 둘 다 400 `USER207`.
`UserRegisterRequestDto.verificationToken` 주석의 "인증한 번호와 가입 번호가 어긋나면 400 USER207" 이
실측으로 확인됐다.

### FCM 토큰 무효 상황 — 콜 흐름 안전 확인

`PUT /api/v1/drivers/fcm-token` `{"token":"bogus-fcm-token-for-qa"}` → **204** 등록 후 콜 발생:

```
INFO FcmCallNotifier : [콜 알림] 전송완료 partyId=3, 성공=0, 실패=1
GET /dispatch/calls/3/status → {"open":true}
```

**FCM 전송이 실패해도 콜 후보는 정상 등록된다.** `FcmCallNotifier.send()` 가
`ApiFutures.addCallback` 비동기 + 실패는 로그만 찍기 때문. 즉 푸시를 놓쳐도
`GET /calls/{partyId}/status` 폴링 경로가 콜을 살려낸다 — 클라의 폴링 폴백 설계가 유효하다.

> 부수 확인: Firebase 자격증명이 실제로 유효하다(인증 실패가 아니라 토큰 단위 거부를 받았다).
> 따라서 **L2 가 에뮬레이터 FCM 토큰을 `PUT /drivers/fcm-token` 으로 등록하면
> 로컬 서버에서 실제 콜 푸시까지 E2E 검증이 가능하다.**

---

## 4. 백엔드 쪽 발견 (클라이언트 수정 불필요)

### B-1. `Capacity` 문서↔코드 불일치
`OpenPartyRequest` Swagger 는 `정원(생성자 포함) 2~4`, 실제 `Capacity` 는 `MIN=1, MAX=3`.
`capacity:1` 로 방을 만들면 생성 즉시 `isFull()` → `MATCHING` 으로 넘어간다.
(이번 E2E 가 승객 1명으로 끝난 이유이기도 하다.)
→ 기사 앱은 방을 만들지 않으므로 영향 없음. 승객 앱/문서 쪽 확인 필요.

### B-2. `drivers/verify` 재호출 코드 불일치
`@ApiResponses` 는 `409 DRIVER_ALREADY_VERIFIED` 로 문서화했지만 실제는 `DRIVER_NOT_PENDING`.
→ 클라가 이 코드로 분기하지 않고 서버 message 를 그대로 보여주므로 영향 없음.

### B-3. 본인이 만든 방의 콜이 본인에게 간다
기사 계정으로 방을 만들었더니 `GET /dispatch/calls/4/status` → `{"open":true}` 로
**자기 방 콜을 받았고**, 수락 시점에야 `409 SELF_DISPATCH_NOT_ALLOWED` 로 막혔다.
`DispatchService.attempt()` 의 후보 필터에 "파티 구성원 제외" 가 없고,
`acceptCall()` 에서만 검사하기 때문이다.
→ 클라는 B6 경로에서 `taxiDriverId == null` 이라 소유권 주장을 하지 않고
   서버 문구("본인이 참여한 방의 콜은 수락할 수 없습니다.")를 그대로 띄운다. **동작은 안전하다.**
   다만 기사에게 수락 불가능한 콜이 노출되는 낭비가 있다 — 백엔드 후보 필터 개선 권고.

### B-4. `MATCHING` 진입 후 승객이 빠져나올 수 없다
`DELETE /matching/leave/{id}` → 409 `PARTY_NOT_RECRUITING`,
`POST /matching/rooms` → 409 `ALREADY_JOINED_OTHER_PARTY`.
3분 매칭 타임아웃(`MatchingSweeper`)이 돌 때까지 승객이 묶인다. 타임아웃되면 `CANCELED` 로 풀린다.
→ 기사 앱 영향 없음. 반복 E2E 시 **방 하나당 3분 대기**가 생기므로 테스트 설계에 반영 필요
   (이번에도 방 3 때문에 3분을 기다렸다). 회피법: 콜을 reject 하지 말고 수락 후 complete 까지 끝내면
   즉시 `FINISHED` 가 되어 승객이 바로 다음 방을 만들 수 있다.

---

## 5. 클라이언트 수정이 필요한 발견

**없다.** 이번 L3 에서 찾은 클라이언트 결함은 0건이다.

대신 PR #27 이 깔아 둔 전제 3건이 실측으로 **검증 확인**됐다.

1. **`isDriverMissing()` 의 코드 기반 404 판정** — `DRIVER_NOT_REGISTERED` 가 실제로 내려온다.
   코드 없는 404(= 컨트롤러 미등록)와 구분하는 게 맞다. 배포 서버에서 매 실행 로그아웃되던
   문제의 수정 방향이 정확했다.
2. **`claimOwnedTripOn409()` 의 소유권 판정** — `taxiDriverId == /drivers/me.id` 가 성립한다.
3. **15초 위치 하트비트** — 서버 TTL 30초를 실측 재확인. 15초는 적정하다.

추가로 확인된 "건드리면 깨지는" 지점 — 회귀 시 주의:
- `PartySummaryDto` 는 `departureLatitude`, `PartyDetailDto` 는 `departureLat`. **서로 다르다.** 통일하면 깨진다.
- `arrive` 는 `DRIVER_ASSIGNED` 에서만 유효하다. `board` 를 먼저 호출하면 `arrive` 는 영구히 409 다.
  (실측: board 먼저 → 204, 이후 arrive → 409 `NOT_AWAITING_PICKUP`)
  클라는 `notifyPickupArrival` 에서 실패를 삼키므로 안전하지만, **화면 순서는 arrive → board 를 지켜야**
  승객에게 "기사 도착" 푸시가 간다.

---

## 6. 띄운 것과 종료 방법

> **현재 상태: 서버와 Redis 를 켜 둔 채로 넘긴다.**
> L2 가 실기 검증에 쓸 수 있도록 살려 뒀다(§7). 필요 없으면 아래 2줄로 끝낸다.
> ```bash
> kill 9012 && docker stop moyeota-redis
> ```
> 리포트 작성 시점 확인: `GET /health` → 200, `moyeota-redis` Up (healthy).

### 띄운 것

| 대상 | 식별자 | 상태 |
|---|---|---|
| Spring Boot (java) | PID **9012**, 포트 8080 | 내가 띄움 |
| Gradle wrapper (bootRun 런처) | PID **8956** | 내가 띄움 |
| Gradle 데몬 | PID **8970** | 내가 띄움 (BUSY) |
| Redis 컨테이너 | `moyeota-redis`, 포트 6379 | **기존에 멈춰 있던 것을 내가 start** (2026-09-28 생성) |
| Docker Desktop | — | **내가 실행** (원래 데몬 꺼져 있었음) |

### 종료 방법 (순서대로)

```bash
# 1) Spring Boot 정상 종료 — bootRun 태스크가 따라서 끝난다
kill 9012

# 2) 남아 있으면 런처까지
kill 8956 2>/dev/null

# 3) Gradle 데몬 (선택 — 놔둬도 유휴 후 자동 종료)
cd /Users/sungyoon/Desktop/moyeota/backend && ./gradlew --stop

# 4) Redis — 원래 "멈춘 상태" 였으므로 stop 으로 되돌린다.
#    데이터를 지우는 `down -v` / `rm` / `flushall` 은 쓰지 않는다.
docker stop moyeota-redis

# 5) Docker Desktop (선택) — 원래 꺼져 있었다
osascript -e 'quit app "Docker"'
```

**지우지 말 것**
- `docker compose down -v` — `postgres_data` 볼륨(9월부터의 dev 데이터)이 날아간다
- `redis-cli flushall` — `moyeota-redis` 에 이전 세션 `sms:*` 키 11개가 남아 있다
- H2 는 인메모리라 서버 종료와 함께 자동 소멸한다. 이번 E2E 로 만든 계정
  (`drvqa0001`/`psgqa0001`)과 파티 1~5 는 **영속 저장소에 남지 않는다.**

### 재현 자료
- 서버 로그 전문: `/private/tmp/claude-501/.../scratchpad/bootrun.log`
- curl 헬퍼: `/private/tmp/claude-501/.../scratchpad/lib.sh`
  (세션 스크래치패드 — 영구 보관 아님)

---

## 7. L2·L4 에게 넘기는 것

**→ L2 (실기 회귀)**: 로컬 서버로 실기 E2E 가 **가능하다**. 하려면
`NetworkModule.BASE_URL` 을 `http://10.0.2.2:8080/` 로 바꾸고(에뮬레이터→호스트),
`android:usesCleartextTraffic` 또는 network-security-config 로 평문 HTTP 를 허용해야 한다
(현재 BASE_URL 이 https 라 확인 필요). 문자 인증 코드는 `000000` 고정으로 띄워 뒀다.
FCM 토큰을 등록하면 실제 콜 푸시까지 검증된다.

**→ L4 (테스트 보강)**: 실측으로 확정된 에러 코드/상태 전이가 단위 테스트 픽스처의 근거가 된다.
특히 §3 표의 HTTP 상태코드 조합(`USER104`→409, `USER202`→429)과
§2.5 상태 전이 4단계, §2.6 의 `taxiDriverId` 소유권 판정.
