// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [GoogleTokenStore] using [EncryptedSharedPreferences]
 * backed by Android Keystore (AES256-GCM keys).
 *
 * SECURITY: If [EncryptedSharedPreferences] fails to initialize (corrupted Keystore,
 * missing hardware), this implementation throws rather than falling back to plain
 * SharedPreferences — per the ADR-003 security requirement.
 */
class AndroidGoogleTokenStore : GoogleTokenStore {

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(SteleKitContext.context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                SteleKitContext.context,
                "stelekit_google_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do NOT fall back to plain SharedPreferences for tokens — fail loudly.
            Log.e(TAG, "EncryptedSharedPreferences initialization failed. Google tokens cannot be stored securely.", e)
            throw IllegalStateException(
                "Android Keystore unavailable. Cannot store OAuth tokens securely. " +
                    "Google account features require a device with hardware-backed Keystore.",
                e,
            )
        }
    }

    override suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    override suspend fun getRefreshToken(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    override suspend fun getExpiresAt(): Long? = withContext(Dispatchers.IO) {
        if (!prefs.contains(KEY_EXPIRES_AT)) null
        else prefs.getLong(KEY_EXPIRES_AT, 0L)
    }

    override suspend fun clearTokens() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    override suspend fun isAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_ACCESS_TOKEN) && prefs.getString(KEY_ACCESS_TOKEN, null) != null
    }

    private companion object {
        private const val TAG = "AndroidGoogleTokenStore"
        private const val KEY_ACCESS_TOKEN = "google_access_token"
        private const val KEY_REFRESH_TOKEN = "google_refresh_token"
        private const val KEY_EXPIRES_AT = "google_expires_at"
    }
}
