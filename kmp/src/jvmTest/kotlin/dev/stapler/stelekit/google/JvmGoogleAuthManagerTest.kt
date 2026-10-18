// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.JvmGoogleAuthManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Only the paths that return before the browser/ServerSocket step are tested;
 * `waitForOAuthCallback` needs a live socket on port 8765 and stays uncovered.
 */
class JvmGoogleAuthManagerTest {

    private class FakeStore(var authed: Boolean = false, var email: String? = null) : GoogleTokenStore {
        var cleared = 0
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getExpiresAt(): Long? = null
        override suspend fun clearTokens() { cleared++; authed = false; email = null }
        override suspend fun isAuthenticated() = authed
        override suspend fun saveEmail(email: String) { this.email = email }
        override suspend fun getEmail() = email
    }

    @Test
    fun `authenticate with blank clientId returns Left 400 immediately`() = runTest {
        val result = JvmGoogleAuthManager(FakeStore()).authenticate()
        val err = assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value)
        assertEquals(400, err.statusCode)
        assertTrue("client ID" in err.message)
    }

    @Test
    fun `authenticate with whitespace clientId is treated as blank`() = runTest {
        val result = JvmGoogleAuthManager(FakeStore(), clientId = "   ").authenticate()
        assertTrue(result is Either.Left)
    }

    @Test
    fun `signOut clears the token store`() = runTest {
        val store = FakeStore(authed = true, email = "a@b.c")
        JvmGoogleAuthManager(store).signOut()
        assertEquals(1, store.cleared)
    }

    @Test
    fun `isAuthenticated delegates to the token store`() = runTest {
        val store = FakeStore(authed = true)
        val manager = JvmGoogleAuthManager(store)
        assertTrue(manager.isAuthenticated())
        store.authed = false
        assertFalse(manager.isAuthenticated())
    }

    @Test
    fun `getConnectedEmail delegates to the token store`() = runTest {
        val store = FakeStore()
        val manager = JvmGoogleAuthManager(store)
        assertNull(manager.getConnectedEmail())
        store.email = "me@example.com"
        assertEquals("me@example.com", manager.getConnectedEmail())
    }

    @Test
    fun `redirect constants are consistent`() {
        assertEquals("http://localhost:${JvmGoogleAuthManager.REDIRECT_PORT}/oauth2callback", JvmGoogleAuthManager.REDIRECT_URI)
        assertTrue(JvmGoogleAuthManager.SCOPES.any { it.endsWith("drive.file") })
    }
}
