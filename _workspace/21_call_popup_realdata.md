# 21. 콜 팝업 실데이터 — 더미 강등 제거 + 푸시 임시 요약

작업 브랜치: `fix/signup-nickname` (main 아님 — 워킹트리에 가입 닉네임 수정이 미커밋 상태라 그대로 이어서 작업)

## 1. 원인

승객이 호출했는데 기사 화면에 "강남역 2번 출구 · 김\*진 · 판교역"이 뜬 경로는 하나였다.

`RemoteDriverRepository.getCallDetail` 이 **모든 예외를 삼키고** `fallback.getCallDetail(callId)` 로 강등했다.
더미는 id 를 못 찾으면 `calls.first()` 를 돌려주므로(`DummyDriverRepository.kt:163`) 실콜 화면에 데모 콜이 그대로 렌더됐다.

실콜에서 조회가 실패하는 실제 이유:
- 배포 서버(https://api.moyeota.p-e.kr) 유휴 후 첫 응답 7~8초 vs OkHttp connect 5s / read 10s → 통째로 타임아웃
- 콜 TTL 경과·타 기사 선점 시 409 CALL_CLOSED

`CallDetailViewModel` 에는 이미 `UiState.Error("콜 정보를 불러오지 못했어요")` 가 있었지만 리포지토리가 예외를 삼켜 도달하지 못했다.

부수 원인: 상세 조회 성공 전까지 콜 피드가 비어 있어 D09 콜 리스트·홈 배너에도 실콜 정보가 남지 않았다
(`handleCallOpened` 도 실패를 null 로 삼킴).

## 2. 변경 파일

### domain
| 파일 | 변경 |
|---|---|
| `model/CallException.kt` (신규) | 콜 실패의 사용자 문구 + 종류(CLOSED/UNAUTHORIZED/NETWORK/SERVER) + `retryable`. `IllegalStateException` 상속이라 `e.message` 를 읽던 기존 화면과 호환 |
| `model/DriverModels.kt` | `CallSummary.provisional` 플래그 + `hasPassengerCount`(0 = 인원 미상) · `hasFareEstimate` |
| `repository/DriverRepository.kt` | `seedCallPreview(partyId, departure, destination, memberCount, estimatedFare)` · `peekCallSummary(callId)` 추가 (둘 다 **non-suspend** — 푸시 수신 직후 화면 전환보다 먼저 피드를 채워야 하므로 네트워크 왕복을 끼울 수 없다) |

### data
| 파일 | 변경 |
|---|---|
| `remote/DispatchMappers.kt` | `pushCallSummary(...)` — FCM payload → 임시 CallSummary. **요금을 모르면 호출료·보너스도 0** (모르는 값으로 "예상 수익 4,500원" 같은 거짓 금액을 만들지 않는다). 인원 미상·2명 이상은 POOL, 1명 명시일 때만 SOLO |
| `repository/CallFeed.kt` | `find(callId)` 추가 |
| `repository/RemoteDriverRepository.kt` | ① `getCallDetail` 더미 강등 제거 → `callFailure` 로 문구 변환 후 **예외 전파** ② `callFailure` 가 `closedCallOr` 를 대체(409·401/403·IOException·기타 서버오류) — accept/decline 도 같은 매핑을 쓰고 IOException 도 잡도록 넓힘 ③ `seedCallPreview`/`peekCallSummary` 구현 ④ `handleCallOpened` 는 409 일 때만 임시 요약을 걷어내고, 401·네트워크 실패에서는 남긴다 |
| `remote/NetworkModule.kt` | connect 5s→**10s**, read 10s→**20s**, write **20s** 추가 |
| `repository/DummyDriverRepository.kt` | 신규 인터페이스 메서드 구현(`seedCallPreview` = null — 더미는 푸시 피드 없음) |

### app
| 파일 | 변경 |
|---|---|
| `DriverFirebaseMessagingService.kt` | `CALL_OPENED` 에서 `memberCount`/`estimatedFare` 를 파싱해 **`CallAlertBus.open` 보다 먼저** `seedCallPreview` 로 피드를 채운다. 상세 조회 실패 시 알림 본문도 임시 요약으로 폴백하고, 인원·금액은 아는 것만 붙인다 |

### presentation
| 파일 | 변경 |
|---|---|
| `feature/call/CallDetailScreen.kt` | `UiState.Loading(preview)` · `Error(message, preview)` 로 임시 요약 동반. 재시도 가능한 실패(NETWORK/SERVER)는 **1회 자동 재시도** 후 에러 표시. `CallPreviewScreen`(임시 요약 헤더 + 출발/도착 + 에러 배너 + 「다시 불러오기」) 과 `CallActionBar`(수락/거절 공용) 신규 |
| `feature/call/CallListScreen.kt` | 인원 미상 → "합승" 배지, 거리 0·보너스 0 → 해당 문구 생략, 요금 미상 → "예상 수익 확인 중" |

### test (신규 11건, `data/src/test/.../RemoteDriverRepositoryCallTest.kt`)
- 타임아웃 → 더미가 아니라 `CallException(NETWORK)` 전파 · "서버 응답이 늦어요 · 다시 시도해 주세요"
- 409 → "이미 마감된 콜이에요 · 다음 콜을 기다려 주세요", `retryable = false`
- 401 → "기사 인증이 만료됐어요 · 다시 로그인해 주세요"
- 더미 id("call-1")는 기존대로 더미 상세 유지
- 푸시 임시 요약이 `getCalls()`(D09·홈 배너)와 `peekCallSummary()`(D10 헤더) 양쪽에 반영
- 인원·요금 없는 푸시 → passengerCount 0 · expectedTotal 0 (지어내지 않음)
- 상세 성공 시 확정 요약으로 교체 / 실패해도 임시 요약 잔존 / 409 는 피드에서 제거

## 3. 실패 시 화면 동작 (D10)

| 상황 | 화면 |
|---|---|
| 푸시 수신 직후 (상세 로딩 중) | 임시 요약 헤더(합승 N명 또는 "합승", 예상 수익 또는 "확인 중") + 출발/도착 카드 + 헤더 우측 스피너 |
| 상세 성공 | 기존 D10 전체(지도·경유 순서·픽업 거리·예상 수익·15초 카운트다운) |
| 상세 실패 + 임시 요약 있음 | 임시 요약 유지 + `NoticeBanner(ERROR)` "…· 위 내용은 콜 알림으로 받은 정보예요" + 「다시 불러오기」 + **수락/거절 버튼 활성** (마감 여부는 서버가 판정 → 409 면 "이미 마감된 콜이에요" 가 액션 에러로 표시) |
| 상세 실패 + 임시 요약 없음 (프로세스 재시작 후 알림 탭 등) | 기존 `ErrorBox` + 「다시 시도」 — **더미는 절대 표시되지 않음** |

D09 콜 리스트와 홈 배너("N건 대기 중")는 `getCalls()` = 콜 피드 스냅샷을 그대로 쓰므로 임시 요약이 즉시 반영된다.
`DummyDriverRepository` 는 삭제하지 않았고, 숫자가 아닌 콜 id(더미 데모 경로)에서만 쓰인다.

## 4. 검증

```
./gradlew :app:assembleDebug :data:testDebugUnitTest   → BUILD SUCCESSFUL
data 유닛테스트 49건 (신규 11건 포함) 전부 통과
```

**실기 검증은 하지 않았다.** 에뮬레이터 콜드부트 + 배포 서버 기사 계정 시드 + 승객 2계정 curl 파티 생성까지 필요해
비용이 커, 리더 지침대로 빌드 + 유닛으로 마무리했다. 실기에서 확인할 항목:
1. 승객 호출 → 기사 폰 콜 팝업에 **실제 출발/도착**이 뜨는지 (강남역·판교역이 아닌지)
2. `memberCount` 백엔드 패치 반영 후 "합승 N명"이 뜨는지 (미반영이면 "합승"만)
3. 서버를 잠깐 재우고(콜드 스타트) 콜을 받아 임시 요약 + 에러 배너 + 「다시 불러오기」 경로

## 5. 남은 것 / 백엔드 요청

- FCM `CALL_OPENED` data 에 `memberCount`, `estimatedFare` 추가(리더 패치 중) — 없어도 동작하지만 있으면 팝업 첫 화면부터 인원·금액이 뜬다.
- `getActiveTrip()` 은 여전히 `remoteTrip ?: fallback.getActiveTrip()` 로 더미 운행을 폴백한다(이번 범위 밖). 운행 화면에서 같은 종류의 유령 데이터가 보일 수 있어 별도 확인 필요.
