// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Platform-agnostic interface for the Google OAuth 2.0 authorization flow.
 *
 * Platform implementations:
 * - androidMain: [AndroidGoogleAuthManager] — WebView/Custom Tab OAuth flow
 * - jvmMain: [JvmGoogleAuthManager] — browser redirect + local HTTP server callback
 * - iosMain: stub
 *
 * Requested OAuth scopes:
 * - https://www.googleapis.com/auth/drive.file  (create/read files this app creates)
 * - https://photospicker.googleapis.com/v1/      (Google Photos Picker API, post-March-2025)
 */
interface GoogleAuthManager {

    /**
     * Initiate the Google OAuth flow for the current platform.
     *
     * On success, tokens are saved in [GoogleTokenStore] and the user's email is returned.
     * On failure, returns a [DomainError.NetworkError] or [DomainError.SensorError.PermissionDenied].
     */
    suspend fun authenticate(): Either<DomainError, String>

    /**
     * Clear stored tokens and revoke the OAuth session.
     *
     * After this call, [GoogleTokenStore.isAuthenticated] returns false.
     */
    suspend fun signOut()
}
