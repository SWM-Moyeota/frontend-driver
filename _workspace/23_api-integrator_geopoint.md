# 23 — api-integrator: 콜 상세 좌표(GeoPoint) 도메인·매핑 (1·2번 항목)

## 범위
`_workspace/23_call_detail_map_input.md` 리더 확정 계약의 **1(domain)·2(data)** 만 구현.
presentation(3번)은 compose-builder 몫 — 아래 시그니처를 그대로 사용하면 된다.

## 변경 파일
- `domain/src/main/kotlin/com/moyeota/driver/domain/model/DriverModels.kt`
  - `GeoPoint` 추가, `CallDetail`에 좌표 필드 2개 추가 (기본값 null → 기존 생성 코드 컴파일 영향 없음)
- `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchMappers.kt`
  - `toCallDetail()`에서 DTO 좌표 → GeoPoint 매핑, `geoPointOrNull()` 헬퍼 추가
- `data/src/test/kotlin/com/moyeota/driver/data/remote/DispatchMappersTest.kt`
  - 좌표 매핑 테스트 3건 추가

## 도메인 시그니처 (compose-builder 경계면)
```kotlin
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class CallDetail(
    val summary: CallSummary,
    val stops: List<RouteStop>,
    val countdownSeconds: Int,
    val etaToPickupMin: Int,
    val departurePoint: GeoPoint? = null,   // null = 서버 좌표 미제공 → 기본 카메라 폴백
    val destinationPoint: GeoPoint? = null, // null = 서버 좌표 미제공 → 마커 생략
)
```
- `DriverCoordinate`(기사 단말 위치)는 재사용하지 않음 — 장소 좌표는 `GeoPoint` 별도 타입 (리더 계약대로).
- Repository 인터페이스 시그니처 변경 없음 — 기존 `CallDetail` 반환 경로에 필드만 추가됨.

## 매핑 규칙
- 원천: `PartySummaryDto.departureLatitude/Longitude`, `destinationLatitude/Longitude` (DTO에 이미 존재, `DispatchDtos.kt` — 컨트롤러 소스 기준, 실서버 미검증 주석 유지).
- **위·경도가 둘 다 non-null일 때만** `GeoPoint` 생성. 한쪽만 있으면(반쪽 좌표) null — 화면 폴백 유도.
- `toActiveTrip()`(운행 화면)은 이번 범위 아님 — 계약 4번대로 좌표 전달하지 않음.

## 테스트
`./gradlew :data:testDebugUnitTest` — DispatchMappersTest 14/14 통과 (failures 0).
추가 케이스:
1. 출발·도착 좌표 → GeoPoint 값 매핑
2. 좌표 전부 미제공 → 둘 다 null
3. 위/경도 중 한쪽만 존재 → null (반쪽 좌표 방어)

## 상태
- 백엔드 신규 엔드포인트 없음 (기존 응답 필드 활용) — 실서버 미검증 (컨트롤러/DTO 소스 기준)
- 커밋하지 않음 (지시대로 워킹트리 수정만)
