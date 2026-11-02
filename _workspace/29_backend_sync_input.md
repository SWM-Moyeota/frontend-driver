# 29. 백엔드 변경분 전면 동기화 (기사 앱)

## 배경
- 기사 앱이 마지막으로 맞춘 백엔드 커밋: `b9a921d` (2026-09-09, 기사 닉네임 호출 API 수정)
- 현재 백엔드 HEAD: `e729801` (2026-10-13) — 그 사이 **273 커밋**, API 표면 파일 121개 변경
- 눈에 띄는 변경 신호:
  - **문자 인증(SMS) 가입 흐름 추가** + `refactor: 쓰지 않는 옛 회원가입 API 삭제` ← 기사 가입이 깨졌을 가능성 높음
  - 기사 FCM 토큰 관련 수정 2건, 배차 중복 호출 방지, 신고 저장 롤백 수정
  - dispatch/driver/matching 패키지 대규모 재배치(dto 이동) — 경로 변경만일 수도, 시그니처 변경일 수도
  - 매칭방 SSE 를 변경분 전달 방식으로 전환 (승객용일 가능성)

## 이번 작업 목표
기사 앱(`/Users/sungyoon/Desktop/moyeota/frontend-driver`)이 **현재 백엔드와 실제로 통신 가능**하도록 API 계층을 맞춘다.
- 깨진 호출(엔드포인트 삭제/경로 변경/필드 변경) 전부 식별 → 수정
- 새로 생긴 필수 단계(문자 인증 등)는 화면까지 반영
- 백엔드는 **수정하지 않는다** (읽기 전용 참고)

## 분석 분담 (읽기 전용, 코드 수정 금지)
| 에이전트 | 범위 | 산출물 |
|---|---|---|
| auth | user/auth 도메인 전체 + 문자 인증 | `_workspace/29a_auth_diff.md` |
| driver | driver 도메인 | `_workspace/29b_driver_diff.md` |
| dispatch | dispatch(call·ride·location) | `_workspace/29c_dispatch_diff.md` |
| matching | matching/party + FCM 페이로드 + SSE | `_workspace/29d_matching_fcm_diff.md` |
| crosscut | 보안 설정·에러 응답·공통 규약 | `_workspace/29e_crosscut_diff.md` |

## 산출물 양식 (각 에이전트 공통)
1. **현재 백엔드 API 표** — 메서드·경로·인증 주체(@CurrentUser/@CurrentDriver/permitAll)·요청 필드·응답 필드·주요 에러코드
2. **기사 앱 클라이언트 현재 상태** — 대응 파일:라인
3. **불일치 목록** — 심각도(깨짐/동작변경/무해), 증상, 고칠 지점
4. **권장 수정안** — 클라이언트 쪽에서 무엇을 어떻게 바꿔야 하는지 (코드 스케치 수준)
