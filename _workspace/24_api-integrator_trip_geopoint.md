# 24 — api-integrator: ActiveTrip 좌표(GeoPoint) 추가 (domain·data)

작업 지시: `_workspace/24_trip_map_input.md` 1번(domain)·2번(data) 항목. presentation(3번)은 compose-builder 몫.
브랜치: feature/trip-map-realdata (23번 GeoPoint 스택 위, 커밋 없음 — 워킹트리 수정만)

## 변경 파일

| 파일 | 변경 |
|------|------|
| `domain/src/main/kotlin/com/moyeota/driver/domain/model/DriverModels.kt` | `ActiveTrip`에 좌표 필드 2개 추가 (기본값 null) |
| `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchMappers.kt` | `toActiveTrip()`에서 DTO 좌표 → GeoPoint 매핑 (23번 `geoPointOrNull` 재사용) |
| `data/src/test/kotlin/com/moyeota/driver/data/remote/DispatchMappersTest.kt` | toActiveTrip 좌표 테스트 3건 추가 (값 매핑·전체 누락 null·반쪽 좌표 null) |

## 추가 필드 시그니처 (compose-builder 경계면)

```kotlin
data class ActiveTrip(
    // ... 기존 필드 변경 없음 ...
    /** 출발지(픽업) 좌표 — null 이면 서버가 좌표 미제공 (D13·D15 지도는 기본 카메라 폴백) */
    val departurePoint: GeoPoint? = null,
    /** 도착지(하차) 좌표 — null 이면 서버가 좌표 미제공 (마커 생략) */
    val destinationPoint: GeoPoint? = null,
)
```

- `GeoPoint(latitude: Double, longitude: Double)` — 23번에서 추가된 기존 타입 재사용 (`domain/model/DriverModels.kt`)
- Repository 메서드 시그니처 변경 없음 — `getActiveTrip(): ActiveTrip?` 등 기존 계약 그대로, 반환 모델에 필드만 추가

## 데이터 소스 / 응답 shape

- 원천: `PartySummaryDto`의 `departureLatitude/departureLongitude`, `destinationLatitude/destinationLongitude` (23번에서 컨트롤러 기준 확정된 필드 — 이번 작업에서 DTO 변경 없음)
- 매핑 규칙: 위·경도 둘 다 있을 때만 GeoPoint, 한쪽이라도 null이면 null (`geoPointOrNull`, 23번 헬퍼 재사용)
- `RemoteDriverRepository`: `acceptCall()`이 `toActiveTrip()` 경유이므로 코드 변경 없이 좌표가 실림
- `DummyDriverRepository`: `ActiveTrip` 생성부(수락 시)는 새 필드 기본값 null로 컴파일 영향 없음 — 확인 완료 (수정 안 함). 더미 운행은 좌표 null → 화면은 MoyeotaDefaultCamera 폴백 (계약 그대로)

## 검증

- `./gradlew :data:testDebugUnitTest` 통과 — DispatchMappersTest 17건 (신규 3건 포함) 전부 성공, 전체 스위트 failures 0
- 실서버 검증: 미수행 (매퍼 단위 변경 — DTO shape 변화 없음, 23번에서 확정된 스펙 그대로)

## 후속 (compose-builder에게)

- `TripCommon.TripMap` 확장 시 `trip.departurePoint` / `trip.destinationPoint` 사용
  - D13 Pickup: departure만 전달 (destination = null)
  - D15 Driving: 둘 다 전달
- 둘 다 null이면 기존 MoyeotaDefaultCamera 폴백 유지 필수 (더미 경로가 이 케이스)
