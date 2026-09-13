package com.moyeota.driver.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.moyeota.driver.data.session.DriverSessionStorage
import com.moyeota.driver.data.session.SessionTokens

/**
 * SharedPreferences 기반 세션 저장소 — 토큰 쌍과 활성 partyId 를 앱 프로세스 종료 후에도 유지한다.
 *
 * ⚠️ **평문 저장(MVP)**: 토큰을 암호화하지 않는다.
 * - 사유: 파일이 앱 전용 디렉터리(`/data/data/com.moyeota.driver/shared_prefs`, MODE_PRIVATE)에 있어
 *   루팅되지 않은 단말에서는 다른 앱이 읽을 수 없고, MVP 일정에 암호화 의존성(androidx.security-crypto)
 *   도입과 키 회전 설계까지 넣기 어렵다.
 * - 후속 과제: `EncryptedSharedPreferences`(또는 Keystore 기반 래핑)로 교체 + 루팅 단말 대응.
 *   교체해도 이 클래스 밖(=[DriverSessionStorage] 계약)은 바뀌지 않는다.
 *
 * 쓰기는 [SharedPreferences.Editor.apply] (비동기 커밋) 로만 한다 — OkHttp Authenticator 가 토큰 재발급
 * 직후 네트워크 스레드에서 호출하므로 디스크 I/O 로 응답 처리를 막으면 안 된다.
 */
class AndroidDriverSessionStorage(context: Context) : DriverSessionStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 한쪽만 남은 반쪽 세션은 재발급도 못 하므로 없는 것으로 본다 */
    override fun readTokens(): SessionTokens? {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return SessionTokens(accessToken = access, refreshToken = refresh)
    }

    override fun writeTokens(accessToken: String, refreshToken: String) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    override fun clearTokens() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
        }
    }

    override fun readActivePartyId(): Long? =
        prefs.getLong(KEY_ACTIVE_PARTY_ID, NO_PARTY).takeIf { it != NO_PARTY }

    override fun writeActivePartyId(partyId: Long) {
        prefs.edit { putLong(KEY_ACTIVE_PARTY_ID, partyId) }
    }

    override fun clearActivePartyId() {
        prefs.edit { remove(KEY_ACTIVE_PARTY_ID) }
    }

    private companion object {
        const val FILE_NAME = "moyeota_driver_session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACTIVE_PARTY_ID = "active_party_id"

        /** partyId 는 서버 auto-increment 라 0 이 될 수 없다 — "없음" 센티널로 쓴다 */
        const val NO_PARTY = 0L
    }
}
