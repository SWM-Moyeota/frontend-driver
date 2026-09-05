package com.moyeota.driver.data.remote

import com.moyeota.driver.data.remote.auth.TokenStore
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
}
