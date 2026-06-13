// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import kotlin.time.Clock

/**
 * Secure storage interface for Google OAuth 2.0 tokens.
 *
 * Platform implementations:
 * - androidMain: [AndroidGoogleTokenStore] via EncryptedSharedPreferences + Android Keystore
 * - jvmMain: [JvmGoogleTokenStore] via java.util.prefs.Preferences (not production-grade)
 * - iosMain: [IosGoogleTokenStore] via in-memory storage (stub; full Keychain impl deferred)
 *
 * SECURITY: Tokens must NEVER be stored in plaintext. Each platform implementation
 * must use the strongest available secure storage. If secure storage is unavailable,
 * propagate the error to the caller rather than falling back silently.
 */
interface GoogleTokenStore {

    /**
     * Persist the OAuth tokens returned after a successful authorization flow.
     *
     * [expiresAt] is a Unix epoch timestamp in milliseconds indicating when
     * [accessToken] expires and a refresh is required.
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    )

    /**
     * Return the stored access token, or null if no tokens have been saved.
     *
     * Does not validate expiry — callers should call [isTokenExpired] first
     * and refresh via [GoogleTokenRefresher] if the token is stale.
     */
    suspend fun getAccessToken(): String?

    /**
     * Return the stored refresh token, or null if no tokens have been saved.
     */
    suspend fun getRefreshToken(): String?

    /**
     * Return the Unix epoch timestamp (ms) at which the access token expires,
     * or null if no tokens have been saved.
     */
    suspend fun getExpiresAt(): Long?

    /**
     * Remove all stored tokens and email. Called on sign-out.
     */
    suspend fun clearTokens()

    /**
     * Returns true if tokens have been saved and clearTokens() has not been called.
     *
     * Does NOT validate token expiry — use [isTokenExpired] for that.
     */
    suspend fun isAuthenticated(): Boolean

    /** Persist the email address associated with the authenticated Google account. */
    suspend fun saveEmail(email: String)

    /** Return the stored email address, or null if not authenticated or never saved. */
    suspend fun getEmail(): String?
}

/**
 * Returns true if the stored access token has expired (or expires within the next 60 seconds)
 * and a refresh is required before making API calls.
 */
suspend fun GoogleTokenStore.isTokenExpired(): Boolean {
    val expiresAt = getExpiresAt() ?: return true
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val bufferMs = 60_000L // refresh 60s before actual expiry
    return nowMs >= expiresAt - bufferMs
}
