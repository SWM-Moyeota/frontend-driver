# 요청 (2026-08-30)

기사 앱 초기 구축: 피그마 디자인(D01~D22, 24화면)을 승객 레포(../frontend) 기반 구조로 구현.

- 하네스 구성 완료 (.claude/agents 3 + .claude/skills 4 + CLAUDE.md)
- 스캐폴딩 완료: 멀티모듈(app/core:designsystem/domain/data/presentation), 다크 토큰, 도메인 계약, DummyDriverRepository, NavGraph 골격 — 빌드 통과
- 다음: 그룹 A~F 화면을 compose-builder 에이전트 6개로 병렬 구현 (서브 에이전트 모드 — 팀 도구 미가용)
- 백엔드 기사용 API 부재 → 더미 연동 플래그

# 요청 2 (2026-09-01) — 기사용 API 실연동
- 백엔드에 기사용 API 존재 확인됨: DriverController(/api/v1/drivers…), DriverLocationController(/api/v1/dispatch/online·location), DispatchCallController(/api/v1/dispatch/calls…), RideController(/api/v1/dispatch/rides…)
- 존재 엔드포인트 실연동, 부재 영역(콜 리스트/정산/평점/이력/프로모션/로그인·OTP)은 더미 폴백
- Phase 0 판단: 동일 구축 작업의 연장이라 _workspace 유지, 산출물 번호 09부터 이어씀

# 요청 3 (2026-09-02) — MVP 가입 간소화 + 기사 인가 연동
- 가입 입력: 차량번호(번호판)·차종·좌석수·운수종사자 번호 (휴대폰 인증 생략)
- 백엔드 인가 구현됨: /api/v1/auth register·login·reissue·logout (JWT), SecurityConfig anyRequest().authenticated() — 전 기사/dispatch API가 Bearer 필수로 바뀜
- 제약: /auth/register 가 loginId·password 필수 → 가입 폼에 아이디·비번 포함, 이름·생년월일·휴대폰·이메일·성별은 MVP 기본값 자동 채움
