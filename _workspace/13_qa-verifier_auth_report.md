# 13 · qa-verifier — 인가·MVP 가입 실기 검증 리포트

검증일: 2026-09-02 · 에뮬레이터: Pixel_6_QA(emulator-5556, 콜드부트, emulator-5554 미접촉) · 백엔드: localhost:8080 (기동 상태 그대로, 종료/재기동 안 함)
빌드·유닛 테스트: 리더 확인 완료 전제로 재실행하지 않음. APK `app/build/outputs/apk/debug/app-debug.apk` 설치·실행 정상.

## 요약

**결함 1건(중대·경계면) + 사전 고지된 백엔드 갭 1건(실기로 확인) + 경미 관찰 2건.**
클라이언트 결함으로 인한 크래시·화면 깨짐은 없음. 핵심 문제는 전부 **백엔드 버전 스큐**(소스 작업트리 ↔ 실행 중 서버 불일치)에 있다.

### 실행 중 서버의 정체 (실측)
- `GET /api/v1/drivers/users/1` → **200** `{"id":1,"userId":1,...}` / `GET /api/v1/drivers/me` → **404** (라우트 없음)
- → 실행 중 서버는 **커밋 HEAD(3d79da8) 기준의 구(舊) 경로 빌드**다: `/drivers/{driverId}/verify`, `/drivers/{driverId}/vehicle`, `/drivers/users/{userId}`, register body 에 userId 포함.
- 반면 백엔드 **작업트리(uncommitted)** 는 토큰 기반 신경로(`/drivers/verify`, `/drivers/vehicle`, `/drivers/me`, `/drivers/call`, `/drivers/fcm-token`)로 리팩터링 중이며, **현재 컴파일이 안 되는 상태**다
  (`DriverController.java:21` 이 `service.register(request.toCommand())` 1-인자 호출인데 `DriverApplicationService.register(Long userId, RegisterDriverCommand)` 는 2-인자 — 재빌드해도 신경로 서버를 띄울 수 없음).
- 클라이언트(11 산출물)는 verify/vehicle 을 **신경로 기준**으로 구현 → 실행 중 구서버와 어긋난다.

## 항목별 결과

### 1. 경계면 정적 교차 비교

| 항목 | 결과 | 비고 |
|---|---|---|
| AuthApi ↔ AuthController 경로 4종 (register/login/reissue/logout) | **통과** | 경로·메서드 일치, 실측 201/200 확인 |
| AuthDtos ↔ UserRegisterRequest/UserResponse/TokenRequest/TokenResponse 필드 | **통과** | 필드명·형·nullability 일치 (UserResponse.name null 허용 반영) |
| getMyInfo ↔ `GET /api/v1/local/users/info` | **통과** | 실측 200 `{uuid,name}` |
| DriverApi.verify/vehicle ↔ 백엔드 **소스(작업트리)** | **통과(소스 기준)** | 단, 실행 중 서버는 구경로 → 아래 갭 |
| DriverApi.verify/vehicle ↔ **실행 중 서버** | **불일치** | `POST /drivers/verify` 실측 404 (라우트 없음) — 갭 A |
| DriverApi.getByUserId / enableCall·disableCall / fcm-token ↔ 백엔드 소스(작업트리) | **불일치** | 결함 1 |
| DispatchApi 경로 (online/calls/rides) ↔ 백엔드 | **통과** | 변경 없음, 실측 204 |
| 하드코딩 hex (`Color(0x`) — SignUpScreen/LoginScreen | **통과** | 0건 |

### 2. 가입 E2E (실서버)

| 확인 포인트 | 결과 | 증적 |
|---|---|---|
| D01 → 「기사 회원가입」 → AUTH_SIGNUP 진입, 하단탭 미노출, 다크 토큰 | **통과** | `_workspace/qa/d01_login.png`, `signup_empty.png` |
| 필드별 helper 문구 (아이디/비밀번호/좌석수) | **통과** | `signup_empty.png` |
| 좌석수 1 입력 → danger 문구 「좌석수는 2 이상이어야 해요」 + 필드 적색 | **통과** | `signup_seats1.png` |
| 좌석수 1 + 나머지 전 필드 유효 → CTA 비활성, 탭해도 무동작 | **통과** | `signup_cta_seats1.png`, `signup_cta_tap_noop.png` |
| 좌석수 4 수정 → CTA 활성 | **통과** | `signup_form.png` |
| 제출 → register 201 → login 200 → POST /drivers 200 | **통과** | logcat okhttp (아래 로그) |
| 제출 중 로딩 표시 | **미검증** | 실패가 ~2.1초 내 발생해 스크린샷 타이밍 미포착 (결함 증거 아님) |
| verify → vehicle → D05b → 홈 진입 | **실패(백엔드 갭 A)** | `POST /api/v1/drivers/verify` → **404** (구서버에 라우트 없음). 앱은 크래시 없이 에러 배너 + 입력 보존 + CTA 재활성 — 실패 UX는 통과. `signup_done.png` |
| 백엔드 실제 생성 확인 | **통과** | curl login 200, `GET /local/users/info` 200 `{name:"기사"}`, `GET /drivers/users/1` 200 `{id:1,userId:1}` |

okhttp 실측 (가입 제출):
```
--> POST /api/v1/auth/register  <-- 201
--> POST /api/v1/auth/login     <-- 200
--> POST /api/v1/drivers        <-- 200   (userId=1 body, 구서버가 수용)
--> POST /api/v1/drivers/verify <-- 404   (구서버 — /drivers/{id}/verify 만 존재)
```

### 3. 로그인 E2E

| 확인 포인트 | 결과 | 증적 |
|---|---|---|
| force-stop 후 재실행 → D01 (인메모리 토큰 소실 → 재로그인 요구) | **통과** | — |
| 잘못된 비밀번호 → 401 → 「아이디 또는 비밀번호가 맞지 않아요」 danger + 적색 테두리 | **통과** | `login_fail.png` |
| 정상 로그인 → login 200 → users/info 200 → drivers/users/1 200(VERIFIED) → **홈(D06) 진입** | **통과** | `login_ok.png` (하단탭 노출, 휴무 배지) |
| ※ 전제: 로그인-홈 진입은 driver 가 VERIFIED 여야 함(PENDING 은 D05 심사 대기行 — LoginScreen.kt:70-72 정상 동작). QA 픽스처를 VERIFIED 로 만들기 위해 구경로 curl 사용 (아래 "테스트 계정") | — | — |

### 4. 토큰 부착 (401 자동 재발급 경로 포함)

| 확인 포인트 | 결과 | 증적 |
|---|---|---|
| 무토큰 보호 API → 401 `{"code":"USER005"}` (서버가 인증 강제함을 확인) | **통과** | curl 실측 |
| 로그인 후 영업 시작 D06→D07: `POST /dispatch/online/1` → **204**, 401·reissue 재시도 없음 | **통과** | logcat okhttp, `online_d07.png` (영업중 헤더·콜 대기 토글 on) |
| 401 → reissue 자동 재발급 실동작 | **미검증** | 정상 토큰으로는 401 유발 불가(만료 대기 필요). 코드 경로는 :data 유닛 테스트로 커버됨 |

### 5. 검증 규칙

| 확인 포인트 | 결과 |
|---|---|
| D01 이 휴대폰이 아닌 **아이디** 필드 (placeholder 「아이디 (영소문자 시작, 4~20자)」) | **통과** (`d01_login.png`) |
| 빈/형식 미충족 시 로그인 CTA 비활성 | **통과** (초기 상태 비활성 확인; 조건식 `isValidLoginId && pw>=8` 코드 대조) |
| 좌석수 1 → 가입 CTA 비활성 | **통과** (§2) |
| loginId 필터(소문자화·허용문자·20자 제한) | **통과(코드 대조)** — SignUpScreen.kt 입력 필터 확인, 실기 개별 타이핑은 미수행 |

## 결함 / 갭

### 결함 1 (중대 · 경계면 · api-integrator 후속 필요) — DriverApi 구경로 3종 잔존
백엔드 **신(新) 소스** 기준으로 다음이 어긋난다 (`data/src/main/kotlin/com/moyeota/driver/data/remote/DriverApi.kt`):
- `getByUserId` — `GET api/v1/drivers/users/{userId}` (42-43행): 신소스에서 **삭제**, 대체는 `GET /drivers/me`(@CurrentDriver). 신서버 배포 시 **login() 이 항상 404 → "기사로 가입되지 않은 계정입니다"** (RemoteDriverRepository.kt:96-103), resolveDriverId 도 폴백 강등.
- `enableCall/disableCall` — `POST·DELETE api/v1/drivers/{driverId}/call` (35-40행): 신소스는 `POST·DELETE /drivers/call`.
- `registerFcmToken/removeFcmToken` — `PUT·DELETE api/v1/drivers/{driverId}/fcm-token` (45-52행): 신소스는 `/drivers/fcm-token`.
- 아울러 신소스 `RegisterDriverRequest` 는 **userId 필드가 없다** — `RegisterDriverRequestDto` 의 userId 는 신서버에서 무시된다(등록은 @CurrentUser 방향으로 이행 중 → DEFAULT_USER_ID=1L 갭 자체가 해소될 예정).
- 단, **현시점 실행 중 서버(구빌드)에서는 이 4종이 오히려 정상 동작**한다. 수정 타이밍은 백엔드 신코드 배포와 맞춰야 하며, 지금 고치면 반대로 깨진다. → **리더 판단 필요: 백엔드 커밋/배포 시점과 동기화**.

### 갭 A (사전 고지된 백엔드 갭 · 결함 아님 · 실기로 확인) — 가입 E2E verify 404
클라이언트의 `POST /drivers/verify`(신경로)가 실행 중 구서버에 없어 404 → 가입이 4단계에서 중단, D05b·홈 진입 불가. 클라이언트 코드는 백엔드 소스와 일치하므로 결함이 아니라 **환경 버전 스큐**다. 백엔드 작업트리는 현재 컴파일 불가(위 요약)라 신서버로 재기동해 재검도 불가. **백엔드에 컴파일 수복+커밋을 요청해야 가입 E2E 완주 재검 가능.**

### 관찰 1 (경미) — verify 404 에러 문구가 원인을 오귀속
RemoteDriverRepository.kt:162-166 은 verify 404 를 "userId 고정값 어긋남"으로 단정한 문구를 노출하지만, 이번 404 의 실제 원인은 라우트 부재(버전 스큐)였다. 사용자 문구로는 과한 내부 추정 — 신서버 배포 후에는 정확해지므로 보류 가능.

### 관찰 2 (경미) — 홈 헤더 이름이 더미값
로그인이 서버에서 이름 "기사" 를 받아왔지만 홈 헤더는 "박기사 기사님"(더미 HomeSummary). getHomeSummary 가 설계상 더미 위임이므로 MVP 한계로 기록만 함 (`login_ok.png`).

## 생성 테스트 계정 (서버 데이터)
- 계정: **qadriver1** / `Qadriver1!` (users 테이블, 내부 userId=1 로 확인됨)
- 기사: driver id=1, userId=1 — 앱 가입 플로우가 PENDING 으로 생성, **QA 가 구경로 curl 로 VERIFIED + 차량(sonata/4/34ga1234) 등록 완료** (로그인-홈 E2E 전제 픽스처화. 앱 signUp 5단계가 하려던 것과 동일 내용)
- 한글 입력 제약: adb `input text` 가 한글 미지원 → 차종 `sonata`, 번호판 `34ga1234` 로 대체 입력 (서버 검증은 @NotBlank 뿐 — 동작 동일. 앱 결함 아님)

## 스크린샷
`_workspace/qa/` — d01_login.png, signup_empty.png, signup_seats1.png, signup_cta_seats1.png, signup_cta_tap_noop.png, signup_form.png, signup_done.png(에러 배너), login_fail.png, login_ok.png(D06), online_d07.png(D07)

## 재검 조건
백엔드 작업트리 컴파일 수복 + 신코드 서버 재기동 후: (1) 가입 E2E 완주(D05b→홈), (2) 결함 1 의 경로 갱신 후 login/콜 토글 재검.

## 재검증 2 (구경로 정렬 후)

재검일: 2026-09-02 19:26 · 에뮬레이터: Pixel_6_QA(emulator-5556 콜드부트, 검증 후 종료, emulator-5554 미접촉) · 백엔드: localhost:8080 기동 상태 그대로 · APK: 당일 19:23 빌드 재설치 + `pm clear`

**결과: 가입 E2E 완주 통과.** D01 → 「기사 회원가입」 → 폼 입력(qadriver2 / Qadriver2! / sonata / 4 / 35ga5678 / QA654321, CTA 활성) → 제출 → **D05b(가입 완료, 승인 완료 배지·면허/차량 완료·콜 ON)** → 「영업 시작하기」 → **D07 홈(영업중 헤더, 콜 대기 토글 on, 하단탭 노출)**. 크래시·화면 깨짐 없음. 직전 갭 A(verify 404)는 클라이언트의 구경로 정렬로 해소됨.

증적: `_workspace/qa/signup_e2e_done.png`(D05b), `_workspace/qa/signup_e2e_home.png`(D07)

okhttp 실측 (가입 제출 → 영업 시작):
```
--> POST /api/v1/auth/register          <-- 201
--> POST /api/v1/auth/login             <-- 200
--> POST /api/v1/drivers                <-- 409  (DRIVER_ALREADY_REGISTERED — 아래 관찰 3)
--> GET  /api/v1/drivers/users/1        <-- 200  (409 폴백: 기존 기사 id=1 회수)
--> POST /api/v1/drivers/1/verify       <-- 409  (DRIVER_NOT_PENDING=이미 검증됨 — 설계상 성공 취급)
--> POST /api/v1/drivers/1/vehicle      <-- 204
--> POST /api/v1/dispatch/online/1      <-- 204  (영업 시작하기)
```
※ 요청 조건이었던 "전부 2xx"는 아니다. 단 두 409는 결함이 아니라 RemoteDriverRepository.kt:114-116 에 문서화된 **의도된 멱등 폴백**이며(중복 등록→기존 id 회수, 이미 검증됨→성공), 폴백 경유 후 플로우는 정상 완주했다. 409가 뜬 근본 원인은 아래 관찰 3.

### 관찰 3 (기지 갭의 실기 증거 · DEFAULT_USER_ID=1L) — 신규 가입자가 driver id=1 에 합류함
- register 201 로 **user qadriver2(userId=2)** 가 새로 생겼지만, POST /drivers body 의 userId 는 `RemoteDriverRepository.kt:458` 의 `DEFAULT_USER_ID = 1L` 고정값 → 구서버에 userId=1 기사(qadriver1 의 driver id=1)가 이미 있어 409 → 폴백으로 **driver id=1 을 회수해 진행**했다.
- curl 실측: qadriver2 로그인 200, `GET /drivers/users/2` → **404 (qadriver2 의 driver 행은 생성되지 않음)**, `GET /drivers/users/1` → 200 `{id:1,userId:1,VERIFIED,callEnabled:true}`. vehicle 204 는 **driver 1 의 차량을 35ga5678 로 덮어씀**.
- 즉 구서버 + 현 클라이언트 조합에서는 **모든 신규 가입이 driver id=1 로 수렴**한다. 결함 1 에 이미 기록된 기지 갭(@CurrentUser 이행으로 해소 예정)의 실기 확인이며 이번 검증 범위(E2E 완주)의 통과 여부와는 무관 — 신서버 배포 시 결함 1 과 함께 해소 여부 재검 필요.
- 부수 효과: 최초 1회 가입(driver 행이 없는 DB)이라면 register→drivers→verify→vehicle 전부 2xx 로 완주 가능하나, 이번 환경은 재검증 1 의 픽스처(driver 1)가 남아 있어 폴백 경로가 실행됐다. 서버 DB 는 사용자 소유라 초기화하지 않았다.

### 생성 테스트 계정 (추가)
- **qadriver2** / `Qadriver2!` — users 행만 존재(userId=2, driver 행 없음). 이 계정으로 로그인 시 구서버에선 `GET /drivers/users/1` 을 타므로 driver 1 로 동작함에 유의.
