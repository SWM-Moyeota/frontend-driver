# 18 · qa-verifier — FCM 콜 푸시 실수신 E2E 검증 리포트

검증일: 2026-09-04 00:20~00:29 · 에뮬레이터: Pixel_6_QA(emulator-5558, google_apis, 콜드부트, 검증 후 종료, emulator-5554 미접촉)
백엔드: localhost:8080 리더 기동 프로세스 그대로 (재시작/종료 안 함) · FCM 서비스 계정 설정 상태 · APK: `:app:assembleDebug` up-to-date 확인 후 설치 + POST_NOTIFICATIONS 선허용

## 요약

**핵심 3개 전부 통과: 실푸시 수신 O · 알림 탭→D10 진입 O · 콜 리스트 실콜 노출 O. 수락→D11(accept 204)도 통과.**
단, 검증 중 **신규 결함 1건(중대 · 클라 · api-integrator)**: 앱이 영업중 주기 위치 보고(하트비트)를 전혀 하지 않아
서버 Redis 하트비트 TTL 30초가 지나면 기사가 후보 풀에서 사라진다 — 이번 E2E 는 curl 로 goOnline 재호출(하트비트 갱신) 보정 후에야 발송이 성사됐다.
그 외 서버 측 스위퍼 크래시(기지 @Transactional 계열)를 관찰로 기록.

## 단계별 결과

### 1. 빌드·설치·권한 — 통과
`./gradlew :app:assembleDebug` BUILD SUCCESSFUL(up-to-date) → 콜드부트 → 설치 Success → `pm grant … POST_NOTIFICATIONS` 완료.

### 2. 신규 기사 가입 E2E (앱 UI) — 통과
D01 → 기사 회원가입 → 스텝1 `01066661234`(exists:false) → 스텝2 kimfcm/`qadriver2`/`Qadriver2!x` → 스텝3 sonata/4/38da2222/QA333444 → 「가입 완료」 → D05b(승인 완료) → 「영업 시작하기」 → D07 영업중(하단탭·콜 대기 토글 on). 크래시 없음.
okhttp 실측: register 201 → login 200 → drivers 200 → verify 204 → **online 204** (`POST /api/v1/dispatch/online` body `{"latitude":37.498,"longitude":127.0276}` — 리포트 16 결함 1의 토큰 기반 경로 이행 확인).

### 3. FCM 토큰 등록 — 통과
가입 직후 자동 `PUT /api/v1/drivers/fcm-token` → **204** (okhttp 실측, 17 산출물 §2 flush 경로).
서버 로그: `2026-09-04T00:22:44 DriverApplicationService : FCM 토큰 등록 driverId=2`

### 4. 콜 푸시 트리거 (curl) — 통과 (보정 1회, 아래 결함 1 참조)
- 승객 `qapax2` register 201 → login 200 → `POST /api/v1/matching/rooms` 200 (party 2, 강남역→판교역, capacity 1, radius 300/300)
- **party 2 는 발송 실패**: 파티 오픈 시점 즉시 dispatch 가 `신규 후보 없음 partyId=2, 기존 후보=0명` — 기사 goOnline(00:22:58) 후 44초 경과로 **하트비트 TTL(30초) 만료** 때문. 스위퍼 재시도도 서버 크래시(관찰 1)로 불발 → 3분 후 타임아웃 해산.
- **보정 재시도**: curl 로 qadriver2 `POST /dispatch/online` 204(하트비트 갱신) 직후 qapax2 로 party 3 생성(00:27:24) →
  `DispatchService : 기사 호출 partyId=3, radius=1000m, 신규=1명` →
  **`FcmCallNotifier : [콜 알림] 전송완료 partyId=3, 성공=1, 실패=0`** ("기사가 없음" 경고 없음 — 토큰 등록 실증)

### 5. 실푸시 수신 — 통과
발송 즉시(≤3초) 수신. `dumpsys notification --noredact`:
- pkg=com.moyeota.driver, channel=`moyeota_driver_default`(importance 4), contentIntent=MainActivity PendingIntent
- bigText **"출발 강남역 → 판교역 · 예상 25,720원"** — 상세 조회 성공 케이스(예상 수익 포함, 17 산출물 §1) 확인
증적: `_workspace/qa/fcm_notification.png` (알림 셰이드: "모여타 기사 · 새 콜 도착")

### 6. 알림 탭 → D10 진입 — 통과
알림 탭 → **D10 콜 상세 직행** (initialCallPartyId 경로). 실파티 데이터 렌더: 단독 콜 배지, 강남역(픽업)→판교역(하차), **수락까지 13초 카운트다운**, 픽업 0.0km·약 5분, 예상 수익 25,720원(미터기 22,720 + 호출료 3,000 — 서버 estimateFare 22720 과 정합). 하단탭 미노출(공통 규칙 준수).
증적: `_workspace/qa/fcm_call_detail.png`

### 7. 콜 리스트(D09) 실콜 — 통과
뒤로 → 콜 탭 → **리스트 최상단에 실콜** "강남역 → 판교역 · 단독 · 픽업 0.0km · 방금 전 · 예상 25,720원" + 더미 3건이 뒤에 병합(17 산출물 §3 순서 규칙 준수).
증적: `_workspace/qa/fcm_call_list.png`

### 8. 수락 플로우 — 통과
최상단 카드 탭 → D10 재진입(카운트다운 리셋 14초) → 「수락하기」 → `POST /api/v1/dispatch/calls/3/accept` **204** → **D11 배차 확정** 진입("배차가 확정됐어요", 강남역 픽업 카드, 탑승 승객 1명, 「픽업 이동 시작」 CTA). board 이후는 기지 서버 버그(RideService @Transactional)로 미진행(지시 사항).
서버 로그: `콜 수락 partyId=3, driverId=2, 콜 알림 취소된 사람 = 0명`
증적: `_workspace/qa/fcm_accept_d11.png`

### 9. CALL_CLOSED — 미검증
후보가 qadriver2 1명뿐이라 수락 시 CALL_CLOSED 대상 0명(서버 로그 "취소된 사람 = 0명") — 구조상 확인 불가, 미검증 표기.

## 결함

### 결함 1 (중대 · 클라 · api-integrator) — 영업중 주기 위치 보고(하트비트) 미구현으로 30초 후 콜 후보에서 소실

- 생산자(서버): `../backend/dispatch/infrastructure/DriverLocationRedis.java` — `HEARTBEAT_TTL = 30초`. `findNearby` 가 `isAlive`(하트비트 키 존재)로 필터 → goOnline/report 후 30초 지나면 위치가 있어도 후보 제외.
- 소비자(클라): `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchApi.kt:28-29` 에 `POST api/v1/dispatch/location`(`reportLocation`) 이 **선언만 있고 전 코드베이스에 호출부가 없음** (grep 실측 — 유일 매치가 선언부).
- 실기 영향(실측): 영업 시작 44초 후 열린 party 2 에 `기존 후보=0명` → 푸시 미발송. **영업중 기사가 30초 뒤부터는 어떤 콜도 받지 못한다.**
- 수정 요청: 영업중(online) 동안 주기(TTL 30초의 절반인 15초 권장) `reportLocation` 하트비트 루프 추가, 영업 종료 시 중단. (좌표는 현 고정값 유지라도 하트비트 목적 달성.)
- 재검 조건: 영업 시작 → 60초 이상 대기 → 파티 생성 시 즉시 `기사 호출 … 신규=1명` + FCM 발송.

## 관찰 (서버 측 · 클라 결함 아님 · 리더 전달용)

1. **MatchingSweeper 재시도 dispatch 크래시**: 스윕 attempt 경로에서 `LazyInitializationException: PartyEntity.members … (no session)` → `Unexpected error occurred in scheduled task` (bootrun.log 00:25:10 등 반복). 기지 이슈(RideService @Transactional 누락)와 같은 계열이나 다른 클래스(스위퍼→PartyAccess 경로). 이 때문에 파티 오픈 순간의 즉시 dispatch 1회만 유효하고 반경 확장 재탐색이 동작하지 않음. 타임아웃 해산(giveUp)은 정상 동작(party 2 해산 실측).
2. party 2 는 타임아웃 해산으로 정리됨(00:27:10) — 서버 상태 오염 없음.

## 미검증

- CALL_CLOSED 수신(§9 — 후보 1명 구조 제약), 미로그인 알림 탭 억제, CALL_CLOSED 시 알림 cancel·피드 제거 (17 산출물 §6-3·4 — 발송 주체 시나리오 확보 불가).
- board 이후 운행 플로우 (기지 서버 버그로 지시상 제외).

## 증적 스크린샷 (`_workspace/qa/`)

| 파일 | 내용 |
|---|---|
| `fcm_notification.png` | 알림 셰이드 — "모여타 기사 · 새 콜 도착 · 출발 강남역 → 판교역 · 예상 25,720원" |
| `fcm_call_detail.png` | 알림 탭 → D10 콜 상세 (실데이터 · 13초 카운트다운) |
| `fcm_call_list.png` | D09 최상단 실콜 + 더미 병합 |
| `fcm_accept_d11.png` | 수락 → D11 배차 확정 |

## 생성 테스트 계정 (인메모리 — 서버 재기동 시 소실)

- 기사 **qadriver2** / `Qadriver2!x` · `01066661234` · kimfcm · driverId=2 · sonata/4석/38da2222 · QA333444 · FCM 토큰 등록됨 · party 3 배차 확정 상태
- 승객 **qapax2** / `Qapax2!pass` · 010-8888-1234 · party 3(MATCHED, driverId=2 배정) 소속
- 승객 **qapax3** / `Qapax3!pass` · 010-8888-5678 · 예비용으로 가입만 함(파티 없음)
