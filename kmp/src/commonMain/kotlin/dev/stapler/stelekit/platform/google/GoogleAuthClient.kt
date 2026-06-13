// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Handles Google OAuth 2.0 token lifecycle for API clients.
 *
 * Provides [getValidToken] which returns a fresh access token, refreshing
 * transparently when the stored token is near expiry.
 *
 * A [Mutex] guards the refresh path so that concurrent callers that both detect
 * an expiring token only perform a single refresh — the second caller re-checks
 * the store inside the lock and returns the token that the first caller wrote.
 *
 * @param tokenStore Storage for OAuth tokens.
 * @param httpClient Ktor client used to call the token endpoint during refresh.
 * @param clientId OAuth client ID used during refresh.
 * @param clientSecret OAuth client secret used during refresh.
 */
class GoogleAuthClient(
    private val tokenStore: GoogleTokenStore,
    private val httpClient: HttpClient,
    private val clientId: String = "",
    private val clientSecret: String = "",
) {

    private val tokenRefreshMutex = Mutex()

    /**
     * Return a valid access token, refreshing if the stored token is expired or near expiry.
     *
     * Returns [DomainError.NetworkError.HttpError] with status 401 if no refresh token is
     * available or the refresh call fails.
     */
    suspend fun getValidToken(): Either<DomainError, String> {
        val storedToken = tokenStore.getAccessToken()
        val expiresAt = tokenStore.getExpiresAt()

        // No tokens at all — not authenticated
        if (storedToken == null) {
            return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated. Connect a Google account first.",
            ).left()
        }

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val bufferMs = 60_000L

        // Token is still fresh — no lock needed
        if (expiresAt != null && expiresAt - nowMs > bufferMs) {
            return storedToken.right()
        }

        // Token is expiring or expired — acquire lock to refresh exactly once
        return tokenRefreshMutex.withLock {
            // Re-read inside lock: another coroutine may have already refreshed
            val freshToken = tokenStore.getAccessToken()
            val freshExpiry = tokenStore.getExpiresAt()
            val nowMs2 = Clock.System.now().toEpochMilliseconds()

            if (freshToken != null && freshExpiry != null && freshExpiry - nowMs2 > bufferMs) {
                return@withLock freshToken.right()
            }

            // Actually need to refresh
            val refreshToken = tokenStore.getRefreshToken()
                ?: return@withLock DomainError.NetworkError.HttpError(
                    statusCode = 401,
                    message = "Not authenticated. Connect a Google account first.",
                ).left()

            refreshGoogleToken(httpClient, clientId, clientSecret, refreshToken, tokenStore)
        }
    }
}
