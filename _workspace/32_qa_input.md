# 32. 백엔드 동기화 QA (PR #27 대상)

## 대상
- 브랜치 `feature/backend-sync-sms-auth` (PR #27) — 문자 인증 가입 4단계 + 404 오진/409 오안내/FCM 해제/릴리즈 로깅 수정
- 참고: 계약 `_workspace/30_sync_contract.md`, 구현 산출물 `31_api-integrator_sync.md`·`31_compose-builder_signup.md`,
  분석 `29a~29e_*.md`

## 이미 끝난 검증 (중복 금지)
- `:app:assembleDebug` 빌드 통과, `:data:testDebugUnitTest` 155개 통과
- 에뮬레이터: 가입 1/4→4/4 단계 이동, 실서버 `phone/check`·`nickname/check` 200,
  미배포 `sms/send` 404 → 안내 배너 + 재시도 버튼 (크래시 없음)

## 알려진 제약
- 운영 서버는 `taxiEnabled=false` + 구버전 빌드 → 기사/배차 API 부재, 문자 인증 API 부재
- 따라서 "가입 완주 → 로그인 → 영업 → 콜 → 운행" E2E 는 **현재 배포 서버로는 불가**

## QA 분담
| 레인 | 담당 | 산출물 |
|---|---|---|
| L1 코드 교차검증 | 계약 ↔ 구현 일치, 회귀 위험, 누락 | `_workspace/32a_qa_code_review.md` |
| L2 실기 회귀 | 에뮬레이터로 기존 기능 회귀 스모크 | `_workspace/32b_qa_device.md` |
| L3 로컬 백엔드 E2E 가능성 | 로컬 기동 요건 조사 → 가능하면 E2E | `_workspace/32c_qa_local_e2e.md` |
| L4 테스트 보강 | 엣지 케이스 단위 테스트 추가 | `_workspace/32d_qa_tests.md` + 코드 |
