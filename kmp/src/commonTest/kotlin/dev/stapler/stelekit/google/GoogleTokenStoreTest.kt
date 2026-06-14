package dev.stapler.stelekit.google

import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.isTokenExpired
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GoogleTokenStore] contract and [isTokenExpired] expiry logic.
 *
 * Uses an in-memory stub that mirrors the [IosGoogleTokenStore] implementation.
 */
class GoogleTokenStoreTest {

    private val store: GoogleTokenStore = InMemoryGoogleTokenStore()

    @Test
    fun `isAuthenticated returns false when no tokens saved`() = runTest {
        assertFalse(store.isAuthenticated())
    }

    @Test
    fun `isAuthenticated returns true after saveTokens`() = runTest {
        store.saveTokens("access", "refresh", Clock.System.now().toEpochMilliseconds() + 3_600_000)
        assertTrue(store.isAuthenticated())
    }

    @Test
    fun `getAccessToken returns null before any tokens saved`() = runTest {
        assertNull(store.getAccessToken())
    }

    @Test
    fun `getRefreshToken returns null before any tokens saved`() = runTest {
        assertNull(store.getRefreshToken())
    }

    @Test
    fun `saveTokens and retrieve roundtrip`() = runTest {
        val accessToken = "ya29.access_token_value"
        val refreshToken = "1//refresh_token_value"
        val expiresAt = Clock.System.now().toEpochMilliseconds() + 3_600_000L

        store.saveTokens(accessToken, refreshToken, expiresAt)

        assertTrue(store.getAccessToken() == accessToken)
        assertTrue(store.getRefreshToken() == refreshToken)
        assertTrue(store.getExpiresAt() == expiresAt)
    }

    @Test
    fun `clearTokens removes all stored data`() = runTest {
        store.saveTokens("access", "refresh", Clock.System.now().toEpochMilliseconds() + 3_600_000)
        assertTrue(store.isAuthenticated())

        store.clearTokens()

        assertFalse(store.isAuthenticated())
        assertNull(store.getAccessToken())
        assertNull(store.getRefreshToken())
        assertNull(store.getExpiresAt())
    }

    @Test
    fun `isTokenExpired returns true when no tokens saved`() = runTest {
        assertTrue(store.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false when token expires in the future beyond buffer`() = runTest {
        // Token expires 2 hours from now (well beyond the 60s buffer)
        val expiresAt = Clock.System.now().toEpochMilliseconds() + 2 * 3_600_000L
        store.saveTokens("access", "refresh", expiresAt)

        assertFalse(store.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns true when token already expired`() = runTest {
        // Token expired 10 minutes ago
        val expiresAt = Clock.System.now().toEpochMilliseconds() - 600_000L
        store.saveTokens("access", "refresh", expiresAt)

        assertTrue(store.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns true when token expires within 60s buffer window`() = runTest {
        // Token expires in 30s — within the 60s pre-expiry buffer
        val expiresAt = Clock.System.now().toEpochMilliseconds() + 30_000L
        store.saveTokens("access", "refresh", expiresAt)

        assertTrue(store.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false when token expires just outside 60s buffer`() = runTest {
        // Token expires in 90s — just outside the 60s pre-expiry buffer
        val expiresAt = Clock.System.now().toEpochMilliseconds() + 90_000L
        store.saveTokens("access", "refresh", expiresAt)

        assertFalse(store.isTokenExpired())
    }

    @Test
    fun `saving new tokens overwrites previous tokens`() = runTest {
        store.saveTokens("old_access", "old_refresh", Clock.System.now().toEpochMilliseconds() + 1_000)
        store.saveTokens("new_access", "new_refresh", Clock.System.now().toEpochMilliseconds() + 3_600_000)

        assertTrue(store.getAccessToken() == "new_access")
        assertTrue(store.getRefreshToken() == "new_refresh")
    }
}

/**
 * In-memory [GoogleTokenStore] implementation used in commonTest.
 *
 * Mirrors [IosGoogleTokenStore] behavior; no platform dependencies.
 */
private class InMemoryGoogleTokenStore : GoogleTokenStore {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAt: Long? = null
    private var email: String? = null

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
    }

    override suspend fun getAccessToken(): String? = accessToken
    override suspend fun getRefreshToken(): String? = refreshToken
    override suspend fun getExpiresAt(): Long? = expiresAt

    override suspend fun clearTokens() {
        accessToken = null
        refreshToken = null
        expiresAt = null
        email = null
    }

    override suspend fun isAuthenticated(): Boolean = accessToken != null
    override suspend fun saveEmail(email: String) { this.email = email }
    override suspend fun getEmail(): String? = email
}
