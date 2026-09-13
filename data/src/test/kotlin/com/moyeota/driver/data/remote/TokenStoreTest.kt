package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.auth.TokenStore
import com.moyeota.driver.data.session.DriverSessionStorage
import com.moyeota.driver.data.session.SessionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    @Test
    fun `초기 상태는 미로그인이다`() {
        val store = TokenStore()
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertFalse(store.isLoggedIn())
    }

    @Test
    fun `update 는 토큰 쌍을 함께 교체한다`() {
        val store = TokenStore()
        store.update("access-1", "refresh-1")
        assertEquals("access-1", store.accessToken())
        assertEquals("refresh-1", store.refreshToken())
        assertTrue(store.isLoggedIn())

        // 재발급(회전) — 쌍이 함께 바뀐다
        store.update("access-2", "refresh-2")
        assertEquals("access-2", store.accessToken())
        assertEquals("refresh-2", store.refreshToken())
    }

    @Test
    fun `clear 는 토큰을 모두 비운다`() {
        val store = TokenStore()
        store.update("access-1", "refresh-1")
        store.clear()
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertFalse(store.isLoggedIn())
    }

    // ── 영속화 (앱 재실행 세션 유지) ──────────────────────────────────

    @Test
    fun `저장소를 붙이면 저장된 토큰으로 곧바로 로그인 상태가 된다`() {
        // 앱 재실행 시나리오 — 새 프로세스의 TokenStore 는 비어 있지만 단말에는 토큰이 남아 있다
        val storage = RecordingSessionStorage(tokens = SessionTokens("saved-access", "saved-refresh"))

        val store = TokenStore().apply { attachPersistence(storage) }

        assertTrue(store.isLoggedIn())
        assertEquals("saved-access", store.accessToken())
        assertEquals("saved-refresh", store.refreshToken())
    }

    @Test
    fun `저장된 토큰이 없으면 미로그인 그대로다`() {
        val store = TokenStore().apply { attachPersistence(RecordingSessionStorage()) }

        assertFalse(store.isLoggedIn())
    }

    @Test
    fun `붙인 뒤 update·clear 는 저장소까지 반영된다`() {
        val storage = RecordingSessionStorage()
        val store = TokenStore().apply { attachPersistence(storage) }

        // 로그인 · 재발급(회전)
        store.update("access-1", "refresh-1")
        assertEquals(SessionTokens("access-1", "refresh-1"), storage.tokens)
        store.update("access-2", "refresh-2")
        assertEquals(SessionTokens("access-2", "refresh-2"), storage.tokens)

        // 로그아웃 · 재발급 실패
        store.clear()
        assertNull("세션 만료 뒤에도 토큰이 남으면 다음 실행이 401 로 시작한다", storage.tokens)
    }

    @Test
    fun `이미 로그인된 상태에서 붙이면 메모리 값이 저장소를 덮는다`() {
        // 같은 프로세스에서 로그인이 먼저 일어난 경우 — 메모리 쪽이 최신이다
        val storage = RecordingSessionStorage(tokens = SessionTokens("stale-access", "stale-refresh"))
        val store = TokenStore().apply { update("fresh-access", "fresh-refresh") }

        store.attachPersistence(storage)

        assertEquals("fresh-access", store.accessToken())
        assertEquals(SessionTokens("fresh-access", "fresh-refresh"), storage.tokens)
    }
}

/** 인메모리 세션 저장소 — 토큰 파트만 쓰는 테스트용 */
private class RecordingSessionStorage(var tokens: SessionTokens? = null) : DriverSessionStorage {
    override fun readTokens(): SessionTokens? = tokens
    override fun writeTokens(accessToken: String, refreshToken: String) {
        tokens = SessionTokens(accessToken, refreshToken)
    }

    override fun clearTokens() {
        tokens = null
    }

    override fun readActivePartyId(): Long? = null
    override fun writeActivePartyId(partyId: Long) = Unit
    override fun clearActivePartyId() = Unit
}
