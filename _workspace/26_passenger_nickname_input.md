# 26. 배차 확정(D11) 승객 표시 — 실닉네임 + 경로 줄 제거

## 요청 (사용자)
1. 승객이 "승객1·승객 1" 더미 표기 → **실제 닉네임** 표시
2. 승객 항목 아래 "출발지 → 목적지" 줄 제거 (파티 단위 경로라 승객별 표기 불필요)

## 서버 근거 (실측)
GET /api/v1/matching/rooms/{partyId} — 기사 토큰으로 조회 가능, `members[]`에
`{publicId, nickname, imageUrl, rideCount, ...}` 포함. 백엔드 수정 불필요.

## 리더 확정 계약
1. **data** (api-integrator)
   - 신규 최소 API: `PartyMembersApi`(또는 기존 파일에 추가) — `GET api/v1/matching/rooms/{partyId}`
     응답 DTO 는 `members[].nickname` 만 파싱 (ignoreUnknownKeys, 나머지 필드 무시. route 등 대형 필드 파싱 금지)
   - NetworkModule·AppContainer 배선 (기존 Retrofit 인스턴스 재사용)
   - RemoteDriverRepository: `acceptCall`(및 ActiveTrip 을 새로 만드는 지점)에서 닉네임 조회를 **베스트에포트**로 수행 —
     성공 시 `TripPassenger.maskedName` 을 닉네임으로 교체(순서 매칭), 실패·미제공 시 기존 "승객N" 유지.
     운행 화면(D15) 스톱 매칭이 passengerMaskedName 문자열 대응이므로 stops 의 이름도 함께 교체해 일관성 유지.
   - 단위 테스트: members DTO 파싱(닉네임/누락) + 닉네임 주입 매핑.
2. **presentation** (리더 직접)
   - CallAssignedScreen(D11): 승객 행에서 "출발지 → 목적지" 줄 제거, 닉네임 pill("승객 N") 중복 표기 정리.
3. 도메인 모델 변경 없음 (maskedName 필드 재사용 — 이름 의미는 "표시 이름").

## 검증 (리더)
- assembleDebug + :data:testDebugUnitTest
- 에뮬레이터: 파티 생성(닉네임 있는 승객) → 수락 → D11 에 닉네임 표시·경로 줄 없음 확인
