# 16 · qa-verifier — 신스펙(토큰 기반 + phone/check) 실서버 E2E 검증 리포트

검증일: 2026-09-03 · 에뮬레이터: Pixel_6_QA(emulator-5556, 콜드부트, 검증 후 종료, emulator-5554 미접촉)
백엔드: localhost:8080 신코드 (기동 상태 그대로 — 종료/재기동 안 함, 상태 변경은 테스트 계정 가입만)
APK: `./gradlew :app:assembleDebug` 재실행 → up-to-date 확인 후 설치 + `pm clear`

## 요약

**핵심 4개 항목 중 3개 통과 + 결함 1건(중대 · 경계면 · api-integrator 후속 필요).**
가입 E2E는 이번에 **순정 2xx(201→200→200→204, 409 폴백 없음)로 완주** — 지난 리포트(13)의 갭 A·관찰 3(userId=1L 수렴)이 신스펙 전환으로 전부 해소됐다.
남은 결함은 **DispatchApi 전 경로가 구(path driverId) 방식**이라는 것 — 백엔드 dispatch 도 토큰 기반(@CurrentDriver)으로 이행 완료되어 `POST /dispatch/online/{id}` 가 **404** 난다(실측 2회).

## 항목별 결과

### 1. 가입 E2E (신규 번호 01055551234) — **통과**

D01 → 「기사 회원가입」 → 스텝1 휴대폰(1/3, CTA 비활성→입력 후 활성) → 「다음」 → 스텝2 프로필(2/3) →
「다음」 → 스텝3 택시(3/3, 전 필드 유효 시 CTA 활성) → 「가입 완료」 → **D05b(승인 완료 배지)** →
「영업 시작하기」 → **D07 홈(영업중 헤더, 콜 대기 토글 on, 하단탭 노출)** 완주. 크래시·화면 깨짐 없음.

okhttp 실측 (가입 제출 — **전부 순정 2xx, 409 폴백 미발생**):

| 순서 | 요청 | 응답 |
|---|---|---|
| 1 | `POST /api/v1/auth/phone/check` `{"phoneNumber":"010-5555-1234"}` | **200** `{exists:false}` → 스텝2 진행 |
| 2 | `POST /api/v1/auth/register` | **201** |
| 3 | `POST /api/v1/auth/login` | **200** |
| 4 | `POST /api/v1/drivers` | **200** `{"id":1,"userId":1,"status":"PENDING","callEnabled":true}` |
| 5 | `POST /api/v1/drivers/verify` | **204** |
| 6 | `POST /api/v1/dispatch/online/1` (영업 시작하기) | **404** — 결함 1 |

`POST /drivers` body 실측 — **userId 없음 + vehicle 중첩 + 폼 실값** (신스펙 부합):
```json
{"qualificationNumber":"QA111222","bankName":"국민","accountNumber":"000000000000",
 "vehicle":{"seats":4,"plateNumber":"37na1111","type":"sonata"}}
```

### 2. 기가입 번호 분기 (실서버 exists:true) — **통과**

앱 재시작(force-stop) → 가입 스텝1에 방금 가입한 `01055551234` → 「다음」 →
`phone/check` **200 `{exists:true}`** (실측) → INFO 배너 「이미 가입된 번호예요. 기존 계정으로 로그인 후 기사 정보를 등록해 주세요」
+ CTA 「로그인하러 가기」로 교체 → 탭 시 D01 복귀 확인. 증적 `_workspace/qa/v2_phone_exists.png`

### 3. 로그인 E2E — **통과**

| 확인 포인트 | 결과 | 실측 |
|---|---|---|
| 잘못된 비밀번호 1회 | **통과** | login **401** → 「아이디 또는 비밀번호가 맞지 않아요」 danger 문구 + 비밀번호 필드 적색 테두리 |
| 정상 로그인 `qadriver1`/`Qadriver1!` | **통과** | login **200** → `GET /local/users/info` **200** `{name:"kimqa"}` (서버가 폼의 실제 이름 저장 확인) → **`GET /drivers/me` 200** `{id:1,userId:1,VERIFIED,callEnabled:true}` → D06 홈 진입(하단탭, 휴무 배지) |

구경로 `GET /drivers/users/{userId}` 미사용 — 신경로 `GET /drivers/me` 경유 확인(리포트 13 결함 1의 getByUserId 건 해소).

### 4. 토큰 동작 (영업 시작) — **부분 통과 / 결함 1**

- driverId 캐시: **통과** — 로그인 후 「영업 시작하기」가 `POST /dispatch/online/1` 을 호출, path 의 `1` 은 `GET /drivers/me` 응답 `id:1` (logcat 실측). Bearer 부착 정상(무토큰이면 서버가 401 — 사전 curl 확인).
- online 204: **실패** — `POST /api/v1/dispatch/online/1` → **404** `{"status":404,"error":"Not Found","path":"/api/v1/dispatch/online/1"}` (가입 직후·재로그인 후 2회 실측). 원인은 아래 결함 1.
- 화면은 404 에도 D07 영업중으로 전환됨 — `RemoteDriverRepository.setDutyStatus` (267-286행) 의 **문서화된 "조용한 강등"** (서버 실패 시 더미 상태 전환) 설계 동작이라 UI 결함으로 분류하지 않음. 단 실서버 영업 상태는 등록되지 않는다.

### 5. 스크린샷 — 전부 `_workspace/qa/`

| 파일 | 내용 |
|---|---|
| `v2_signup_done.png` | D05b 가입 완료 (승인 완료 배지, 면허/차량 완료, 콜 ON) |
| `v2_home.png` | D07 홈 (영업중, 콜 대기 토글 on, 하단탭) — 가입 직후 |
| `v2_phone_exists.png` | 스텝1 기가입 배너 + 「로그인하러 가기」 |
| `v2_login_ok.png` | 재로그인 후 D06 홈 (휴무 배지, 하단탭) |
| `v2_d01.png` · `v2_step1.png` · `v2_step2.png` · `v2_step3.png` | D01 · 가입 스텝 1/2/3 (다크 토큰, 진행바, helper 문구) |

## 결함

### 결함 1 (중대 · 경계면 · api-integrator 후속) — DispatchApi 전 경로가 구(path/query driverId) 방식

백엔드 dispatch 도 **토큰 기반(@CurrentDriver)으로 이행 완료**됐는데
(`../backend/src/main/java/team/codingforest/moyeota/dispatch/interfaces/{DriverLocationController,DispatchCallController,RideController}.java` 직접 확인),
클라이언트 `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchApi.kt` 는 전부 driverId 를 붙인다.
15 산출물 §1 의 "dispatch API(여전히 path driverId 사용)는 변경 없음" 전제가 현 백엔드 소스와 어긋남(스펙 스큐).

| DispatchApi.kt | 클라(현재) | 서버(신) | 실기 영향 |
|---|---|---|---|
| goOnline (22행) | `POST /dispatch/online/{driverId}` | `POST /dispatch/online` | **404 실측** — 영업 시작 서버 미반영 |
| goOffline (29행) | `DELETE /dispatch/online/{driverId}` | `DELETE /dispatch/online` | 404 예상 (영업 종료) |
| reportLocation (32행) | `POST /dispatch/location/{driverId}` | `POST /dispatch/location` | 404 예상 |
| acceptCall (40행) | `POST /calls/{partyId}/accept/{driverId}` | `POST /calls/{partyId}/accept` | 404 예상 — **콜 수락 불가** |
| rejectCall (46행) | `POST /calls/{partyId}/reject/{driverId}` | `POST /calls/{partyId}/reject` | 404 예상 |
| callStatus (52행) | `GET /calls/{partyId}/status?driverId=` | `GET /calls/{partyId}/status` (@CurrentDriver) | 경로는 일치 — 잉여 query 는 무시되나 정리 권장 |
| getPartyDetail (59행) | `GET /calls/{partyId}/{driverId}` | `GET /calls/{partyId}` | 404 예상 — 콜 상세 더미 폴백 |
| arrive/board/complete (66-84행) | `/rides/{partyId}/…/{driverId}` | `/rides/{partyId}/…` | 404 예상 — **운행 플로우 서버 미반영** |

수정 방법: DriverApi 와 동일하게 path/query 의 driverId 제거 (파라미터·Repository 시그니처는 호출부 영향 최소화 방향으로 api-integrator 판단).
LocationReportRequest body `{latitude,longitude}` 는 일치 — 경로만 문제.
재검 조건: 수정 후 「영업 시작」 **204** + 영업 종료 204 확인 (콜 수락/운행은 매칭 픽스처 필요 시 별도).

## 관찰 (경미 · 기지)

- 홈 헤더 「박기사 기사님」·오늘 운행 수치는 여전히 더미 HomeSummary (리포트 13 관찰 2와 동일, 설계상 더미 위임 — 서버는 name "kimqa" 반환 실측).
- 스텝1 키보드가 CTA 를 가림 — 시스템 back 으로 내리면 정상. IME 액션/imePadding 은 개선 여지이나 결함 아님.

## 미검증

- 서버 정지 상태의 스텝1 에러 배너(15 산출물 §6-4) — 서버 종료 금지 제약으로 미수행.
- 콜 수락(calls/accept)·운행(rides/*) 실서버 경로 — 결함 1 로 404 확정적이라 실기 생략, 수정 후 재검 대상.

## 생성 테스트 계정 (인메모리 — 서버 재기동 시 소실)

- **qadriver1** / `Qadriver1!` · 휴대폰 `01055551234` · 이름 kimqa · driver id=1, userId=1, **VERIFIED**, callEnabled=true · 차량 sonata/4석/37na1111 · 자격번호 QA111222
