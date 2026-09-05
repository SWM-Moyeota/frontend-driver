# 21 · qa-verifier — 긴급 신고(3초 홀드 → 112 → 복귀 다이얼로그) 실기 검증 (2026-09-06)

환경: Pixel_6_QA(emulator-5558, 콜드부트), 백엔드 localhost:8080 기동(무개입), 계정 qadriver1, `:app:assembleDebug` BUILD SUCCESSFUL(up-to-date APK 설치).

## 결과 요약

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 1 | 빌드 + 설치 + 권한 grant | 통과 | assembleDebug 성공, POST_NOTIFICATIONS·ACCESS_FINE_LOCATION grant |
| 2 | D15 도달 (로그인→영업→수락→…→운행 시작) | 통과* | 아래 "D15 진입 경로 주의" |
| 3 | 짧은 탭 → INFO 배너, 다이얼러 미오픈 | **통과** | "3초간 길게 누르면 신고돼요" 배너 표시, top activity = MainActivity 유지. `qa/emergency_shorttap.png` |
| 4a | 3초 홀드 → 112 입력된 다이얼러 열림 | **통과** | `input swipe 203 2278 203 2278 3400` → com.google.android.dialer 최상단, 번호란 112, 자동 발신 없음. `qa/emergency_dialer.png` |
| 4b | 홀드 중 게이지(왼쪽부터 차오름) | **통과** | 홀드 1.7초 시점 캡처 — 버튼 좌측 절반이 진한 색으로 채워짐. `qa/emergency_holdgauge.png` |
| 4c | POST /api/v1/reports 발사 | **통과(발사)** / 응답은 아래 참조 | body `{"latitude":35.2313,"longitude":129.0838}` — 더미 트립이라 partyId 생략(계약 준수). 응답 **403 REPORT_NOT_ALLOWED** |
| 5 | 복귀 다이얼로그 "112와 실제로 통화하셨나요?" | **통과** | back 2회로 앱 복귀 시 즉시 표시. `qa/emergency_dialog.png` |
| 5b | 바깥 탭 / back 으로 안 닫힘 | **통과** | 바깥 탭 + back 후에도 다이얼로그 유지 (스크린샷 동일) |
| 5c | 저장 실패 ERROR 배너 | **통과** | 403 실패가 다이얼로그 내 "신고 정보 저장에 실패했어요 · 통화 여부는 기록됩니다" 배너로 정확히 표면화 — 상태 전이도의 reportSaveFailed 경로 그대로 |
| 6a | 「통화했어요」 → PATCH /call-result 발사 | **통과(발사)** | body `{"called":true}`. 응답 **404 REPORT_NOT_FOUND** (선행 신고 미저장 탓) |
| 6b | PATCH 실패 시 다이얼로그 유지 + 재선택 배너 | **통과** | "통화 여부를 저장하지 못했어요 · 다시 선택해 주세요" 표시, 버튼 재선택 가능. `qa/emergency_done.png` |
| 6c | PATCH 204 → 다이얼로그 닫힘 + "신고가 접수됐어요" SUCCESS 배너 | **미검증** | 아래 "백엔드 규칙" — 이 환경에서 2xx 도달 불가 |

## API 호출 기록 (logcat okhttp)

```
--> POST /api/v1/reports   {"latitude":35.231298…,"longitude":129.083798…}   (partyId 생략)
<-- 403 {"code":"REPORT_NOT_ALLOWED","message":"운행 중에만 신고할 수 있습니다."}

--> PATCH /api/v1/reports/call-result   {"called":true}
<-- 404 {"code":"REPORT_NOT_FOUND","message":"존재하지 않는 신고입니다."}
```

- **401 아님** — Bearer 인증은 정상 동작(미인증 curl 은 401, 앱 요청은 403/404). 지시서의 "401이면 결함" 기준으로는 결함 없음.
- 403 은 백엔드 비즈니스 규칙: **서버 기준 운행 중(진행 트립 보유)이 아니면 신고 저장 거부**. 더미 트립(call-1)은 서버에 트립이 없으므로 "partyId 없이 위치만이라도 저장"(19번 리포트·리더 전제)은 현 백엔드에서 **불가능**. 이후 PATCH 404 는 그 연쇄(최근 신고 없음).
- 결과적으로 이번 실기는 **실패 경로 전체**(저장 실패 배너 → confirm 실패 배너 → 재선택)를 실증했고, 앱 동작은 상태 전이도와 완전히 일치. **성공 경로(2xx→SUCCESS 배너)는 서버에 실제 진행 트립이 있어야만 검증 가능** — 미검증으로 남김.

## 발견 사항 (코드 결함 아님 — 계약/전제 이슈)

1. **[계약 재협의 필요] partyId 없는 신고는 서버가 403 거부** — `_workspace/01_contract.md` 긴급 신고 계약과 `19_api-integrator_report.md`의 "partyId 생략 → 신고자·위치만이라도 저장" 전제가 백엔드 `REPORT_NOT_ALLOWED` 규칙과 불일치. 앱 쪽 방어(실패 배너)는 이미 올바르므로 수정 불요하나, 리더가 백엔드 규칙 확인 후 계약 문서를 갱신할 것. 담당: 리더 + api-integrator.
2. **[지시서 전제 어긋남] 콜 탭에 더미 콜 카드 없음** — `RemoteDriverRepository.getCalls()`(data/.../RemoteDriverRepository.kt:469)는 FCM 실콜만 반환(더미 병합 의도적 제거, 주석 명시). 실서버 모드에서 "콜 탭 → 더미 콜 첫 카드" 경로는 성립하지 않음. 본 검증은 FCM 알림 탭과 동일한 인텐트 경로(`am start … --es callPartyId call-1` → MainActivity.publishCallAlert → D10)로 우회 진입 — 앱 자체 경로이므로 검증 유효성 훼손 없음. QA 재현 문서의 진입 경로 갱신 권장.

## 부수 확인

- D10 수락→D11→D13→D14→D15 전이 전 구간 정상, 다크 모드 위반 없음(전 화면 다크 토큰), 흰 배경은 지도 타일뿐(정상).
- 다이얼러 이탈→복귀(ON_RESUME) 감지 정확, DialogProperties(dismissOnBackPress/ClickOutside=false) 실기 동작 확인.
- D14 탑승 확인은 코드 입력 없이 즉시 탑승 처리됨(더미 트립) — 코드 4자리 입력 UI는 이번 세션에서 미노출(미검증, 본 검증 범위 외).

## 스크린샷

- `_workspace/qa/emergency_shorttap.png` — 짧은 탭 INFO 배너
- `_workspace/qa/emergency_holdgauge.png` — 홀드 1.7초 게이지
- `_workspace/qa/emergency_dialer.png` — 112 입력된 다이얼러
- `_workspace/qa/emergency_dialog.png` — 복귀 다이얼로그 + 저장 실패 ERROR 배너
- `_workspace/qa/emergency_done.png` — PATCH 실패 재선택 배너(성공 SUCCESS 배너 아님 — 미검증 항목 6c 참조)
