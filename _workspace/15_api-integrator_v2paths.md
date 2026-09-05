# 15 api-integrator — 백엔드 신스펙 전환 (토큰 기반 driver API + 휴대폰 조회)

**⚠ 실서버 미검증 — 서버 재기동 후 E2E 필요.** 구동 중인 서버는 구빌드(`POST /auth/phone/check` 404 실측,
리더 확인)라 이번 작업은 **백엔드 작업트리 소스 기준 정적 연동**이다. 서버는 사용자가 직접 재기동 예정 —
재기동 후 qa-verifier 의 가입/로그인/phone-check E2E 검증이 필요하다.

빌드/테스트: `./gradlew :app:assembleDebug :data:testDebugUnitTest --console=plain` 통과
(테스트 33 = dispatch 11 + auth 15 + driver DTO 4 + TokenStore 3).
스펙 원천: `../backend` 작업트리 `driver/interfaces/DriverController.java`, `user/interfaces/AuthController.java`,
`driver/application/dto/RegisterDriverRequest.java`, `user/domain/PhoneNumber.java` (전부 직접 재확인).

## 1. 신구 경로 대조표

| 구 (11 산출물) | 신 (이번 전환) | 인증 | 비고 |
|---|---|---|---|
| — (없음, 더미) | **`POST /api/v1/auth/phone/check`** | permitAll | body `{phoneNumber}` → 200 `{exists: boolean}`. 형식 불일치 400 INVALID_PHONE_NUMBER |
| `POST /drivers` body 에 `userId` | `POST /drivers` — **body 에 userId 없음** (@CurrentUser) + **vehicle 중첩 필수** | Bearer | 200 DriverResult / 409 DRIVER_ALREADY_REGISTERED. **userId=1L 고정 갭 해소** |
| `POST /drivers/{driverId}/verify` | `POST /drivers/verify` (@CurrentDriver) | Bearer | 204 / 404 DRIVER_NOT_REGISTERED / 409 DRIVER_NOT_PENDING(성공 취급) |
| `POST /drivers/{driverId}/vehicle` | `POST /drivers/vehicle` (@CurrentDriver) | Bearer | 204. 가입 플로우에선 불필요(register 통합) — 레거시 D04 용 유지 |
| `POST·DELETE /drivers/{driverId}/call` | `POST·DELETE /drivers/call` (@CurrentDriver) | Bearer | 204 |
| `GET /drivers/users/{userId}` | **삭제됨** → `GET /drivers/me` (@CurrentUser) | Bearer | 200 DriverResult / 404 DRIVER_NOT_REGISTERED |
| `PUT·DELETE /drivers/{driverId}/fcm-token` | `PUT·DELETE /drivers/fcm-token` (@CurrentDriver) | Bearer | 미사용(연동 대기) — 경로만 신스펙으로 |

auth register/login/reissue/logout, local/users/info, dispatch API(여전히 **path driverId** 사용)는 변경 없음.

## 2. 새 register body shape (서버 record 필드명 그대로)

```json
POST /api/v1/drivers        ← userId 는 토큰에서 해석, body 에 넣으면 안 됨
{
  "qualificationNumber": "서울-2024-001234",
  "bankName": "국민",                    ← 화면 입력 없음, 앱 기본값
  "accountNumber": "000000000000",       ← 앱 기본값
  "vehicle": {                           ← @Valid @NotNull — 필수(차량 정보 register 통합)
    "seats": 4,                          ← @Min(2)
    "plateNumber": "34가 1234",
    "type": "쏘나타"
  }
}
→ 200 {"id":1,"userId":1,"status":"PENDING","callEnabled":false}
```

DTO: `RegisterDriverRequestDto`(+중첩 `VehicleInfoDto`), `PhoneCheckRequestDto`, `PhoneCheckResponseDto{exists=false 기본}`.

## 3. 전화번호 정규화 규칙 (register ↔ phone/check 일원화)

- **데이터 계층 단일 규칙**: `AuthMappers.normalizePhoneNumber(raw)` — 숫자만 남긴 뒤
  11자리 → `010-1234-5678`(3-4-4), 10자리 → `011-123-4567`(3-3-4), 그 외 → 숫자만 그대로(서버 400 검증 위임).
- `toRegisterRequest()`(가입)와 `isPhoneRegistered()`(조회) **둘 다** 이 함수를 거친다 —
  포맷 차이로 "조회는 미등록·가입은 중복" 이 되는 오탐 차단.
- 화면(SignUpScreen)은 숫자만(`01012345678`) 전달 — `formatPhoneNumber` 와 결과 동일하지만
  화면 포맷에 의존하지 않도록 데이터 계층에서 재정규화한다.
- 서버는 어차피 하이픈 제거 저장·비교(`PhoneNumber` record: `value.replace("-","")`,
  패턴 `^01[016-9]\d{7,8}$`)라 하이픈 유무는 결과에 영향 없음 — 표준형은 가독성·일관성용.
- `MvpProfileDefaults.PHONE_NUMBER("010-0000-0000")` 고정값 **삭제** — 이제 폼의 실제 번호를 보낸다.

## 4. Repository 동작 변화 (compose-builder 경계면 — 시그니처는 전부 불변)

- `isPhoneRegistered(phoneNumber): Boolean` — **실연동으로 교체** (더미 폴백 제거).
  실패는 `IllegalStateException("번호 확인 실패: {서버 message|네트워크 오류}")` **예외 전파** —
  스텝1 에러 배너 + 재시도 UI 가 받는다. ⚠ 기존 더미 뒷문(9999로 끝나면 기가입) 소멸 —
  기가입 분기 테스트는 이제 실제 가입된 번호로만 재현 가능.
- `login` — 기사 조회를 `GET /drivers/me` 로 (404 → "기사로 가입되지 않은 계정입니다..." 예외 유지). driverId 캐시.
- `signUp` — 5단계 → **4단계**: register(409 관용) → login → `POST /drivers`(vehicle 통합, 409 면 getMe 회수)
  → verify(409 성공 취급). 별도 차량 등록 호출 제거. 성공 시 driverName 은 폼의 이름(`form.name`, 공백이면 "기사").
- `resolveDriverId` — getMe 기반 (dispatch API 전용 — driver API 는 토큰 기반이라 불필요).
- `checkQualifications` — **더미 위임으로 강등** (레거시 D03, MVP 미사용).
  사유: 구현이 `POST /drivers` 를 자격 검증 대용으로 썼는데 신 register 는 vehicle 필수 —
  차량 입력이 없는 D03 에서 호출 불가. MVP 가입은 signUp 일괄 플로우가 담당.
- `registerVehicle`(레거시 D04) — `POST /drivers/vehicle` + call on/off 신경로(토큰 기반)로 유지.
- userId=1L 고정값·백엔드 갭 주석(11 산출물 §6) **전부 제거** — 갭 해소됨.

## 5. 변경 파일

- `data/.../remote/dto/AuthDtos.kt` — PhoneCheckRequestDto·PhoneCheckResponseDto 추가
- `data/.../remote/dto/DriverDtos.kt` — RegisterDriverRequestDto 재정의(userId 제거 + VehicleInfoDto 중첩)
- `data/.../remote/AuthApi.kt` — checkPhone 추가
- `data/.../remote/AuthMappers.kt` — normalizePhoneNumber 추가, toRegisterRequest 정규화 적용, PHONE_NUMBER 기본값 삭제
- `data/.../remote/DriverApi.kt` — 전 메서드 토큰 기반 경로(path id 제거), getMe 추가, getByUserId 삭제
- `data/.../repository/RemoteDriverRepository.kt` — §4 전체 (userId 생성자 파라미터·DEFAULT_USER_ID 삭제)
- `data/src/test/.../AuthMappersTest.kt` — 신 폼 시그니처 반영 + 정규화 4 + phone-check 직렬화 2 (15개)
- `data/src/test/.../DriverDtosTest.kt` — 신규 4 (register body shape·DriverResult 파싱)

**domain·presentation·app(AppContainer 포함)·NetworkModule 무변경** — Repository 인터페이스 그대로.

## 6. qa-verifier 참고 (서버 재기동 후)

1. phone/check: 미가입 번호 → `{exists:false}` → 스텝2 진행 / 가입 후 같은 번호 → `{exists:true}` → 로그인 유도
2. 가입 E2E: 3단계 폼 → signUp → D05b. 서버 로그에서 `POST /drivers` body 에 userId 없음 + vehicle 중첩 확인
3. 로그인 E2E: 가입 계정 로그인 → `GET /drivers/me` 200 / 미가입 계정 → "기사로 가입되지 않은 계정" 배너
4. 서버 정지 상태에서 스텝1 「다음」 → 에러 배너 + 재시도 (조용한 진행이면 회귀)
