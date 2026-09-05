# 19. api-integrator — 긴급 신고 API 연동 (2026-09-06)

## 연동 엔드포인트

| 메서드 | 경로 | 요청 body | 응답 | Repository 메서드 |
|---|---|---|---|---|
| POST | `/api/v1/reports` | `{partyId: Long?, latitude: Double?, longitude: Double?}` | 200 `{reportId: Long}` | `reportEmergency(tripId: String): Long` |
| PATCH | `/api/v1/reports/call-result` | `{called: Boolean}` | 204 | `confirmEmergencyCall(called: Boolean)` |

- 스펙 원천: **컨트롤러 소스 기준** (`../backend/.../report/interfaces/ReportController.java` + `application/dto/` record 3종 직접 확인). http/*.http 파일에는 report 예시 없음.
- 인증: 전 엔드포인트 `@CurrentUser` (Bearer 필수) → NetworkModule **인증 클라이언트**(`reportApi()`)로 생성. 실서버(localhost:8080) 기동 확인 — 미인증 요청 401 응답으로 Bearer 필수 재확인. **인증 플로우 실호출은 미검증** (읽기 확인만, 서버 무개입 원칙).

## 추가/변경 파일 (data 모듈만)

- `data/remote/dto/ReportDtos.kt` — `ReportRequestDto` / `ReportResponseDto`(reportId nullable 방어) / `CallResultRequestDto` (서버 record 필드명 그대로)
- `data/remote/ReportApi.kt` — POST /reports, PATCH /reports/call-result
- `data/remote/NetworkModule.kt` — `reportApi()` 추가 (authedRetrofit — Bearer + 401 reissue 재시도)
- `data/repository/RemoteDriverRepository.kt` — 생성자에 `reportApi: ReportApi = NetworkModule.reportApi()` (기본값 주입 — **AppContainer 수정 불필요**, 원하면 명시 주입 가능) + 두 메서드 구현
- `data/src/test/.../ReportDtosTest.kt` — 직렬화 테스트 5건

## 위치 소스 결정

- `reportEmergency` 는 하트비트(POST /dispatch/location)와 **동일한** `refreshLocation()` 사용: `locationSource.current()` 실측값으로 캐시 갱신, 측위 실패 시 직전 좌표(최초엔 강남역 기본 좌표) 전송. 신고에 "좌표 없음"보다 "직전 좌표"가 낫다는 하트비트 원칙 그대로.
- `tripId → partyId`: `toLongOrNull()`. 숫자 아님(더미 트립) → `partyId` 필드 자체를 생략 전송(kotlinx 기본값 생략, 서버 Jackson 은 null 로 수신) — 신고자·위치만이라도 저장.

## 실패 정책

- 두 메서드 모두 **예외 전파** (한국어 `IllegalStateException` — "신고 접수 실패: …" / "통화 여부 기록 실패: …", `serverMessage()` 우선). 조용한 더미 폴백 없음 — 신고가 저장된 척하면 안 된다.
- 계약 원칙 재확인: 화면(D15)은 `reportEmergency` 실패와 **무관하게 112 다이얼을 연다**(통화 최우선) → 복귀 후 에러 배너. 이 순서는 presentation 책임(리더 반영 완료).
- 응답에 reportId 누락 시에도 예외 (`requireNotNull`).

## 검증

- `./gradlew :data:compileDebugKotlin :data:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, ReportDtosTest 5/5 통과, 기존 테스트 전체 유지 (:app 은 compose-builder 병렬 작업 중이라 미실행 — 리더 통합 빌드에서 확인 요망)
- DummyDriverRepository 는 리더가 이미 구현(reportId=1L 반환) — 인터페이스 3구현체 정합 확인.

## qa-verifier 교차 검증 요청

- D15 신고 홀드 → POST /reports 저장(로그캣 OkHttp BODY) → 112 다이얼 → 복귀 다이얼로그 → PATCH /call-result 204 확인
- 서버 다운 상태에서 신고 → 다이얼은 열리고 복귀 후 에러 배너 노출 확인
