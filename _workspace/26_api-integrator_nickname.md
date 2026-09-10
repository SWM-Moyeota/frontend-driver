# 26. api-integrator — 배차 승객 실닉네임 연동 (data 파트)

작업 지시: `_workspace/26_passenger_nickname_input.md` 1번(data) 항목. presentation(D11 화면)은 리더 담당.

## 연동한 엔드포인트

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/v1/matching/rooms/{partyId}` | 콜 수락 직후 승객 실닉네임 조회 (베스트에포트) |

- 스펙 원천: `../backend/.../matching/presentation/PartyController.detail` + `matching/application/dto/PartyDetailResult.java` (컨트롤러 기준) **+ 실측 응답**(기사 토큰으로 조회 가능 확인됨 — 리더 제공 실측).
- 실측 응답 shape(발췌): `{"id":32, ..., "members":[{"publicId":"...","nickname":"승객검D417","imageUrl":null,"rideCount":1,...}], "route":"(대형 폴리라인)"}`
- DTO 는 **members[].nickname 만** 파싱 — route(대형 문자열)·좌표·기타 필드는 ignoreUnknownKeys 로 건너뜀. `nickname` 은 서버에서 null 가능(탈퇴 회원 방어 — `PartyDetailResult.toMemberInfo`).

## 새 API / DTO 시그니처

```kotlin
// data/remote/PartyMembersApi.kt — 인증(Bearer) Retrofit 클라이언트로 생성
interface PartyMembersApi {
    @GET("api/v1/matching/rooms/{partyId}")
    suspend fun getPartyMembers(@Path("partyId") partyId: Long): PartyDetailMembersDto
}

// data/remote/dto/PartyMemberDtos.kt
@Serializable data class PartyDetailMembersDto(val members: List<PartyMemberDto> = emptyList())
@Serializable data class PartyMemberDto(val nickname: String? = null)

// data/remote/DispatchMappers.kt — 닉네임 주입 매퍼
fun ActiveTrip.withPassengerNicknames(nicknames: List<String?>): ActiveTrip
```

## Repository 시그니처 변경

- **DriverRepository(domain) 인터페이스 변경 없음** — 계약 그대로 (maskedName 필드 재사용, compose 쪽 코드 수정 불필요).
- `RemoteDriverRepository` 생성자에 옵션 파라미터 추가 (기본 null — 미주입이면 기존 "승객N" 동작):
  ```kotlin
  RemoteDriverRepository(..., partyMembersApi: PartyMembersApi? = null)
  ```
- `AppContainer` 에 `partyMembersApi = NetworkModule.partyMembersApi()` 배선 완료 (`NetworkModule.partyMembersApi()` 신설, 기존 인증 Retrofit 인스턴스 재사용).

## 동작 규칙

- 주입 지점: `RemoteDriverRepository.acceptCall` — accept 성공 후 `toActiveTrip(...)` 에 `withPassengerNicknames(fetchPassengerNicknamesQuietly(partyId))` 체인. `getActiveTrip`/`startRide` 는 remoteTrip 캐시 기반이라 닉네임이 운행 내내 유지된다.
- **베스트에포트**: 닉네임 조회 실패(404·네트워크·미배선)는 조용히 빈 목록 폴백 → "승객N" 유지. 콜 수락 흐름을 절대 막지 않는다 (CancellationException 만 전파).
- **passengers·stops 동시 교체**: D15 하차 매칭이 `RouteStop.passengerMaskedName` 문자열 대응이므로 순서 매칭(passengers[i] ↔ members[i])으로 양쪽을 같은 이름으로 바꾼다.
- 방어: null·공백·부족한 목록은 해당 승객만 "승객N" 유지, **중복 닉네임은 뒤 승객이 "승객N" 유지**(이름 겹침 → 엉뚱한 승객 하차 처리 방지).

## 변경 파일

- 신규: `data/src/main/kotlin/com/moyeota/driver/data/remote/PartyMembersApi.kt`
- 신규: `data/src/main/kotlin/com/moyeota/driver/data/remote/dto/PartyMemberDtos.kt`
- 수정: `data/src/main/kotlin/com/moyeota/driver/data/remote/DispatchMappers.kt` (`withPassengerNicknames` 추가)
- 수정: `data/src/main/kotlin/com/moyeota/driver/data/remote/NetworkModule.kt` (`partyMembersApi()` 추가)
- 수정: `data/src/main/kotlin/com/moyeota/driver/data/repository/RemoteDriverRepository.kt` (생성자 파라미터 + acceptCall 주입 + `fetchPassengerNicknamesQuietly`)
- 수정: `app/src/main/kotlin/com/moyeota/driver/app/AppContainer.kt` (배선)
- 테스트 신규: `data/src/test/kotlin/com/moyeota/driver/data/remote/PartyMemberDtosTest.kt` (3)
- 테스트 수정: `data/src/test/.../DispatchMappersTest.kt` (+4: 주입·부분/공백·빈 목록·중복), `data/src/test/.../RemoteDriverRepositoryCallTest.kt` (+3: 주입 성공·조회 실패 폴백·미배선)

## 검증

- `./gradlew :data:testDebugUnitTest` **통과** — DispatchMappersTest 21, PartyMemberDtosTest 3, RemoteDriverRepositoryCallTest 16 등 전 스위트 0 실패.
- `./gradlew :app:compileDebugKotlin` 통과 (AppContainer 배선 확인용 — 전체 assembleDebug 는 리더 검증).
- 실서버 검증: 엔드포인트 자체는 실측 확인됨(리더 제공). 수락 플로우 통합 실기 검증은 리더 에뮬레이터 검증 항목.
