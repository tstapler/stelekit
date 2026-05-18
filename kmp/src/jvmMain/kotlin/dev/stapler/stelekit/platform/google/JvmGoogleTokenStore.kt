// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

/**
 * JVM (Desktop) implementation of [GoogleTokenStore] using [java.util.prefs.Preferences].
 *
 * NOTE: This is NOT production-grade secure storage. Java Preferences stores data in
 * the OS user profile directory (e.g., ~/.java/.userPrefs or the Windows Registry) without
 * encryption. Tokens stored here can be read by any process running as the same OS user.
 *
 * TODO: Replace with OS keyring integration:
 *   - Linux: libsecret / Secret Service DBus API
 *   - macOS: Keychain Services via JNA or the macOS Keychain Java bridge
 *   - Windows: Windows Credential Manager via JNA
 *
 * This implementation is sufficient for development and local testing only.
 */
class JvmGoogleTokenStore : GoogleTokenStore {

    private val prefs: Preferences = Preferences.userRoot().node(PREFS_NODE)

    override suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ) = withContext(Dispatchers.IO) {
        prefs.put(KEY_ACCESS_TOKEN, accessToken)
        prefs.put(KEY_REFRESH_TOKEN, refreshToken)
        prefs.putLong(KEY_EXPIRES_AT, expiresAt)
        prefs.flush()
    }

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        prefs.get(KEY_ACCESS_TOKEN, null)
    }

    override suspend fun getRefreshToken(): String? = withContext(Dispatchers.IO) {
        prefs.get(KEY_REFRESH_TOKEN, null)
    }

    override suspend fun getExpiresAt(): Long? = withContext(Dispatchers.IO) {
        val value = prefs.getLong(KEY_EXPIRES_AT, Long.MIN_VALUE)
        if (value == Long.MIN_VALUE) null else value
    }

    override suspend fun clearTokens() = withContext(Dispatchers.IO) {
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs.remove(KEY_REFRESH_TOKEN)
        prefs.remove(KEY_EXPIRES_AT)
        prefs.flush()
    }

    override suspend fun isAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        prefs.get(KEY_ACCESS_TOKEN, null) != null
    }

    private companion object {
        private const val PREFS_NODE = "/dev/stapler/stelekit/google"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
