# 30. 기사 앱 ↔ 백엔드 HEAD 동기화 — 리더 확정 계약

분석 산출물: `29a_auth_diff.md`(auth) · `29b_driver_diff.md`(driver) · `29c_dispatch_diff.md`(dispatch) · `29d_matching_fcm_diff.md`(matching/FCM) · `29e_crosscut_diff.md`(공통)

## 전제 (확정 사실)
- **기준은 백엔드 소스 HEAD(`e729801`)**. 배포 서버(`api.moyeota.p-e.kr`)는 09-29~10-10 빌드이고 `taxiEnabled=false` 라
  기사·배차 엔드포인트가 **아예 등록돼 있지 않다**(OPTIONS 404, api-docs 36경로 중 0건). 실기 검증은 서버가
  `TAXI_ENABLED=true` 로 재배포된 뒤에만 가능 — 이번 작업의 완료 조건에서 제외한다.
- 공통 규약(에러 `{code,message}`, 토큰 재발급, Bearer, permitAll)은 **무변경** → 전역 수정 불필요.
- 파티 상세(운행 복구·승객 닉네임), 콜 푸시 type(`CALL_OPENED`/`CALL_CLOSED`)도 **무변경** → 복구 로직 유지.
- 기사 등록(`POST /api/v1/drivers`) 요청 7필드도 **무변경** → 건드리지 말 것.

## A. 문자 인증 가입 (최우선, 현재 가입 불가)

서버 계약 (실제 코드에서 확인):
```
POST /api/v1/auth/sms/send    {"phoneNumber":"010-1234-5678"}            → 204
POST /api/v1/auth/sms/verify  {"phoneNumber":"...","code":"000000"}      → 200 {"verificationToken":"..."}
POST /api/v1/auth/register    {... 기존 8필드 ..., "verificationToken":"..."}  → 201
```
- `code` 는 숫자 6자리(`^\d{6}$`), 전화번호는 `^01[016-9]-?\d{3,4}-?\d{4}$` (클라의 `normalizePhoneNumber` 결과가 통과함)
- 인증 토큰 유효기간 **10분**(`VERIFIED_TTL`) → **인증 단계를 가입 제출 직전에 둔다**
- 에러코드: 쿨다운·만료·시도초과 등 `USER2xx` 계열 (상세는 29a 참고)

### domain 계약 (api-integrator가 이대로 구현, compose-builder는 이 시그니처에 맞춰 화면 작성)
```kotlin
// DriverRepository — 기존 requestOtp/verifyOtp 를 대체한다
suspend fun requestPhoneVerification(phoneNumber: String)          // 실패 시 예외(메시지 그대로 노출 가능)
suspend fun verifyPhoneCode(phoneNumber: String, code: String): String   // 반환값 = verificationToken
suspend fun isNicknameTaken(nickname: String): Boolean             // 신규(제출 전 중복 확인)

// DriverSignUpForm — 필드 추가
val verificationToken: String
```

### presentation
- 가입 3단계 → **4단계**: ①휴대폰 번호 → ②프로필 → ③택시 정보 → **④휴대폰 인증(코드 6자리)** → 제출
  - ④를 마지막에 두는 이유: 인증 토큰 10분 만료. ②③ 입력 중 만료되면 제출이 `USER207` 로 실패한다
  - 코드 입력 중 「재전송」(쿨다운 60초 안내), 실패 시 에러 배너, 성공 시 토큰 보관 후 즉시 제출
- 기존 D02 `PhoneVerifyScreen`(현재 고아 라우트 `Routes.AUTH_PHONE`)의 입력 UI를 **내부 step 으로 재사용**한다.
  라우트 자체는 리더(=나) 외에는 건드리지 않는다.

## B. 안전성 수정 (서버 상태와 무관하게 지금 고칠 것)
1. **404 를 "기사 미등록"으로 단정하지 않기** — `RemoteDriverRepository.login`·`restoreSession` 이 404 를 받으면
   세션 저장을 지워 버린다. 서버가 기사 기능을 꺼둔 경우(지금의 배포 서버)에도 매 실행 로그아웃된다.
   → 에러 바디의 `code == "DRIVER_NOT_REGISTERED"` 일 때만 "미등록" 처리하고 저장을 지운다.
      그 밖의 404 는 **저장 보존 + "서버에서 기사 기능을 사용할 수 없어요"** 류의 메시지로 구분한다.
   → 보조로 `GET /api/v1/config`(permitAll, `{"taxiEnabled":bool}`)를 읽어 `false` 면 그 사실을 문구에 반영한다.
2. **로그아웃 시 FCM 토큰 해제** — `DriverApi.removeFcmToken()` 이 선언만 있고 호출되지 않는다.
   `logout()` 에서 토큰을 비우기 **전에** best-effort 호출(실패 무시).
3. **register 409 무조건 무시 버그** — `USER104`(이미 가입된 번호 등 "그대로 로그인하면 되는 경우")만 통과시키고,
   `USER101`(아이디 중복) 등은 코드별 메시지로 중단한다. 지금은 남의 아이디로 로그인 시도 → 엉뚱한 401 메시지가 뜬다.
4. **릴리즈 빌드 바디 로깅 제거** — `NetworkModule` 이 `Level.BODY` 고정이라 토큰·전화번호가 평문으로 남는다.
   디버그 빌드에서만 BODY, 릴리즈는 NONE(또는 BASIC).
5. 낡은 주석 2곳 정리(`AuthApi.kt:18-19` permitAll 설명, `:49` phone/check 404 설명 — 지금은 200).

## C. 하지 않을 것 (범위 밖, 근거 포함)
- 백엔드 수정 전부 (`TAXI_ENABLED` 플래그, FCM TTL 미설정, Swagger 문서 불일치) → 사용자에게 보고만
- SSE 구독 — 기사는 파티 멤버가 아니라 403, 기사 상태 전이는 SSE 미발행
- 기사 등록/FCM 토큰 API 재작성 — 필드까지 동일, 건드리면 오히려 깨짐
- 패키지 재배치 반영 — HTTP 표면 불변

## 분담
| 에이전트 | 범위 | 파일 |
|---|---|---|
| api-integrator | A의 domain/data + B 전부 | `domain/`, `data/`, `app/`(필요 시) |
| compose-builder | A의 presentation(4단계 가입) | `presentation/feature/auth/` |
| 리더 | 계약·라우트·최종 빌드·PR | `Routes.kt`, `MainNavGraph.kt` |

## 검증
- `./gradlew :app:assembleDebug :data:testDebugUnitTest`
- 단위 테스트: SMS DTO 직렬화, register 에 verificationToken 포함, 404/409 분기, 로그아웃 토큰 해제
- 실기: **서버 재배포(TAXI_ENABLED=true) 후** 가입→로그인→영업→콜 흐름. 그 전까지는 "실서버 미검증"으로 명시

## B 보강 (dispatch 분석 반영)
6. **콜 수락 응답 유실 대비(409 오진)** — `acceptCall` 이 서버에서는 성공했는데 응답만 유실되면(콜드 스타트 7~8초)
   재시도가 409 `CALL_CLOSED` 를 맞고 "이미 마감된 콜"로 안내된다. 실제로는 배차된 상태다.
   → 수락이 409 로 떨어지면 `GET /api/v1/matching/rooms/{partyId}` 로 **소유권을 확인**해
      `taxiDriverId == 내 기사 id` 이면 **성공으로 간주**하고 운행 화면으로 진행한다.
      (이 엔드포인트는 taxi 플래그 게이트가 없어 기사 기능이 꺼져 있어도 살아 있다.)
7. `suspend fun isTaxiServiceEnabled(): Boolean` — `GET /api/v1/config` 의 `taxiEnabled`.
   네트워크 실패 시 `true`(낙관) 로 폴백해 정상 경로를 막지 않는다. B1 의 404 분기 문구에 사용.

### dispatch 에서 **바꾸지 말 것** (분석으로 확정)
- `DispatchApi.kt`·`DispatchDtos.kt`·`DispatchMappers.kt` 전부 와이어 레벨 무변경 — 수정 불필요
- 서버 상수(TTL 30초·반경 1000/500/3000·스윕 30초·타임아웃 3분) 동일 → 하트비트 15초 유지
- `callStatus` 선언은 미사용이어도 유지(푸시 누락 시 확인용으로 백엔드가 문서화)
- 백엔드 Swagger 의 `DRIVER_CANNOT_RECEIVE 403` 표기는 **오기**(실제 409) — 403 분기 추가 금지
