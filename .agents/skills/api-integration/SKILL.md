---
name: api-integration
description: "모여타 기사 앱의 백엔드 API 연동 레시피. 새 엔드포인트 연동, 액션 API(영업 시작/콜 수락/운행 완료 등) 연결, DTO·매퍼·Repository 추가/수정, 서버 데이터 화면 연결 등 data/ 또는 domain/ 모듈을 건드리는 모든 작업 시 반드시 이 스킬을 사용할 것. '연동', 'API', '백엔드', 'Retrofit', 'DTO' 언급 시 이 스킬을 사용할 것."
---

# 모여타 기사 앱 API 연동 레시피

## 데이터 흐름 (계층 계약)

```
../backend (Spring)
  → DriverApi (data/remote, Retrofit suspend fun)
  → DTO (data/remote/dto — 서버 shape 그대로)
  → Mappers (DTO → 도메인 변환, 이 파일에서만)
  → RemoteDriverRepository (data/repository)
  → DriverRepository (domain/repository — presentation이 보는 유일한 계약)
  → ViewModel (presentation)
```

presentation은 DTO를 절대 보지 않는다. 계층을 건너뛰면 서버 스펙 변경이 전 화면으로 번진다.

## 연동 순서

1. **실제 스펙 확인 (추측 금지)**: `../backend/http/*.http`로 요청 예시를 확인하고, 컨트롤러/응답 DTO 소스를 직접 읽는다. 백엔드는 Java(Spring)이며 컨트롤러는 `../backend/src/main/java/team/codingforest/moyeota/{도메인}/interfaces/`에 있다. 서버가 떠 있으면 실제 응답이 최우선:
   ```bash
   curl -s http://localhost:8080/api/... | head -50
   ```
   백엔드는 인메모리라 재시작 시 데이터가 초기화된다. 서버는 사용자가 띄운 프로세스일 수 있으니 함부로 재시작/종료하지 않는다.
2. **기사용 엔드포인트 부재 시**: 백엔드에 기사용 API가 아직 없으면 만들어내지 말고, `DummyDriverRepository`(더미 데이터)가 화면을 지탱하게 한다. 도메인 계약(모델 + Repository 인터페이스)은 화면 스펙 기준으로 먼저 확정하고, 산출물에 "백엔드 미구현 — 더미 연동" 플래그를 명시한다
3. **DTO 추가**: `data/remote/dto/`에 서버 응답 필드명 그대로 정의. 서버가 누락 가능한 필드는 nullable + 기본값으로 방어한다
4. **API 메서드 추가**: `DriverApi`(또는 새 인터페이스)에 `suspend fun`으로 추가. 새 인터페이스면 `NetworkModule`에 create 함수를 추가하고 `AppContainer`에서 생성한다 (baseUrl은 에뮬레이터 기준 `http://10.0.2.2:8080/`)
5. **도메인 확장**: `domain/model/`에 모델 추가, `domain/repository/DriverRepository`에 메서드 추가
6. **매퍼 + Repository 구현**: 변환 함수 작성, `RemoteDriverRepository`에 구현. `DummyDriverRepository`도 같은 인터페이스를 구현하므로 함께 갱신한다 (컴파일 깨짐 방지)
7. **테스트**: `data/src/test/`에 매퍼 단위 테스트 추가 — 필드 매핑, nullable 방어를 검증:
   ```bash
   ./gradlew :data:testDebugUnitTest --console=plain
   ```
8. **ViewModel 연결**: 화면 쪽 연결은 compose-builder와 협의. Repository 시그니처(메서드명·파라미터·반환 도메인 모델)를 먼저 확정해 공유한다

## 액션 API (POST/PUT) 연동 시 추가 규칙

- 액션 성공 후 화면 상태 갱신 필요 — "액션 성공 → 어느 목록/상세를 refresh해야 하는가"를 산출물에 명시한다
- 콜 수락(D10)·요금 확정(D16) 같은 상태 전이 액션은 실패 시 화면 유지 + 재시도가 원칙 (피그마 공통 규칙)
- 실패 응답(4xx)의 body 형식을 확인하고, ViewModel에서 한국어 사용자 메시지로 변환한다
- 금액 계산 규칙: 기사 정산액 = 승객 청구 총액 − 서비스 수수료(5%) ± 보너스·차감. 1인 부담 = (최종 요금 + 서비스 수수료) ÷ 분담 인원, 10원 단위

## 산출물 기록

연동 완료 시 다음을 산출물 파일에 기록한다 (qa-verifier의 교차 검증 입력이 된다):
- 연동한 엔드포인트 목록 (메서드 + 경로) 또는 "백엔드 미구현 — 더미 연동" 플래그
- 실제 응답 shape (curl 결과 또는 컨트롤러 기준, 어느 쪽인지 명시)
- 추가/변경된 Repository 시그니처
- 실서버 검증 여부

## 완료 체크리스트

- [ ] DTO 필드명이 서버 응답과 정확히 일치 (케이스 포함) — 더미 연동이면 해당 없음 표기
- [ ] `DummyDriverRepository`·`RemoteDriverRepository` 모두 새 인터페이스 메서드 구현
- [ ] 매퍼 단위 테스트 추가 + `:data:testDebugUnitTest` 통과
- [ ] `./gradlew :app:assembleDebug` 통과
- [ ] Repository 시그니처를 compose-builder에게 공유함
