---
name: moyeota-driver-dev
description: "모여타 기사(드라이버) 앱 개발 에이전트 팀 오케스트레이터. 화면 구현, 기능 추가, API 연동, 피그마 디자인 반영, UI 수정, 버그 수정 등 코드 변경이 수반되는 모든 개발 요청 시 반드시 이 스킬을 사용할 것. 후속 작업도 포함: 다시 실행, 재실행, 수정, 보완, 업데이트, 이전 결과 개선, '화면만 다시', '연동만 다시', '검증만 다시' 요청 시에도 이 스킬을 사용. 단순 코드 질문/설명 요청은 직접 응답 가능."
---

# Moyeota Driver Dev Orchestrator

모여타 기사 앱 개발 작업을 「화면 구현(compose-builder) + API 연동(api-integrator) + 실기 검증(qa-verifier)」 에이전트 팀으로 수행하는 오케스트레이터.

## 실행 모드: 에이전트 팀 (폴백: 서브 에이전트)

- 2개 이상 영역(화면/연동/검증)이 걸리면 팀을 구성한다 — 경계면(Repository 시그니처, DTO shape) 협의가 품질을 좌우하기 때문이다.
- 단일 영역 작업(예: 화면 1개 수정)이면 해당 에이전트 1명만 Agent 도구로 서브 실행한다.
- **TeamCreate 도구가 없는 환경**에서는 서브 에이전트 모드로 대체한다: Agent 도구로 병렬 스폰하고, 데이터 전달은 `_workspace/` 파일 기반으로, 경계면 합의는 리더가 도메인 계약(Repository 인터페이스)을 먼저 확정해 각 에이전트 프롬프트에 명시하는 방식으로 수행한다.
- 화면 대량 구현(그룹 A~F)은 그룹 단위로 compose-builder를 병렬 스폰해도 된다. 단, `Routes.kt`/`MainNavGraph.kt` 등 공유 파일 충돌을 피하기 위해 네비게이션 골격은 리더가 먼저 만들고, 각 에이전트는 자기 feature 디렉토리만 만진 뒤 리더가 NavGraph를 통합한다.

## 에이전트 구성

| 팀원 | 역할 | 스킬 | 출력 |
|------|------|------|------|
| compose-builder | 화면 구현·NavGraph | compose-feature | `_workspace/{NN}_compose-builder_*.md` + 코드 |
| api-integrator | API 연동·data/domain | api-integration | `_workspace/{NN}_api-integrator_*.md` + 코드 |
| qa-verifier | 빌드·실기 검증·경계면 교차 비교·피그마 대조 | verify | `_workspace/{NN}_qa-verifier_report.md` |

에이전트 정의는 `.claude/agents/{이름}.md` — 스폰 시 프롬프트에 "너의 역할 정의 `.claude/agents/{이름}.md`를 먼저 Read하라"를 포함한다. 모델은 세션 기본 모델을 상속한다(하위 모델로 다운그레이드 금지).

## 워크플로우

### Phase 0: 컨텍스트 확인 (후속 작업 지원)

1. `_workspace/` 존재 여부 확인
2. 실행 모드 결정:
   - **미존재** → 초기 실행, Phase 1로
   - **존재 + 부분 수정 요청** → 부분 재실행. 해당 에이전트만 재호출하고, 프롬프트에 이전 산출물 경로를 포함해 기존 결과를 읽고 피드백만 반영하게 한다
   - **존재 + 새 작업 입력** → 새 실행. 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동 후 Phase 1로
3. git 상태를 확인해 진행 중 변경과 충돌하지 않는지 본다

### Phase 1: 준비

1. 요청 분석 — 대상 화면 ID(`docs/FIGMA-SCREENS.md`의 D01~D22), 연동 엔드포인트, 검증 범위 도출
2. 프로젝트 루트에 `_workspace/` 생성, 요청 정리를 `_workspace/00_input.md`에 저장
3. 작업이 화면/연동/검증 중 어느 영역에 걸치는지 판단 → 스폰할 에이전트 결정

### Phase 2: 계약 확정 + 작업 분배

1. 리더가 도메인 계약(Repository 인터페이스 시그니처, 도메인 모델)을 먼저 확정해 `_workspace/01_contract.md`에 기록 — 병렬 에이전트 간 경계면 충돌을 원천 차단한다
2. TaskCreate로 작업 등록. 의존 관계: Repository 시그니처 확정 → 화면 연결, 각 구현 작업마다 대응 검증 작업(incremental QA)
3. 에이전트 스폰 (팀 도구 가용 시 TeamCreate, 아니면 Agent 병렬 호출)

### Phase 3: 구현 + 점진 검증

- api-integrator: 시그니처 변경 시 즉시 compose-builder에게 공유 (팀 모드: SendMessage / 서브 모드: 리더가 중계)
- 구현 완료 시 qa-verifier에게 검증 요청 (변경 파일 + 진입 경로 포함)
- qa-verifier 결함 발견 → 담당 에이전트에게 파일:라인 + 수정 방법으로 요청. 재검증 루프 최대 2회, 이후 리더 에스컬레이션

### Phase 4: 최종 검증 및 통합

1. 모든 작업 완료 확인
2. qa-verifier의 최종 리포트 수집 — 통과/실패/미검증 구분 확인
3. 리더가 직접 최종 빌드 1회: `./gradlew :app:assembleDebug :data:testDebugUnitTest --console=plain`
4. 실패 항목이 남았으면 해당 에이전트 재호출 (1회), 재실패 시 미해결로 명시

### Phase 5: 정리

1. 팀 정리 (팀 모드였다면 TeamDelete)
2. `_workspace/` 보존 (감사 추적용)
3. 사용자에게 보고: 변경 파일, 검증 결과(스크린샷 경로 포함), 미해결·미검증 항목, 남은 작업
4. 피드백 기회 제공 — 개선 요청이 있으면 하네스 진화(CLAUDE.md 변경 이력 갱신)로 연결

## 데이터 흐름

```
[리더] → 계약 확정(_workspace/01_contract.md)
   api-integrator ──시그니처 확정──→ compose-builder
        │                              │
        └──완료 알림──→ qa-verifier ←──완료 알림──┘
        ↓                   ↓                  ↓
  _workspace/NN_api…   NN_qa…report.md   NN_compose…
                            ↓
                   [리더: 최종 빌드 + 보고]
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 에이전트 1명 실패/중지 | 재시작 1회 → 실패 시 작업을 리더가 직접 수행하거나 미해결 명시 |
| 빌드 실패 반복(2회+) | 해당 영역 롤백 여부를 사용자에게 확인 |
| 에뮬레이터 부팅 실패 | qa-verifier가 정적 검증만 수행, 보고서에 "실기 미검증" 명시 |
| 백엔드 미기동/기사 API 부재 | 함부로 띄우지/죽이지 않음. 더미 Repository로 지탱 + "백엔드 미구현 — 더미 연동" 플래그 |
| 경계면 합의 불발 | 리더가 domain 계약(Repository 인터페이스) 기준으로 중재 |
| 공유 파일(NavGraph 등) 충돌 | 네비게이션 골격은 리더 선작성, feature 디렉토리만 에이전트가 작성, NavGraph 통합은 리더 |

## 테스트 시나리오

### 정상 흐름 (화면 그룹 구현)
1. 사용자: "콜 리스트·수락 화면(D09~D12) 만들어줘"
2. Phase 1: 대상 = D09·D10·D11·D12, 스펙은 docs/FIGMA-SCREENS.md
3. Phase 2: 리더가 CallRepository 계약 확정 → compose-builder(화면) + api-integrator(더미/실연동) 병렬
4. Phase 3: 구현 → qa-verifier가 경계면 교차 비교 + 실기 스크린샷 + 피그마 대조
5. Phase 4~5: 최종 빌드 통과, 스크린샷과 함께 보고

### 에러 흐름
1. Phase 3에서 기사용 백엔드 API 부재 발견
2. api-integrator가 도메인 계약 기준 DummyDriverRepository로 지탱, "백엔드 미구현 — 더미 연동" 플래그
3. qa-verifier는 더미 데이터 상태로 화면 품질만 실기 검증
4. 최종 보고에 "실서버 연동 미검증 — 백엔드 기사 API 구현 후 재연동 필요" 명시
