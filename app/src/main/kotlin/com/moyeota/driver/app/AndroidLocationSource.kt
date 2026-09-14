package com.moyeota.driver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.moyeota.driver.domain.location.DriverCoordinate
import com.moyeota.driver.domain.location.DriverLocationSource

/**
 * 플랫폼 LocationManager 기반 위치 공급자 (play-services-location 미도입 — 의존성 최소화).
 *
 * 영업중 하트비트(POST /dispatch/location)가 15초 주기로 [current] 를 읽으므로,
 * 매번 측위를 새로 하지 않고 **업데이트 구독으로 최신 좌표를 상시 캐시**한다.
 * 캐시가 비어 있으면 각 프로바이더의 마지막 알려진 위치(getLastKnownLocation)로 폴백하되,
 * **[MAX_POSITION_AGE_NANOS] 이내인 것만 채택**한다 — 실기기에서 5일 지난 lastKnown 이
 * 잡히는 걸 확인했고, 그런 좌표로 배차되면 기사는 엉뚱한 지역의 콜을 받는다.
 * (에뮬레이터 `emu geo fix` 로 주입한 좌표는 방금 찍힌 값이라 이 필터를 통과한다.)
 *
 * 권한(ACCESS_FINE/COARSE_LOCATION)이 없으면 조용히 무동작 + [current] 는 null —
 * 호출부(RemoteDriverRepository)는 이때 **좌표를 지어내지 않고** 영업 시작을 실패시키거나
 * 하트비트 주기를 건너뛴다.
 */
class AndroidLocationSource(private val context: Context) : DriverLocationSource {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    /**
     * 실시간 구독 콜백으로 받은 마지막 좌표 — **측위 시각과 함께** 들고 있다.
     * 시각을 같이 두는 이유: 권한이 회수되거나 터널·지하처럼 수신이 끊기면 콜백이 멈추는데,
     * 시각이 없으면 그 순간의 좌표를 영영 "현재 위치"로 보고하게 된다.
     */
    @Volatile
    private var cached: Fix? = null

    private data class Fix(val coordinate: DriverCoordinate, val atNanos: Long)

    @Volatile
    private var listening = false

    private val listener = LocationListener { location ->
        cached = Fix(location.toCoordinate(), location.elapsedRealtimeNanos)
    }

    /**
     * 권한이 있으면 위치 업데이트 구독을 시작한다. 중복 호출·권한 없음은 안전하게 무시된다.
     * [current] 가 백그라운드(하트비트) 스레드에서도 호출하므로 진입을 직렬화한다.
     */
    @SuppressLint("MissingPermission") // hasPermission() 으로 직접 검사한다
    @Synchronized
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

    /**
     * 마지막으로 확인된 좌표. 구독 캐시 → 프로바이더의 **신선한** last known 순으로 찾고, 없으면 null.
     *
     * last known 은 [cached] 에 심지 않는다 — 심어 두면 오래된 값이 영구히 신선한 척하게 된다.
     * 매 호출마다 나이를 다시 따져야 한다.
     */
    @SuppressLint("MissingPermission") // hasPermission() 으로 직접 검사한다
    override fun current(): DriverCoordinate? {
        cached?.takeIf { SystemClock.elapsedRealtimeNanos() - it.atNanos <= MAX_POSITION_AGE_NANOS }
            ?.let { return it.coordinate }
        val manager = locationManager ?: return null
        if (!hasPermission()) return null

        // 홈에서 권한을 갓 허용한 직후에도 곧바로 측위가 시작되도록 여기서도 구독을 연다
        // (MainActivity 경로만으로는 이미 실행 중인 화면에서 허용했을 때 구독이 열리지 않는다).
        start()

        val best: Location? = PROVIDERS
            .filter { manager.allProviders.contains(it) }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.isFreshEnough() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (best == null) Log.w(TAG, "채택 가능한 위치 없음 — 구독 대기 중이거나 last known 이 낡았다")
        return best?.toCoordinate()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * last known 좌표의 나이 판정. 벽시계(`time`)가 아니라 부팅 기준 단조 시계
     * (`elapsedRealtimeNanos`)를 쓴다 — 시각 동기화·시간대 변경에 흔들리지 않는다.
     */
    private fun Location.isFreshEnough(): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return ageNanos in 0..MAX_POSITION_AGE_NANOS
    }

    private fun Location.toCoordinate() = DriverCoordinate(latitude = latitude, longitude = longitude)

    private companion object {
        const val TAG = "MoyeotaDriverLocation"

        /** GPS 우선, 실내·에뮬레이터 대비로 NETWORK 도 함께 구독 */
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** 하트비트 주기(15초)보다 촘촘하게 — 전송 시점에 항상 신선한 좌표가 있게 한다 */
        const val MIN_INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_M = 10f

        /**
         * 보고에 쓸 수 있는 좌표의 최대 나이 — 5분. 실시간 구독 캐시와 getLastKnownLocation 에 같이 적용한다.
         *
         * 서버는 이 좌표로 콜 반경을 판정하고 하트비트 TTL 은 30초다. 그보다 한참 낡은 위치로 배차되면
         * 기사가 이미 떠난 자리로 콜이 간다. 실기기에서 **5일 지난** last known 이 잡히는 걸 확인했고,
         * 권한 회수·터널 진입처럼 콜백이 멈춘 뒤에도 마지막 좌표를 계속 보고하는 문제도 이 한계로 함께 막는다.
         * 이 값을 넘기면 [current] 가 null 을 돌려주고, 호출부는 영업 시작을 실패시키거나 하트비트를 건너뛴다.
         */
        const val MAX_POSITION_AGE_NANOS = 5L * 60 * 1_000_000_000
    }
}
