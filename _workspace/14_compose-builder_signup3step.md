# 14 · compose-builder — 가입 3단계(phone-first) 재구성

## 변경 파일
- `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/SignUpScreen.kt` — 전면 재구성 (유일한 변경 파일)
  - `SignUpRoute` 시그니처 유지 (`repository, onBack, onSignedUp`) → AuthGraph 수정 불필요
  - 단일 라우트(Routes.AUTH_SIGNUP) 내 내부 스텝 상태 머신, `rememberSaveable`로 스텝·전 입력값 보존
  - `StepProgressBar`(AuthCommon) 재사용 — 상단 1/3 · 2/3 · 3/3 표시
  - ViewModel: `checkPhone`(isPhoneRegistered, 로딩·에러·기가입 상태) / `resetPhoneCheck` / `signUp`(기존 제출 로직 유지)
  - 새 `DriverSignUpForm(phoneNumber, name, ...)` 시그니처 반영 → presentation 컴파일 깨짐 해소

## 진입 경로
D01 로그인(AUTH_LOGIN) 「기사 회원가입」 → AUTH_SIGNUP → 성공 시 AUTH_APPROVED(D05b)

## 스텝 전이 다이어그램
```
D01 ──「기사 회원가입」──▶ [스텝1 휴대폰 확인]
                              │ 「다음」→ isPhoneRegistered(하이픈 포맷 번호)
                              ├─ 조회 실패 ──▶ NoticeBanner(ERROR) + 재시도, 화면 유지
                              ├─ true(기가입) ─▶ NoticeBanner(INFO) 안내
                              │                  + CTA가 「로그인하러 가기」로 교체 → onBack(popBackStack, D01 복귀)
                              └─ false(신규) ──▶ [스텝2 회원 프로필]
                                                    │ 「다음」(이름·아이디·비밀번호 유효 시)
                                                    ▼
                                               [스텝3 택시 정보]
                                                    │ 「가입 완료」→ signUp(form)
                                                    ├─ 실패 → NoticeBanner(ERROR), 입력 보존
                                                    └─ 성공 → AUTH_APPROVED(D05b)
back(버튼·시스템 공통, BackHandler):
  스텝3 → 스텝2 → 스텝1 → popBackStack(D01)   ※ 입력값 전부 보존
번호를 수정하면 이전 조회 결과(기가입/에러) 즉시 초기화(resetPhoneCheck)
```

## 검증 규칙 (CTA 활성 조건)
| 스텝 | 필드 | 규칙 |
|------|------|------|
| 1 | 휴대폰 | 숫자만 입력(최대 11자리), `^01[016-9]\d{7,8}$` — 백엔드 `^01[016-9]-?\d{3,4}-?\d{4}$`의 하이픈 제거 등가. 제출 시 `formatPhoneNumber`로 3-4-4(또는 3-3-4) 하이픈 포맷 |
| 2 | 이름 | 비어있지 않음 |
| 2 | 아이디 | `^[a-z][a-z0-9_]{3,19}$` + 입력 필터(소문자화·허용문자만·20자) 유지 |
| 2 | 비밀번호 | 8~64자 + 영문·숫자·특수문자 각 1+ , 마스킹 유지 |
| 3 | 차종·번호판·운수종사자 번호 | 비어있지 않음 |
| 3 | 좌석수 | 정수 ≥ 2 (1자리 숫자 필터) |

- 각 스텝 CTA는 해당 스텝 필드 전부 유효할 때만 활성, 호출 중 loading + 필드 disabled
- OTP 인증은 범위 외 — helperText "본인 인증은 추후 추가 예정" 안내만
- 더미 폴백: 숫자 기준 9999로 끝나는 번호만 기가입(true) — 기가입 분기 수동 테스트용

## 빌드
`./gradlew :app:assembleDebug --console=plain` → BUILD SUCCESSFUL (2026-09-02)

## qa-verifier 참고
- 기가입 분기 재현: 스텝1에 `01000009999` 입력 → 「다음」
- 신규 분기: 그 외 번호(예: `01012345678`) → 스텝2 진행
- back 보존 확인: 스텝3까지 입력 후 시스템 back 2회 → 스텝1, 다시 전진 시 입력 유지
