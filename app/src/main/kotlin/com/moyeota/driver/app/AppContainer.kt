package com.moyeota.driver.app

import android.content.Context
import com.moyeota.driver.data.remote.NetworkModule
import com.moyeota.driver.data.repository.DummyDriverRepository
import com.moyeota.driver.data.repository.RemoteDriverRepository
import com.moyeota.driver.domain.repository.DriverRepository

/**
 * 수동 DI 컨테이너 (승객 앱과 동일한 방식 — Hilt 미도입).
 * RemoteDriverRepository 는 백엔드에 있는 API 만 실연동하고 나머지는 내장 더미로 위임한다.
 * 더미 전용 구동이 필요하면 [USE_REMOTE] 를 false 로 바꾼다.
 */
class AppContainer(context: Context) {

    /** 영업중 위치 하트비트에 쓰는 단말 실측 위치 (권한 허용 후 [AndroidLocationSource.start] 로 구독 시작) */
    val locationSource: AndroidLocationSource = AndroidLocationSource(context.applicationContext)

    /**
     * 프로세스가 죽어도 남는 세션 저장소 — 토큰 쌍과 활성 partyId.
     * 첫 네트워크 호출보다 먼저(컨테이너 생성 시점) TokenStore.attachPersistence 로 붙여야
     * 재실행 직후의 요청이 저장된 토큰을 싣는다.
     */
    private val sessionStorage: AndroidDriverSessionStorage =
        AndroidDriverSessionStorage(context.applicationContext)

    val driverRepository: DriverRepository =
        if (USE_REMOTE) {
            NetworkModule.tokenStore.attachPersistence(sessionStorage)
            RemoteDriverRepository(
                authApi = NetworkModule.authApi(),
                driverApi = NetworkModule.driverApi(),
                dispatchApi = NetworkModule.dispatchApi(),
                partyMembersApi = NetworkModule.partyMembersApi(),
                tokenStore = NetworkModule.tokenStore,
                fallback = DummyDriverRepository(),
                locationSource = locationSource,
                sessionStorage = sessionStorage,
            )
        } else {
            DummyDriverRepository()
        }

    private companion object {
        /** false 면 전체 더미(DummyDriverRepository) 구동 */
        const val USE_REMOTE = true
    }
}
