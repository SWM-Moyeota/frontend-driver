# 02 · compose-builder — GRP/A 가입 · 로그인 (D01~D05b)

`./gradlew :presentation:compileDebugKotlin` 통과. 피그마 노드 7개 전부 get_design_context 로 확인 후 Compose 로 번역.

## 변경 파일 (전부 `presentation/src/main/kotlin/com/moyeota/driver/presentation/feature/auth/`)

| 파일 | 화면 | 비고 |
|------|------|------|
| `AuthGraph.kt` | — | 스텁 → 실제 화면 등록 + 네비게이션 배선 (교체) |
| `AuthCommon.kt` | 공용 | StepProgressBar · ChecklistRow · AgreeCheckRow · AuthToggle · RadioOption (auth 전용, internal) |
| `LoginScreen.kt` | D01 | LoginViewModel + LoginRoute + LoginScreen |
| `PhoneVerifyScreen.kt` | D02 | PhoneVerifyViewModel(OTP 타이머 포함) + OtpInput(6자리) |
| `QualificationScreen.kt` | D03 | QualificationViewModel (조회 3회 실패 시 D05 분기) |
| `QualificationResultScreen.kt` | D03b | 스테이트리스 |
| `VehicleScreen.kt` | D04 | VehicleViewModel + 합승 토글(기본 on) + 보너스 1,500원 고지 |
| `ReviewPendingScreen.kt` | D05 | 스테이트리스, CTA 2개 비활성 표기 |
| `ApprovedScreen.kt` | D05b | 스테이트리스, BackHandler 뒤로가기 차단 |

## 화면 진입 경로

- **D01** `auth/login` — 앱 시작 (startDestination)
- **D02** `auth/phone` — D01 「기사 회원가입」
- **D03** `auth/qualification` — D02 「다음」 (verifyOtp 성공 + 필수 약관 2개)
- **D03b** `auth/qualification-result` — D03 「자동 조회하기」 allPassed
- **D04** `auth/vehicle` — D03b 「다음 · 차량 등록」
- **D05** `auth/review-pending` — D01 로그인 PENDING_REVIEW · D03 조회 불일치/3회 실패
- **D05b** `auth/approved` — D04 「가입 완료하기」 성공
- HOME 진입: D01 로그인 APPROVED / D05b 「영업 시작하기」 — 둘 다 `popUpTo(AUTH_LOGIN) { inclusive = true }` 로 가입 스택 전부 제거
- D05b 프로모션 배너 탭 → `call/promotion` (D22)

## 플로우 · 검증 규칙 반영

- CTA 활성: D01 휴대폰 11자리 + 비번 8자↑ / D02 OTP 발송 + 코드 6자리 + 필수 약관 2개 (만료 시 비활성 + 재발송 전환) / D03 면허 12자리 + 일련번호 + 자격증 8자리 / D04 차량번호 + 차종 + 소속
- 제출 중 CTA 비활성 + 로딩 (PrimaryCtaButton loading)
- 필드 오류: D01 로그인 실패 → 비밀번호 필드 errorText / D02 OTP 불일치 → OtpInput error 상태 + danger 문구
- 화면 오류: D02 · D03 · D04 CTA 위 NoticeBanner(ERROR), 입력값 보존
- D02 OTP 3분 타이머 (ViewModel 소유), 만료 시 「재발송」 버튼 전환
- D03 조회 예외 3회 누적 → D05 분기, allPassed=false → D05 분기
- D04 차량번호 유효(7자↑) 시 소속/차종/좌석 자동 채움 (더미 값 — 실연동 시 등록원부 조회로 대체)
- D04 합승 토글 off 시 「프로모션 대상 아님」 warning 배너
- D05 서류 제출 · 고객센터: 미연결 — Toast 없이 비활성 버튼 + 「준비 중」 표기
- D05b BackHandler 로 뒤로가기 차단
- 다크 고정 · MoyeotaColor 토큰만 사용 (`Color(0x` 검색 0건) · 본문 BodyLg 17sp · 터치 타깃 60dp(heightIn)

## 스펙 대비 미구현 항목

- D01: 5회 연속 실패 시 10분 잠금 (서버 정책 — Dummy repo 가 항상 성공이라 클라이언트 잠금 카운터 미구현), 비밀번호 찾기 (미연결 — 비활성 표기)
- D02: 약관 상세 · 개인정보 처리방침 전문 (미연결), 「이미 가입된 번호」 error 배너 분기 (repo 가 구분된 에러 타입을 주지 않아 일반 실패 배너로 통합), 재발송 쿨다운 60초
- D03: 암호 일련번호 도움말 시트 (미연결), 타임아웃 30초 개별 처리 (repo 예외로 일괄 처리), 형식 하이픈 자동 포맷 (숫자만 입력으로 대체)
- D03b: 항목 행 탭 → 조회 근거 안내 (미연결 — 표시 전용)
- D04: 소속 회사 검색 시트 · 명의 불일치 → D05 분기 · 보험 미가입 분기 (repo registerVehicle 이 항상 성공, 실패 정보 없음 → 예외 시 error 배너로 통합), 중복 차량번호 차단
- D05: 반려(Status=canceled) 상태 · 확인 완료 푸시 → D05b 이동 (푸시 인프라 없음)
- D05b: 위치 권한 미허용 시 D06 분기 (권한 처리 미도입 — HOME 상태 분기는 GRP/B 소관), 범죄경력 제출 기한 표기 (정책 TBD)
- 피그마 D02 StepProgress 는 "1 / 4" 로 표기돼 있으나 실제 플로우는 3단계 (D03 "2 / 3" · D04 "3 / 3") — 1/3 · 2/3 · 3/3 으로 통일

## Repository 요청 사항 (api-integrator 참고)

- `login` 이 실패 사유(잠금/미가입)를 구분하지 않음 — 에러 타입 세분화 필요
- `registerVehicle` 이 명의 불일치 · 보험 미가입 분기 정보를 반환하지 않음 → D05 분기 불가
- 차량번호 → 등록원부 자동 조회(차종·좌석·소속) API 없음 — 현재 더미 자동 채움
