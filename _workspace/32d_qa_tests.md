# 32d. L4 테스트 보강 (PR #27 대상) — api-integrator

## 요약

| 항목 | 값 |
|---|---|
| 추가 테스트 | **42건** (신규 파일 3 + 공용 가짜 1) |
| 최종 테스트 수 | **197건 통과 / 0 실패 / 0 스킵** (기존 155 → 197) |
| 실행 명령 | `./gradlew :data:testDebugUnitTest` → BUILD SUCCESSFUL |
| 프로덕션 코드 변경 | **없음** (`git status`: 추가된 파일 전부 `data/src/test/**`) |
| `@Ignore`·실패 잔존 | 없음 |
| 발견 결함 | 2건 (경계면 불일치 1 · 낡은 주석 1) — **고치지 않고 보고만** |

추가 파일:
- `data/src/test/kotlin/com/moyeota/driver/data/repository/EdgeCaseFakes.kt` (공용 가짜 + 에러 바디 조립기, 테스트 아님)
- `data/src/test/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepositorySmsAuthTest.kt` (13건)
- `data/src/test/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepositorySignUpChainTest.kt` (13건)
- `data/src/test/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepositoryFailureBranchTest.kt` (16건)

> `EdgeCaseFakes.kt` 를 따로 둔 이유: 기존 테스트 파일들이 각자 최상위 `private` 가짜를 들고 있고(`FakeAuthApi`·`SessionAuthApi`·`UnusedAuthApi` …), 같은 패키지에 같은 이름을 또 두면 JVM 클래스 이름이 충돌한다. `Edge*` 접두사로 한 벌만 두고 **호출 횟수를 기록**하게 만들어, "호출되지 않아야 하는 경로"를 `fail()` 대신 카운터로 단정한다.

---

## 1. 추가한 테스트 (42건)

### A. 문자 인증 — `RemoteDriverRepositorySmsAuthTest` (13건)

기존(`AuthSyncTest`)은 "서버 message 가 있을 때 그대로 올라오는가"(429 USER202)와 "토큰이 빈 200"을 덮는다.
보강한 구간은 **body 가 없는 응답 · 확인(verify) 경로의 HttpException 분기 · 취소 전파**다.

| 테스트 | 검증하는 분기 |
|---|---|
| `발송 429 에 본문이 없으면 과다 요청 폴백 문구를 쓴다` | 프록시가 끼어든 429 → `authFailureMessage` 폴백 "요청이 너무 많아요 · 잠시 후…" |
| `발송 일일 한도 429 USER203 은 서버 문구가 그대로 온다` | 시도 초과 코드의 서버 문구 우선 |
| `발송 409 USER104 는 이미 가입된 번호임을 알린다` | 문자 비용 전 서버 선거절(기가입 번호) |
| `발송 5xx 에 본문이 없으면 인증 불가 폴백 문구를 쓴다` | 502·503(USER201/USER208) body 없음 → "지금은 인증을 진행할 수 없어요" |
| `발송 네트워크 실패는 실패 사실과 원인을 함께 올린다` | IOException → "인증번호를 보내지 못했어요 · timeout" |
| `발송 중 취소는 실패로 바꾸지 않고 그대로 올린다` | `CancellationException` 전파 (삼키면 사라진 화면에 배너) |
| `인증번호 확인도 하이픈 표준형으로 정규화해 보낸다` | verify 경로의 `normalizePhoneNumber` — 서버의 번호 해시 대조와 어긋나면 USER207 |
| `인증번호 만료 400 USER204 는 서버 문구가 그대로 온다` | 만료 분기 |
| `입력 횟수 초과 429 USER206 은 서버 문구가 그대로 온다` | 시도 초과 분기 |
| `확인 400 에 본문이 없으면 호출부 문구에 상태코드를 덧붙인다` | USER205 류 body 없음 폴백 |
| `공백뿐인 토큰 200 응답도 인증 실패로 처리한다` | `isNotBlank` 경계 (빈 문자열은 기존 테스트가 덮음) |
| `확인 네트워크 실패는 실패 사실과 원인을 함께 올린다` | IOException 문구 |
| `확인 중 취소는 실패로 바꾸지 않고 그대로 올린다` | `CancellationException` 전파 |

### B. 가입 체인 중간 실패 — `RemoteDriverRepositorySignUpChainTest` (13건)

`register → login → POST /drivers → /drivers/verify` 네 왕복 중 **어디서 멈췄는지**와 **남은 상태**(토큰·저장·다음 단계 호출 여부)를 함께 단정한다. 기존 테스트는 1단계 409 코드 분기까지만 덮었다.

| 테스트 | 멈춘 단계 | 단정한 상태 |
|---|---|---|
| `인증 만료 400 에 본문이 없어도 멈춘 단계와 상태코드를 알린다` | ① 계정 생성 | 문구 = `회원가입 실패 — 계정 생성: … 서버 오류(400)`, 로그인 0회, 토큰 없음 |
| `계정 생성 후 로그인이 5xx 로 실패하면 토큰을 저장하지 않고 단계를 알린다` | ② 자동 로그인 | 미로그인, 기사 등록 0회 |
| `중복이 아닌 로그인 401 은 서버 문구를 그대로 쓴다` | ② | 중복 안내로 바꾸지 않음(거짓 안내 방지) |
| `전화번호 중복 409 뒤 로그인 401 은 로그인 화면 안내로 바뀐다` | ② | `duplicateAccountMessage(USER104)` 분기 |
| `전화번호 중복 409 라도 내 계정이면 가입이 이어진다` | — | USER104 관용 성공 경로(③④ 각 1회) |
| `기사 등록 5xx 는 세션을 남기고 멈춘 단계를 알린다` | ③ 기사 등록 | **토큰은 남는다**(재제출이 ③부터 이어져야 함), 자격 검증 0회 |
| `기사 등록 409 는 기존 기사를 조회해 가입을 이어 간다` | — | `GET /drivers/me` 회수 1회 + 검증 1회 |
| `기사 등록 409 인데 기존 기사 조회까지 실패하면 멈춘다` | ③ | 검증 0회 |
| `자격 검증 409 는 이미 검증됨으로 보고 가입을 완료한다` | — | APPROVED |
| `자격 검증 5xx 는 가입 실패로 올린다` | ④ 자격 검증 | 세션 유지(검증만 재시도 가능) |
| `자격 검증이 네트워크로 실패해도 단계가 드러난다` | ④ | 문구에 단계 노출 |
| `닉네임이 비면 실명을 표시 이름으로 쓴다` | — | 기본값("기사")으로 덮지 않음 |
| `가입 성공은 로그인 토큰을 저장소까지 반영한다` | — | write-through 저장 |

### C. 404·409·로그아웃·게이트 — `RemoteDriverRepositoryFailureBranchTest` (16건)

| 테스트 | 검증하는 분기 |
|---|---|
| `다른 코드의 404 는 기사 미등록이 아니라 저장을 보존한다` | 404 + `DRIVER_NOT_FOUND`·`USER103`·`PARTY_NOT_FOUND` → 보존 (3가지 반복) |
| `본문이 없거나 JSON 이 아닌 404 도 저장을 보존한다` | body 없는 404(프록시) · HTML 404(CDN) → 파싱 실패를 "미등록"으로 읽지 않음 |
| `파티 조회의 코드 없는 404 는 활성 파티를 지우지 않는다` | **Spring 기본 404**(실서버 모양) + 다른 코드 404 → `activePartyId` 보존 (기존 테스트는 `PARTY_NOT_FOUND` 삭제 쪽만 덮음) |
| `로그인 중 본문 없는 404 는 서버 설정을 읽어 원인을 좁힌다` | body 없는 404 + `taxiEnabled=false` → "택시 서비스가 꺼져", `config` 1회 조회, "회원가입을 진행" 미포함 |
| `로그인 중 404 안내는 설정 조회가 실패해도 안내 문구를 만든다` | 설정 조회 실패 → 낙관 폴백 → "기사 기능을 사용할 수 없어요" |
| `수락 409 에 파티 조회가 실패하면 마감으로 남는다` | B6 확인 불가(파티 5xx) → CLOSED + 저장 무변경 + 조회 1회 시도 |
| `수락 409 에 내 기사 정보 조회가 실패하면 파티를 묻지 않는다` | 내 id 미상 → 파티 조회 0회 |
| `수락 409 에 배정 전 파티는 내 운행으로 주장하지 않는다` | `taxiDriverId=null` → CLOSED |
| `수락이 409 가 아니면 소유권을 되묻지 않는다` | 500 → SERVER·retryable, 파티·getMe 0회(헛된 왕복 없음) |
| `소유권으로 복원한 운행도 콜 피드에서 내려가고 저장된다` | 409 복원 경로도 `settleAcceptedTrip` 뒤처리(피드 소거 + partyId 저장 + 캐시) |
| `서버 로그아웃이 실패해도 로컬 세션은 비워진다` | `POST /auth/logout` 500 → 예외 없이 토큰·파티 비움 |
| `로그아웃은 저장된 refreshToken 으로 서버 세션을 무효화한다` | 전달된 body 값까지 단정 + FCM 해제 1회 |
| `미로그인 로그아웃은 FCM 해제도 서버 무효화도 호출하지 않는다` | 두 호출 모두 0회(카운터 단정) |
| `로그아웃은 진행 중 운행 캐시까지 비운다` | 수락 → 로그아웃 → `getActiveTrip()` null, 저장 전부 비움 |
| `서버 설정 5xx·타임아웃도 열림으로 폴백한다` | 503·타임아웃·직렬화 실패 → `true`(게이트가 기사를 가두지 않음) |
| `서버 설정 조회의 취소는 열림으로 뭉개지 않는다` | `CancellationException` 전파 |

---

## 2. 발견한 프로덕션 결함 (수정하지 않음 — 보고만)

### D4-b. 로그인이 실패해도 세션이 영속된다 → 다음 실행은 로그인 화면을 건너뛴다 · 심각도 **중**

- **위치**: `data/.../repository/RemoteDriverRepository.kt:345` (`tokenStore.update(...)`) ↔ `:347-351` (`driverApi.getMe()` 실패 시 예외) · 판정은 `:369-390` `driverLookupFailure`
- **사실**: `login()` 은 토큰을 **먼저 저장(write-through)** 하고 그 다음 `GET /drivers/me` 를 호출한다. 현 배포 서버 상태(`taxiEnabled=false` → code 없는 404)에서 사용자는 `"서버에서 기사 기능을 사용할 수 없어요 · 잠시 후 다시 시도해 주세요"` 를 보고 **로그인 실패로 인지**하지만, 토큰 쌍은 `DriverSessionStorage` 에 남는다.
- **결과**: 다음 실행의 시작 게이트 `restoreSession()` 이 `loggedIn = true` 를 돌려줘(code 없는 404 = 보존 정책, 올바름) 기사는 **로그인 화면을 건너뛰고 홈에 착지**한다. 32a 의 **D4**(복구 경로에 안내 채널 없음)와 합쳐지면 "로그인은 실패했는데 다음 실행엔 조용한 정상 홈"이 된다.
- **실측(일회성 프로브로 확인, 커밋하지 않음)**:
  `login` 실패 메시지 = `서버에서 기사 기능을 사용할 수 없어요 · 잠시 후 다시 시도해 주세요` / 직후 `tokenStore.isLoggedIn() = true`, `storage.tokens = SessionTokens(access-1, refresh-1)` / 이어서 `restoreSession().loggedIn = true`.
- **재현 테스트(수정 담당 레인이 붙일 이름)**: `RemoteDriverRepositoryFailureBranchTest.로그인 실패는 세션을 영속하지 않는다`
  ```kotlin
  // 현재 구현에서는 실패한다 (의도 결정 후 수정 레인이 추가할 것)
  @Test
  fun `로그인 실패는 세션을 영속하지 않는다`() = runBlocking {
      val storage = EdgeStorage()
      val tokenStore = TokenStore().apply { attachPersistence(storage) }
      val repository = repository(
          storage = storage,
          tokenStore = tokenStore,
          driverApi = EdgeDriverApi(onGetMe = { throw edgeSpringNotFound() }),
      )

      runCatching { repository.login("driver01", "test123!@") }

      assertFalse(tokenStore.isLoggedIn())
      assertNull(storage.tokens)
      assertFalse(repository.restoreSession().loggedIn)
  }
  ```
- **판단 필요(리더)**: 두 방향이 있고 둘 다 테스트로 못 박을 수 있다.
  1. **실패 시 토큰 회수**: `login()` 의 `getMe` 실패 경로에서 `tokenStore.clear()` — "실패했다"는 화면 신호와 다음 실행이 일치한다. 단, 기사 기능만 꺼진 서버에서 매번 재로그인을 요구한다.
  2. **현 동작 유지 + 안내**: 32a 의 D4 수정안(`RestoredSession.serviceNotice`)과 묶어, 홈에 "서버에서 기사 기능을 사용할 수 없어요" 배너를 띄운다. 이 경우 위 테스트 대신 "실패한 로그인 뒤에도 세션은 보존되고 안내가 실린다"로 명세를 바꿔 고정해야 한다.
- **스코프 주의**: `login`/`restoreSession` 은 L1·L2 레인이 같은 워킹트리에서 검토 중이라 본 레인은 코드를 건드리지 않았다.

### D6. `restoreSession` 주석이 판정과 어긋난다 (낡은 주석) · 심각도 **하**

- **위치**: `RemoteDriverRepository.kt:202-203` — "DRIVER_NOT_REGISTERED/**DRIVER_NOT_FOUND** 면 … 확인된 종료"
- **사실**: 판정 함수 `ServerErrorCodes.isDriverMissing`(`data/.../remote/AuthMappers.kt`)은 `DRIVER_NOT_REGISTERED` **하나만** 확인된 종료로 본다. `AuthMappersTest.code 없는 404 와 다른 코드의 404 는 기사 미등록이 아니다` 가 `DRIVER_NOT_FOUND = false` 를 이미 고정하고 있고, 32a 의 백엔드 확인(`DriverService.java:108`·`CurrentDriverArgumentResolver.java:41`)에 따르면 **판정 쪽이 옳다**.
- **조치 제안**: 코드 수정 없이 주석에서 `DRIVER_NOT_FOUND` 만 삭제. 본 레인 신규 테스트 `다른 코드의 404 는 기사 미등록이 아니라 저장을 보존한다` 가 실제 동작을 못 박아 둔다(주석을 믿고 판정을 "넓히는" 회귀를 차단).

### 점검했으나 문제 없음

- **errorBody 1회성 규칙**: `retrofit2.HttpException.errorBody()` 는 1회성이라 `serverError()`/`serverMessage()`/`errorCode()` 를 한 예외에 두 번 부르면 두 번째가 `null` 이 된다. 실패 처리 전 경로를 훑은 결과 **이중 읽기는 없다** — `restoreSession`(`errorCode` 1회) · `restoreOngoingTrip`(1회) · `login`(1회) · `driverLookupFailure`(`serverError` 1회로 code·message 동시 사용) · `isNicknameTaken`(1회) · `registerOrDuplicate`(1회) · `step`(1회) · `callFailure`(409·401 은 body 미독) · `declineCall`(1회).
- **`step()` 의 예외 분류 순서**: `IllegalStateException` → `HttpException` → `Exception` 순이며 상호 배타적이라 순서 의존 버그 없음. 하위 단계가 이미 사용자 문구로 바꾼 실패(`registerOrDuplicate`)는 첫 분기로 들어가 문구가 중복 변환되지 않는다.
- **`fail()` 을 쓴 가짜의 엄격성**(기존 테스트): `AssertionError` 는 `Error` 라 `catch (_: Exception)` 에 삼켜지지 않는다 — 본 레인은 같은 의도를 카운터 단정으로 바꿔 더 분명하게 고정했다.

---

## 3. 본 레인 범위 밖 (핸드오프)

- 32a 가 L4 에 권고한 **`SignUpViewModel` 단위 테스트**(제출 실패 후 토큰·쿨다운 상태, verify 실패 종류별 `codeInvalid`)는 `presentation/src/test` 소스셋이 **존재하지 않아** 추가하지 못했다. 본 레인 지시는 `data/src/test/**` 한정이고, 새 테스트 소스셋 생성은 `presentation/build.gradle.kts` 와 의존성 추가(프로덕션 모듈 설정 변경)를 수반한다 → **리더 판단 + compose-builder 레인**으로 넘긴다.
  - 32a 의 D1·D2·D3(가입 화면 상태 머신 결함)은 `:data` 테스트로는 구조적으로 잡히지 않는다는 지적에 동의한다. 다만 D1(인증 토큰 재사용 가능)의 **데이터 계층 전제**는 본 레인이 고정했다 — `기사 등록 5xx 는 세션을 남기고 멈춘 단계를 알린다`·`인증 만료 400 에 본문이 없어도 …`(토큰 미소모·재제출 가능 경로).
- 실서버·에뮬레이터 검증은 L2·L3 담당. 본 레인은 **단위 테스트만** 수행했다(네트워크 미사용, 서버 기동 없음).

## 4. 완료 기준 확인

```
$ ./gradlew :data:testDebugUnitTest --console=plain
BUILD SUCCESSFUL
TOTAL tests 197  failures 0  errors 0  skipped 0
```

| 클래스 | 테스트 수 |
|---|---|
| `RemoteDriverRepositoryAuthSyncTest` | 26 |
| `RemoteDriverRepositorySessionTest` | 19 |
| `RemoteDriverRepositoryCallTest` | 16 |
| **`RemoteDriverRepositoryFailureBranchTest`** | **16 (신규)** |
| `RemoteDriverRepositoryDutyLocationTest` | 10 |
| **`RemoteDriverRepositorySignUpChainTest`** | **13 (신규)** |
| **`RemoteDriverRepositorySmsAuthTest`** | **13 (신규)** |
| `AuthMappersTest` | 32 |
| `DispatchMappersTest` | 21 |
| `PartyRecoveryMappersTest` | 9 |
| `TokenStoreTest` | 7 |
| `DriverDtosTest` · `PartyMemberDtosTest` · `CallFeedTest` | 5 · 5 · 5 |
