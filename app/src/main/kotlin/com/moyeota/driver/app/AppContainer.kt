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

    val driverRepository: DriverRepository =
        if (USE_REMOTE) {
            RemoteDriverRepository(
                authApi = NetworkModule.authApi(),
                driverApi = NetworkModule.driverApi(),
                dispatchApi = NetworkModule.dispatchApi(),
                partyMembersApi = NetworkModule.partyMembersApi(),
                tokenStore = NetworkModule.tokenStore,
                fallback = DummyDriverRepository(),
                locationSource = locationSource,
            )
        } else {
            DummyDriverRepository()
        }

    private companion object {
        /** false 면 전체 더미(DummyDriverRepository) 구동 */
        const val USE_REMOTE = true
    }
}
