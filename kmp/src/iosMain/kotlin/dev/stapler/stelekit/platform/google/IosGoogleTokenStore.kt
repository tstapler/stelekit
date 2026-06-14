// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

/**
 * iOS stub implementation of [GoogleTokenStore] using in-memory storage.
 *
 * NOTE: This is a stub only. Tokens are NOT persisted across app restarts.
 *
 * TODO: Replace with Keychain Services implementation using SecItemAdd/SecItemCopyMatching
 * for production-grade secure storage on iOS.
 */
class IosGoogleTokenStore : GoogleTokenStore {

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAt: Long? = null
    private var email: String? = null

    override suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ) {
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
