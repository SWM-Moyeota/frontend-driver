# 29d. matching(파티) API + 기사 FCM 페이로드 + SSE — 백엔드 변경분 대조

- 분석 범위: `matching/` 패키지 전체 · 서버→기사 FCM 데이터 페이로드 · SSE
- 백엔드 기준: 로컬 소스 HEAD `e729801` (2026-10-13) vs 기사 앱이 맞췄던 `b9a921d` (2026-09-09)
- 실서버 검증: **부분 검증됨** — `https://api.moyeota.p-e.kr` 의 `GET /v3/api-docs` · `GET /api/v1/config` · `OPTIONS` 프로브로 확인.
  QA 기사 계정(`mapqb3481`)은 **로그인 실패**(`401 USER102 아이디나 비밀번호가 다릅니다`)로 토큰 기반 실응답 shape 확인 불가 → 스키마는 배포 서버 OpenAPI + 소스로 확정.
- 코드 수정 없음 (읽기 전용 분석)

---

## 🚨 먼저: 배포 서버는 지금 `taxiEnabled=false` — 기사 API가 **존재하지 않는다**

이 절의 결론이 아래 모든 절의 전제를 바꾸므로 맨 앞에 둔다.

```
GET https://api.moyeota.p-e.kr/api/v1/config  →  200 {"taxiEnabled":false}

OPTIONS /api/v1/matching/rooms/1   → 200   (매칭 API 살아 있음)
OPTIONS /api/v1/drivers/me         → 404
OPTIONS /api/v1/drivers/fcm-token  → 404
OPTIONS /api/v1/dispatch/online    → 404
OPTIONS /api/v1/dispatch/calls/1   → 404
```
배포 서버 OpenAPI 의 전체 경로 36개 중 `/api/v1/drivers/**` · `/api/v1/dispatch/**` 는 **0개**다.
(`Bearer x` 로 찍으면 전부 401 이 나오는데, 이는 `JwtAuthenticationFilter` 가 핸들러 매핑 전에 토큰을 거부하기 때문이라 경로 존재 증거가 못 된다. `OPTIONS` 는 `SecurityConfig` 에서 `permitAll` 이라 필터를 통과해 DispatcherServlet 까지 가므로 404 가 곧 "매핑 없음"이다.)

원인 — `moyeota.taxi.enabled=false` 가 아래 빈들을 전부 끈다 (`@ConditionalOnProperty`):

| 빈 | 조건 | taxiEnabled=false 일 때 |
|---|---|---|
| `driver/DriverController` | `havingValue="true", matchIfMissing=true` | **미등록** → `/drivers/**` 전멸 |
| `dispatch/call/DispatchCallController` | 동일 | **미등록** → 콜 수락/거절/상세 전멸 |
| `dispatch/ride/RideController` | 동일 | **미등록** → arrive/board/complete 전멸 |
| `dispatch/location/DriverLocationController` | 동일 | **미등록** → online/location 전멸 |
| `dispatch/call/DispatchListener` | 동일 | **미등록** → `MatchingStartedEvent` 수신자 없음 |
| `dispatch/call/infrastructure/FcmCallNotifier` | 동일 | **미등록** → CALL_OPENED/CALL_CLOSED 푸시 0건 |
| `dispatch/call/MatchingSweeper` | 동일 | **미등록** → 반경 확대 재호출 없음 |
| `matching/party/completion/DispatchCompletionPolicy` | 동일 | **미등록** |
| `matching/party/completion/ChatOnlyCompletionPolicy` | `havingValue="false"` | **등록** → 정원 차도 `startMatching()` 안 함, `MatchingStartedEvent` 미발행 |
| `matching/party/CompletedPartySweeper` | `havingValue="false"` | **등록** → COMPLETED 30분 후 자동 종료 |

즉 배포 서버에서는 파티가 정원을 채워도 `COMPLETED` 에서 멈추고 `MATCHING` 으로 넘어가지 않으며, **기사에게 콜 푸시가 단 한 건도 나가지 않는다.** 로컬 소스에는 코드가 다 있으므로 이건 "구현 삭제"가 아니라 **배포 프로파일의 피처 플래그**다 → 기사 앱 쪽에서 고칠 수 있는 문제가 아니고, 백엔드 담당자에게 `moyeota.taxi.enabled=true` 로 켜달라고 요청해야 한다.

**기사 앱에 이미 있는 대응**: `GET /api/v1/config` 를 읽는 클라이언트는 없다. `DriverApi.getMe()` 가 404 를 받으면 `restoreSession()` 이 `clearStoredSession()` 으로 "기사 미등록 — 확인된 종료"로 판정해 토큰·활성 파티를 **지운다**(`RemoteDriverRepository.kt:195`). taxiEnabled=false 인 서버에서는 404 가 "기사 미등록"이 아니라 "기능 꺼짐"인데 같은 취급을 받아 **로그인 세션이 매 실행마다 날아간다.** 아래 불일치 목록 M-1 로 올린다.

---

## 1. 현재 백엔드 API 표 (matching 패키지, HEAD `e729801`)

경로는 전부 `@RequestMapping("/api/v1")` 하위. 인증은 `SecurityConfig` 의 `anyRequest().authenticated()` 로 **토큰 필수**(permitAll 은 `/api/v1/auth/**`, `/api/v1/config`, `/ws-chat/**`, `/health`, `/prometheus`, `/info`, swagger 뿐).

> 인증 주체 메모: 기사 토큰 = 유저 토큰이다. `AuthService.authenticate()` 가 access token → `AuthenticatedPrincipal(userId, publicId)` 를 만들고, `@CurrentDriver` 는 그 `userId` 를 `Drivers.findByUserId()` 로 driverId 로 바꿔 넣는다(`CurrentDriverArgumentResolver`). 따라서 **`@CurrentUser` 엔드포인트도 기사 토큰으로 호출된다** — 단 주입되는 값은 그 기사의 userId 다.

| 메서드 | 경로 | 인증 주체 | 요청 | 응답 | 주요 에러코드 |
|---|---|---|---|---|---|
| POST | `/matching/rooms` | `@CurrentUser` | `OpenPartyRequest{creatorId(무시), departureLat, departureLng, destinationLat, destinationLng, departure, destination, capacity, departureRadius, destinationRadius}` | `OpenPartyResponse` (= PartyResult 필드 + ) | 400 SAME_DEPARTURE_DESTINATION/INVALID_CAPACITY/INVALID_RADIUS/OUT_OF_SERVICE_AREA, 409 ALREADY_JOINED_OTHER_PARTY, 502 ROUTE_SEARCH_FAILED |
| POST | `/matching/rooms/{partyId}/join` | `@CurrentUser` | — | `PartyStatusResponse{status, currentMembers, fingerprint}` | 404 PARTY_NOT_FOUND, 409 PARTY_FULL/PARTY_CLOSED/ALREADY_JOINED_PARTY/ALREADY_JOINED_OTHER_PARTY |
| DELETE | `/matching/leave/{partyId}` | `@CurrentUser` | — | 204 | 403 NOT_PARTY_MEMBER, 404 PARTY_NOT_FOUND, 409 PARTY_NOT_RECRUITING |
| **GET** | **`/matching/rooms/{partyId}`** | **인증만 (`@CurrentUser` 파라미터 없음 · 멤버 체크 없음)** | — | **`PartyDetailResult`** ↓ | **404 PARTY_NOT_FOUND** |
| GET | `/matching/rooms/{partyId}/status` | 인증만 | — | `PartyStatusResponse{status, currentMembers, fingerprint}` | 404 PARTY_NOT_FOUND |
| GET | `/matching/rooms` | 인증만 | — | `PartyListResponse{list:[{partyId, departure, destination, currentMembers, capacity, status, departureLat, departureLng}]}` | — |
| GET | `/matching/rooms` | 인증만 | query `swLat,swLng,neLat,neLng` (4개 모두) | 위와 동일 | 400 INVALID_MAP_BOUNDS |
| GET | `/matching/rooms/{partyId}/driver` | 인증만 | — | `DriverSummary` (좌석·번호판·차종) | 404 PARTY_NOT_FOUND/ASSIGNED_DRIVER_NOT_FOUND, 409 DRIVER_NOT_ASSIGNED |
| POST | `/matching/rooms/{partyId}/finish` | `@CurrentUser` | — | 204 | 403 NOT_PARTY_MEMBER, 404 PARTY_NOT_FOUND, 409 PARTY_NOT_COMPLETED/DRIVER_ALREADY_ASSIGNED |
| GET | `/matching/rooms/{partyId}/events` | **`@CurrentUser` + `party.hasMember(memberId)`** | — | `text/event-stream` | **403 NOT_PARTY_MEMBER**, 404 PARTY_NOT_FOUND |
| POST | `/matching/routes` | 인증만 | `RouteRequest` | `RoutePreviewResponse` | — |

### `GET /matching/rooms/{partyId}` 응답 — `PartyDetailResult` (기사 앱 운행 복구의 원천)

```
{ id, departureLat, departureLng, destinationLat, destinationLng,
  departure, destination, capacity, currentMembers,
  departureRadius, destinationRadius, status, createdAt,
  members: [ { publicId, nickname, imageUrl, badgeId, rideCount } ],
  estimateFare, estimateTime, route, taxiDriverId, fingerprint }
```
- `status` ∈ `ACTIVE | COMPLETED | MATCHING | DRIVER_ASSIGNED | IN_RIDE | FINISHED | CANCELED` — **`b9a921d` 와 완전히 동일** (enum 무변경)
- `taxiDriverId`: 배정된 **driver 엔티티 id** (= `GET /drivers/me` 의 `id`). 배정 전 `null`
- 좌표 키: **`departureLat`/`departureLng`/`destinationLat`/`destinationLng`** — 변경 없음 (dispatch 콜 상세의 `departureLatitude`/`...Longitude` 와 여전히 다름)
- 요금/시간 키: **`estimateFare`/`estimateTime`** (dispatch 쪽은 `estimatedFare`/`estimatedTime`) — 변경 없음
- `members[].nickname`: 탈퇴 등으로 `MemberSummary` 가 없으면 레코드 전체가 null 필드로 채워진다(`MemberInfo.of` 가 null-guard) — 변경 없음
- **`members` 에 기사는 들어가지 않는다** — 멤버는 party_member 의 userId 뿐이고, `DispatchService.acceptCall` 이 "기사가 자기 매칭방 멤버인 경우"를 `SELF_DISPATCH_NOT_ALLOWED` 로 막는다. 즉 `members` = 승객 전원.

### `b9a921d` → `e729801` 응답 필드 차분

| 필드 | b9a921d | e729801 | 영향 |
|---|---|---|---|
| `fingerprint` (루트) | 없음 | **추가** (`8a01214`) | 무해 — 상태·멤버 구성 지문. 클라이언트가 안 읽어도 됨 |
| `members[].joinedAt` | 있음 (`Instant`) | **삭제** (`e729801`, 오늘) | 무해 — 기사 앱이 파싱하지 않음 |
| 그 외 전부 | — | 무변경 | — |

패키지 경로만 이동: `matching/application/dto/PartyDetailResult` → `matching/party/dto/PartyDetailResult`, `matching/domain/enums/PartyStatus` → `matching/party/domain/PartyStatus`, `matching/api/PartySummary` → `matching/api/dto/PartySummary`. **HTTP 표면 영향 0.**

### 인증 요구 변화 — 없음
`b9a921d` 의 `SecurityConfig` 도 `anyRequest().authenticated()` 였다. 열린 경로만 늘었다(`/api/v1/config`, `/prometheus`, `/info`, swagger, `DispatcherType.ASYNC`). `GET /matching/rooms/{partyId}` 는 **전과 동일하게 "토큰만 있으면 되고 파티 멤버일 필요는 없다"** → 기사 토큰으로 조회 가능. 기사 앱의 운행 복구 전제가 그대로 유효하다.

---

## 2. SSE — **승객 전용. 기사는 구독 대상이 아니다.** (근거 명시)

### 근거 4개
1. **엔드포인트가 멤버십을 강제한다.** `PartySseService.subscribe()` 는 `if(!party.hasMember(memberId)) throw NOT_PARTY_MEMBER` 다. `memberId` 는 `@CurrentUser` → `principal.userId()`. 기사의 userId 는 그 파티의 party_member 에 없다(위 1절 참고 — 있으면 애초에 콜 수락이 `SELF_DISPATCH_NOT_ALLOWED` 로 막힌다). 따라서 **기사 토큰으로 구독하면 반드시 403.**
2. **백엔드 전체에 SSE 엔드포인트가 이 하나뿐이다.** `grep SseEmitter|TEXT_EVENT_STREAM` → `matching/sse/PartySseController` 만. 기사용 SSE는 존재하지 않는다.
3. **발화 지점이 승객 행동뿐이다.** `PartyService` 에서 `partyChangeNotifier` 를 호출하는 곳은 join(106~110) · leave(129~137) · finish(198) · expire(211) 뿐. **기사 상태 전이(`assignDriver`/`startRide`/`completeRide`, 전부 `PartyAccessService`)는 SSE 를 쏘지 않는다** — 기사가 알아야 할 내용이 애초에 SSE 로 흐르지 않는다.
4. **기사 앱에 SSE 클라이언트가 없다.** `data/`·`app/`·`presentation/` 전체에 event-stream/EventSource 사용 0건. 기사 앱은 FCM 푸시 + `CallAlertBus` 로 산다.

→ **결론: `e729801` 은 기사 앱에 아무 작업도 요구하지 않는다.** 승객 앱(`../frontend`) 전용 변경분이다.

### 참고로 정리한 실제 SSE 스펙 (승객 앱 담당자에게 넘길 용도)
엔드포인트 `GET /api/v1/matching/rooms/{partyId}/events` (`Accept: text/event-stream`), 전파는 Redis pub/sub 채널 `party:events` 경유.

| 이벤트명 | 발화 | `data` 페이로드 | 비고 |
|---|---|---|---|
| `connected` | 구독 즉시 1회 | **`PartyDetailResult` 전체 JSON** | `PartySseRegistry.subscribe()` 가 `partyService.getPartyDetail(partyId)` 스냅샷을 그대로 보낸다 |
| `member-joined` | `PartyService.join` 커밋 후 | `MemberJoined{ member:{publicId,nickname,imageUrl,badgeId,rideCount}, status }` | **변경분 전달** — 이게 `e729801` 의 핵심 |
| `member-left` | `PartyService.leave` 커밋 후 | `MemberLeft{ publicId, status }` | 나간 본인의 연결은 `closeMember` 로 끊긴다(이벤트 없이 스트림 종료) |
| `closed` | leave 로 마지막 멤버 퇴장 · `finish` · `expire` | `partyId` **문자열 하나** (JSON 아님) | 수신 후 `closeAll` 로 전원 연결 종료 |
| (주석) | 30초 주기 | `: ping` | `PartySseRegistry.HEARTBEAT_MS = 30_000` |

**백엔드 문서 ↔ 코드 불일치 2건** (실제 응답 우선, 소스 기준으로 기록):
- Swagger(`PartySseController` `@Operation`)는 "멤버 변화 시 **`changed`**" 라고 쓰여 있는데, 구현은 `member-joined`/`member-left` 다. `changed` 라는 이벤트명은 코드에 없다 — `e729801` 에서 설명을 갱신하지 않았다. 배포 서버 OpenAPI 에도 옛 문구가 그대로 실려 있다.
- Swagger 와 커밋 `3670213` 는 "heartbeat 5초"라고 하지만 `PartySseRegistry` 의 `HEARTBEAT_MS` 는 **30_000(30초)** 이다.

### `PartyClosedEvent` / `PartyMemberJoinedEvent` / `PartyMemberLeftEvent` — SSE와 별개의 **인프로세스 Spring 이벤트**
`matching/api/dto/` 에 있고 HTTP 로 안 나간다. 수신자:

| 이벤트 | 발행 | 수신자 | 기사 앱 영향 |
|---|---|---|---|
| `PartyMemberJoinedEvent{partyId, memberId}` | `PartyService:80, :106` | `chat/room/.../ChatRoomMatchingListener.on()` → 채팅방 참여 | 없음 (기사는 채팅방 멤버가 아니다) |
| `PartyMemberLeftEvent{partyId, memberId}` | `PartyService:129` | 같은 리스너 → 채팅방 퇴장 | 없음 |
| `PartyClosedEvent{partyId, status}` | `PartyService:132, :198, :211` | **수신자 없음** (백엔드 전체 grep 결과 발행만 존재) | 없음 |
| `MatchingStartedEvent{partyId}` | `DispatchCompletionPolicy:24` | `dispatch/call/DispatchListener.on()` → `DispatchService.dispatch()` → **CALL_OPENED 푸시** | **콜 발생의 유일한 트리거.** taxiEnabled=false 면 발행자·수신자 모두 사라진다 |

---

## 3. 서버 → 기사 FCM 데이터 페이로드 (현재 코드 전수)

백엔드 전체에서 `putData("type", ...)` 는 4곳. 수신 대상 토큰 저장소로 승객용/기사용이 갈린다.

| notifier | 토큰 출처 | 대상 | `type` | 동반 키 | Android 설정 |
|---|---|---|---|---|---|
| **`dispatch/call/infrastructure/FcmCallNotifier.notifyCall`** | `driverAccess.findFcmTokens(driverIds)` (**driver 테이블**) | **기사** | `CALL_OPENED` | `partyId`, `departure`, `destination` | `Priority.HIGH`, **TTL 미설정**, `notification` 블록 없음(data-only), multicast |
| **`FcmCallNotifier.notifyCallClosed`** | 동일 (**driver 테이블**) | **기사** | `CALL_CLOSED` | `partyId` | 동일 |
| `dispatch/ride/infrastructure/FcmPassengerNotifier.notifyDriverArrived` | `userAccess.findFcmTokens(memberIds)` (user 테이블) | 승객 | `DRIVER_ARRIVED` | `partyId` | `Priority.HIGH`, TTL 미설정 |
| `chat/notification/infrastructure/FcmChatNotifier.notifyNewMessage` | `userAccess.findFcmTokens(receiverIds)` (user 테이블) | 승객 | `CHAT_MESSAGE` | `chatRoomId`, `messageId`, `senderPublicId`, `senderNickname`, `preview` | `Priority.HIGH`, TTL 미설정 |

**기사에게 가는 type 은 `CALL_OPENED` · `CALL_CLOSED` 단 2개다.**

- `memberCount` / `estimatedFare` 는 **CALL_OPENED 에 없다.** `PartySummary` 레코드에는 두 값이 다 있는데(`memberCount`, `estimatedFare`, `estimatedTime`) `FcmCallNotifier` 가 `departure`/`destination` 만 `putData` 한다. `b9a921d` 시점의 `FcmCallNotifier` 와 **키 구성이 완전히 동일** — 이번 273 커밋 동안 콜 푸시 페이로드는 한 글자도 안 바뀌었다(패키지만 `dispatch/infrastructure/` → `dispatch/call/infrastructure/` 로 이동).
- **TTL(`setTtl`)이 어느 notifier에도 없다** → FCM 기본 TTL = 4주. 콜은 `DispatchService.MATCHING_TIMEOUT = 3분` 인데, 단말이 오프라인이었다가 수 시간 뒤 접속하면 **이미 마감된 CALL_OPENED 가 배달될 수 있다.** `CALL_CLOSED` 도 같은 큐에 쌓여 순서가 뒤바뀔 수 있다.
- 기사 토큰 등록 경로: `PUT /api/v1/drivers/fcm-token` · 삭제 `DELETE /api/v1/drivers/fcm-token` (기기 1대 정책, 새 토큰이 이전 토큰 덮어씀). 기사 앱 `DriverApi.kt:53,56` 과 **경로·메서드 일치**. 단 배포 서버에서는 404(taxiEnabled=false) → 토큰이 아예 등록되지 않는다.
- 기사 앱 패키지의 FCM 등록 토큰은 승객 앱과 별개이고, 기사 앱 토큰은 driver 테이블에만 저장되므로 `DRIVER_ARRIVED`·`CHAT_MESSAGE` 가 기사 앱으로 흘러올 경로는 없다.

---

## 4. 기사 앱 클라이언트 현재 상태 (파일:라인)

| 관심사 | 파일:라인 | 상태 |
|---|---|---|
| 파티 상세 API | `data/.../remote/PartyMembersApi.kt:21-22` — `@GET("api/v1/matching/rooms/{partyId}")` | ✅ 경로 일치 |
| 파티 상세 DTO | `data/.../remote/dto/PartyMemberDtos.kt:23-40` — `id, departureLat/Lng, destinationLat/Lng, departure, destination, currentMembers, status, members, estimateFare, estimateTime, taxiDriverId` | ✅ 12개 필드 전부 서버에 존재 |
| 멤버 DTO | `PartyMemberDtos.kt:44-46` — `PartyMemberDto(nickname)` | ✅ |
| JSON 관용성 | `data/.../remote/NetworkModule.kt:30-33` — `ignoreUnknownKeys = true`, `coerceInputValues = true` | ✅ 신규 `fingerprint` 를 조용히 무시 |
| 상태 → 단계 매핑 | `data/.../remote/PartyRecoveryMappers.kt:20-38` — `IN_RIDE→IN_TRIP`, `DRIVER_ASSIGNED→ASSIGNED`, 나머지 null | ✅ enum 무변경 |
| 복구 변환 | `PartyRecoveryMappers.kt:49-91` — `toRestoredTrip()`; 좌표·`currentMembers`·`members[].nickname` 사용 | ✅ |
| 세션/운행 복구 | `data/.../repository/RemoteDriverRepository.kt:182-215`(`restoreSession`), `225-266`(`restoreOngoingTrip`) | ⚠️ `getMe()` 404 처리가 taxiEnabled=false 와 충돌 (M-1) |
| 닉네임 주입 | `RemoteDriverRepository.kt:775-782`(`fetchPassengerNicknamesQuietly`), `:744-773`(`acceptCall`) | ✅ 베스트에포트 |
| CALL_OPENED 처리 | `app/.../DriverFirebaseMessagingService.kt:48-53, 63-99` | ✅ `memberCount`/`estimatedFare` 를 nullable 로 받고 없으면 그대로 진행 |
| CALL_CLOSED 처리 | `DriverFirebaseMessagingService.kt:102-107` | ✅ |
| 그 외 type | `DriverFirebaseMessagingService.kt:52, 164-192`(`showFallbackNotification`) | ✅ title/body 없으면 조용히 무시 |
| 콜 미리보기 | `RemoteDriverRepository.kt:646-657`(`seedCallPreview`) | ✅ |
| 콜 확정 조회 | `RemoteDriverRepository.kt:667-684`(`handleCallOpened`) → `GET /dispatch/calls/{partyId}` | ⚠️ 배포 서버 404 (M-1 파생) |
| SSE | 없음 | ✅ 필요 없음 (2절) |

---

## 5. 불일치 목록

| # | 심각도 | 증상 | 원인 | 고칠 지점 |
|---|---|---|---|---|
| **M-1** | **깨짐 (환경)** | 배포 서버에 기사 API 전체가 없다. 로그인 후 `getMe()` 404 → `clearStoredSession()` 이 토큰+활성 partyId 를 지워 **매 실행마다 로그아웃**. 콜 푸시 0건, 운행 복구 불가 | 배포 프로파일 `moyeota.taxi.enabled=false` → `DriverController`/`Dispatch*Controller`/`FcmCallNotifier` 미등록 | **1차는 백엔드**(플래그 ON 요청). 기사 앱 쪽 방어: `RemoteDriverRepository.kt:195` 의 404 분기를 "세션 보존"으로 완화 + `GET /api/v1/config` 의 `taxiEnabled` 선조회 |
| **M-2** | 무해 | `PartyDetailResult.fingerprint` 신규 필드 | `8a01214` | 없음. `ignoreUnknownKeys` 로 흘려보낸다. (기사 앱은 폴링을 안 하므로 `/status` + fingerprint 최적화를 쓸 이유도 없다) |
| **M-3** | 무해 | `members[].joinedAt` 삭제 | `e729801` | 없음. 기사 앱이 파싱하지 않는다 |
| **M-4** | 무해 | matching 패키지 전면 재배치 (`application/dto`→`party/dto` 등) | `9c66992` 외 | HTTP 표면 무영향. `PartyMembersApi.kt:8` · `PartyMemberDtos.kt:7` · `PartyRecoveryMappers.kt:19` 주석의 옛 소스 경로만 낡았다 |
| **M-5** | 동작변경(승객 전용) | SSE 가 `changed`(재조회 유발) → `member-joined`/`member-left`(변경분 동반) 로 전환 | `e729801` | **기사 앱 작업 없음.** 승객 앱 담당에게 2절 표 전달 |
| **M-6** | 무해(잠재) | `CALL_OPENED` 에 `memberCount`·`estimatedFare` 가 없어 백그라운드 알림 본문이 "출발 A → B" 로만 뜬다. 보강용 `GET /dispatch/calls/{partyId}` 가 실패하면(409/타임아웃/404) 끝까지 안 채워짐 | 서버 `FcmCallNotifier` 가 두 키를 안 싣는다 (b9a921d 때도 동일) | 클라이언트 변경 불필요(이미 방어됨). 개선하려면 **백엔드에 `putData("memberCount"/"estimatedFare")` 추가 요청** — `PartySummary` 에 값이 이미 있어 1줄씩이면 끝난다 |
| **M-7** | 동작변경(잠재 오동작) | FCM TTL 미설정(기본 4주) + 콜 유효시간 3분. 장시간 오프라인 뒤 복귀하면 **죽은 CALL_OPENED** 로 D10 이 자동 전환되고 `GET /dispatch/calls/{partyId}` 가 409 를 돌려줘 빈 콜 화면이 뜬다 | `FcmCallNotifier` 에 `setTtl` 없음 | 백엔드: `AndroidConfig.setTtl(Duration.ofMinutes(3))`. 클라이언트 보완: `onCallOpened` 에서 `RemoteMessage.sentTime` 과 현재 시각 차가 3분 초과면 `CallAlertBus.open` 을 생략 |
| **M-8** | 무해 (문서) | 백엔드 Swagger 가 SSE 이벤트명을 `changed`, heartbeat 를 5초로 적어 두었으나 실제는 `member-joined`/`member-left`, 30초 | `e729801` 이 `@Operation` 미갱신 | 기사 앱 무영향. 백엔드 문서 수정 대상 |
| **M-9** | 무해 (문서) | `backend/http/matching.http` 가 `/api/matching/rooms`(`/v1` 없음) + `hostId` 로 낡았다 | 장기 미갱신 | 스펙 원천으로 쓰지 말 것. 컨트롤러 소스/배포 OpenAPI 를 쓴다 |

### 서버가 보내는데 기사 앱이 처리하지 않는 type
**없음.** 서버가 driver 토큰으로 보내는 type 은 `CALL_OPENED`·`CALL_CLOSED` 뿐이고 둘 다 `DriverFirebaseMessagingService.onMessageReceived` 의 `when` 에 있다. (`DRIVER_ARRIVED`·`CHAT_MESSAGE` 는 user 토큰 대상이라 기사 앱에 도달하지 않으며, 설령 오더라도 `showFallbackNotification` 이 body 없음으로 조용히 무시한다.)

### 기사 앱이 기대하는데 서버가 더 이상 안 보내는 type
**없음.** 기사 앱이 분기하는 type 은 `CALL_OPENED`·`CALL_CLOSED` 둘뿐이고 둘 다 서버에 살아 있다(단 taxiEnabled=true 일 때만 — M-1).

### 키 단위 차이 (type 은 맞고 동반 키만 다른 경우)
| type | 서버가 보내는 키 | 기사 앱이 읽는 키 | 판정 |
|---|---|---|---|
| `CALL_OPENED` | `type, partyId, departure, destination` | `type, partyId, departure, destination, memberCount, estimatedFare` | `memberCount`·`estimatedFare` 는 **서버에 없음** → 기대만 있고 안 옴 (M-6, 방어됨) |
| `CALL_CLOSED` | `type, partyId` | `type, partyId` | 완전 일치 |

---

## 6. 권장 수정안 (클라이언트 쪽, 코드 스케치)

### 우선순위 1 — M-1 방어: `taxiEnabled=false` 서버에서 세션이 날아가지 않게
배포 서버가 `taxiEnabled=false` 인 동안 `GET /drivers/me` 는 404 다. 지금은 이를 "기사 미등록(확인된 종료)"으로 보고 토큰까지 지운다. 404 를 두 가지로 가를 수 없으므로 **`/api/v1/config` 를 먼저 읽어 구분**한다.

```kotlin
// data/.../remote/AuthApi.kt (또는 신규 ConfigApi) — permitAll 이라 토큰 불필요
@GET("api/v1/config")
suspend fun getConfig(): AppConfigDto     // { taxiEnabled: Boolean }

// data/.../remote/dto/AuthDtos.kt
@Serializable data class AppConfigDto(val taxiEnabled: Boolean = true)
```
```kotlin
// RemoteDriverRepository.restoreSession() 의 404 분기
404 -> if (isTaxiDisabledQuietly()) {
    // 기능 꺼진 서버 — 세션은 보존하고 "기사 기능 미활성" 으로만 알린다
    return RestoredSession(loggedIn = true, ongoingTrip = null)
} else {
    clearStoredSession()   // 진짜 기사 미등록
}
```
`isTaxiDisabledQuietly()` 는 실패 시 `false`(= 기존 동작 유지)로 떨어뜨린다. compose-builder 와 협의 필요: `taxiEnabled=false` 를 홈(D05)에서 배너로 알릴지 여부 → **Repository 시그니처 변경 없음**(`restoreSession(): RestoredSession` 그대로)이라 화면 쪽 필수 작업은 없다.

### 우선순위 2 — M-7 방어: 만료된 콜 푸시로 D10 자동 전환 막기
```kotlin
// app/.../DriverFirebaseMessagingService.onMessageReceived
override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    when (message.data["type"]) {
        // 서버 콜 유효시간 3분(DispatchService.MATCHING_TIMEOUT). FCM 기본 TTL 은 4주라
        // 오프라인 뒤 복귀하면 이미 마감된 콜이 배달된다 — 자동 전환 전에 걸러낸다.
        "CALL_OPENED" -> if (message.isFresh(CALL_TTL_MS)) onCallOpened(message.data)
        "CALL_CLOSED" -> onCallClosed(message.data)   // 마감은 늦게 와도 처리해야 한다
        else -> showFallbackNotification(message)
    }
}

private fun RemoteMessage.isFresh(ttlMs: Long) =
    sentTime <= 0L || System.currentTimeMillis() - sentTime <= ttlMs   // sentTime 0 = 알 수 없음 → 통과

private const val CALL_TTL_MS = 3 * 60 * 1000L
```
`data/`·`domain/` 무변경, `app/` 모듈 한 파일. DTO·매퍼·Repository 시그니처 영향 없음 → compose-builder 통지 불필요.

### 우선순위 3 — 주석의 낡은 소스 경로 갱신 (M-4)
동작 영향 0. 다음에 해당 파일을 손댈 때 함께 고친다.
- `PartyMembersApi.kt:8` `matching/presentation/PartyController` → `matching/party/PartyController`
- `PartyMemberDtos.kt:7` `matching/application/dto/PartyDetailResult.java` → `matching/party/dto/PartyDetailResult.java`
- `PartyRecoveryMappers.kt:19` `matching/domain/enums/PartyStatus.java` → `matching/party/domain/PartyStatus.java`

### 백엔드에 요청할 것 (기사 앱이 고칠 수 없는 것)
1. **`moyeota.taxi.enabled=true`** — 이것 없이는 기사 앱 통합 검증이 불가능하다 (최우선).
2. `FcmCallNotifier.notifyCall` 에 `.putData("memberCount", String.valueOf(party.memberCount()))` · `.putData("estimatedFare", String.valueOf(party.estimatedFare()))` 추가 — `PartySummary` 에 값이 이미 있다. 기사 앱은 **이미 두 키를 읽을 준비가 되어 있다**(`DriverFirebaseMessagingService.kt:70-71`).
3. `FcmCallNotifier` 양쪽에 `AndroidConfig.setTtl(Duration.ofMinutes(3))`.
4. `PartySseController` `@Operation` 의 `changed`/5초 문구를 `member-joined`·`member-left`/30초로 정정.
5. QA 기사 계정(`mapqb3481`) 비밀번호 재확인 — 배포 서버에서 `401 USER102`.

### 테스트 (실제 수정 단계에서 추가할 것)
- `data/src/test/.../PartyRecoveryMappersTest.kt`: `fingerprint` 포함 + `joinedAt` 없는 JSON 을 `PartyDetailDto` 로 디코딩해도 `toRestoredTrip` 결과가 동일함을 고정 (M-2/M-3 회귀 방지)
- `app` 모듈 또는 순수 함수로 분리한 `isFresh()` 단위 테스트 (M-7)
- `RemoteDriverRepositorySessionTest`: `getMe()` 404 + `taxiEnabled=false` 조합에서 `loggedIn=true` 유지, 활성 partyId 보존 (M-1)
- 실행: `./gradlew :data:testDebugUnitTest`

---

## 7. 결론 요약

1. **운행 복구는 서버 스펙 측면에서 여전히 동작한다.** `GET /api/v1/matching/rooms/{partyId}` 는 경로·인증 요구(토큰만, 멤버십 불필요)·`status`·`taxiDriverId`·`departureLat/Lng`·`destinationLat/Lng`·`currentMembers`·`estimateFare`·`estimateTime`·`members[].nickname` 이 `b9a921d` 와 **전부 동일**하고, `PartyStatus` enum 도 무변경이다. 추가된 `fingerprint` 는 `ignoreUnknownKeys` 가, 삭제된 `members[].joinedAt` 은 애초에 미파싱이라 영향이 없다. **단 배포 서버에서는 `GET /drivers/me` 가 404 라 복구 경로의 1단계에서 멈추고, 오히려 세션이 지워진다(M-1)** — 코드 문제가 아니라 피처 플래그 문제.
2. **콜 푸시 타입 불일치 — type 레벨 불일치 0건.** 서버→기사 type 은 `CALL_OPENED`·`CALL_CLOSED` 두 개뿐이고 기사 앱이 둘 다 처리한다. 서버가 보내는데 앱이 모르는 type 없음, 앱이 기대하는데 서버가 안 보내는 type 없음. **키 레벨로만 2건**: `CALL_OPENED` 의 `memberCount`·`estimatedFare` 를 앱이 읽으려 하지만 서버가 싣지 않는다(nullable 로 이미 방어 — 알림 본문만 축약됨). 그 밖에 TTL 미설정으로 만료 콜이 뒤늦게 배달될 수 있다(M-7).
3. **SSE 는 승객 전용 — 기사 앱 작업 없음.** 멤버십 강제(403 NOT_PARTY_MEMBER) · 유일 엔드포인트 · 발화 지점이 승객 행동뿐 · 기사 앱에 클라이언트 없음, 4중 근거.
