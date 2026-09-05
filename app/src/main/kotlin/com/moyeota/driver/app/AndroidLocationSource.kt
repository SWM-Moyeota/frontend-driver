package com.moyeota.driver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.moyeota.driver.domain.location.DriverCoordinate
import com.moyeota.driver.domain.location.DriverLocationSource

/**
 * 플랫폼 LocationManager 기반 위치 공급자 (play-services-location 미도입 — 의존성 최소화).
 *
 * 영업중 하트비트(POST /dispatch/location)가 15초 주기로 [current] 를 읽으므로,
 * 매번 측위를 새로 하지 않고 **업데이트 구독으로 최신 좌표를 상시 캐시**한다.
 * 캐시가 비어 있으면 각 프로바이더의 마지막 알려진 위치(getLastKnownLocation)로 폴백한다 —
 * 에뮬레이터 `emu geo fix` 로 주입한 좌표도 이 경로로 잡힌다.
 *
 * 권한(ACCESS_FINE/COARSE_LOCATION)이 없으면 조용히 무동작 + [current] 는 null —
 * 호출부(RemoteDriverRepository)가 직전/기본 좌표로 폴백하므로 하트비트가 끊기지는 않는다.
 */
class AndroidLocationSource(private val context: Context) : DriverLocationSource {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    @Volatile
    private var cached: DriverCoordinate? = null

    private var listening = false

    private val listener = LocationListener { location -> cached = location.toCoordinate() }

    /** 권한이 있으면 위치 업데이트 구독을 시작한다. 중복 호출·권한 없음은 안전하게 무시된다 */
    @SuppressLint("MissingPermission") // hasPermission() 으로 직접 검사한다
    fun start() {
        val manager = locationManager ?: return
        if (listening || !hasPermission()) return
        listening = true

        // GPS·NETWORK 를 함께 구독한다 — 실내·에뮬레이터에서 한쪽만으로는 좌표가 오지 않을 수 있다.
        PROVIDERS.forEach { provider ->
            if (!manager.allProviders.contains(provider)) return@forEach
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { Log.w(TAG, "위치 업데이트 구독 실패: $provider", it) }
        }
    }

    /** 마지막으로 확인된 좌표. 구독 캐시 → 프로바이더의 last known 순으로 찾고, 없으면 null */
    @SuppressLint("MissingPermission") // hasPermission() 으로 직접 검사한다
    override fun current(): DriverCoordinate? {
        cached?.let { return it }
        val manager = locationManager ?: return null
        if (!hasPermission()) return null

        // 가장 최근(elapsed time 기준) last known 위치를 고른다
        val best: Location? = PROVIDERS
            .filter { manager.allProviders.contains(it) }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return best?.toCoordinate()?.also { cached = it }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toCoordinate() = DriverCoordinate(latitude = latitude, longitude = longitude)

    private companion object {
        const val TAG = "MoyeotaDriverLocation"

        /** GPS 우선, 실내·에뮬레이터 대비로 NETWORK 도 함께 구독 */
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** 하트비트 주기(15초)보다 촘촘하게 — 전송 시점에 항상 신선한 좌표가 있게 한다 */
        const val MIN_INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_M = 10f
    }
}
