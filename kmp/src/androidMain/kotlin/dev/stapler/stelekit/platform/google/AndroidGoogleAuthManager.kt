// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.content.Intent
import android.net.Uri
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Android implementation of [GoogleAuthManager].
 *
 * Uses a WebView/Custom Tab OAuth 2.0 flow targeting:
 *   https://accounts.google.com/o/oauth2/auth
 *
 * Credential Manager (play-services-auth) is NOT yet available in the project dependencies.
 * This stub opens the OAuth consent screen in the system browser (Custom Tab or fallback).
 * The redirect URI must be registered in the Google Cloud Console as:
 *   com.stelekit.app:/oauth2redirect
 *
 * TODO (Story 7.2): When `credentials` + `credentials-play-services-auth` deps are added
 *   to build.gradle.kts, replace this with GetGoogleIdOption Credential Manager flow.
 *
 * NOTE: Token exchange (auth code → access/refresh tokens) requires a server-side endpoint
 * or a native app client secret. For now this implementation is a structural stub that
 * opens the browser. The token exchange is wired once a client_id is configured.
 */
class AndroidGoogleAuthManager(
    private val tokenStore: GoogleTokenStore,
    private val clientId: String = "",
) : GoogleAuthManager {

    companion object {
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.file",
            "email",
            "profile",
        )
        const val REDIRECT_URI = "com.stelekit.app:/oauth2redirect"

        /**
         * SharedFlow bridge: MainActivity.onNewIntent() emits (state, code) pairs here.
         * authenticate() suspends on this flow filtering for its own state nonce, so stale
         * codes from a prior abandoned auth attempt in the buffer are discarded automatically.
         *
         * replay=0 + extraBufferCapacity=1 + DROP_OLDEST: the buffer holds at most one pair;
         * a new emission displaces any stale buffered pair before the next first{} call sees it.
         */
        val oauthCodeFlow = MutableSharedFlow<Pair<String, String>>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /**
         * CSRF protection: the state nonce generated in [authenticate] and validated by
         * [MainActivity.onNewIntent] before emitting to [oauthCodeFlow].
         * Volatile ensures cross-thread visibility between the auth coroutine and the main thread.
         */
        @Volatile var pendingOAuthState: String? = null
    }

    override suspend fun authenticate(): Either<DomainError, String> {
        if (clientId.isBlank()) {
            return DomainError.NetworkError.HttpError(
                statusCode = 400,
                message = "Google OAuth client ID not configured. Set WEB_CLIENT_ID in build config.",
            ).left()
        }

        val scopeString = SCOPES.joinToString(" ")
        val state = UUID.randomUUID().toString()
        pendingOAuthState = state
        val authUrl = "https://accounts.google.com/o/oauth2/auth" +
            "?client_id=$clientId" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${Uri.encode(scopeString)}" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&state=${Uri.encode(state)}"

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SteleKitContext.context.startActivity(intent)

            // Suspend until MainActivity.onNewIntent delivers the matching (state, code) pair.
            // Filtering by state ensures stale codes from abandoned auth sessions are discarded.
            // Timeout after 5 minutes to prevent hanging indefinitely if the user abandons.
            val code = try {
                withTimeout(5 * 60 * 1000L) {
                    oauthCodeFlow.first { (emittedState, _) -> emittedState == state }.second
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                pendingOAuthState = null
                return DomainError.NetworkError.HttpError(
                    statusCode = 408,
                    message = "OAuth timed out — no code received within 5 minutes.",
                ).left()
            }

            // Exchange the authorization code for tokens
            exchangeCodeForTokens(code)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Failed to launch OAuth browser: ${e.message}",
            ).left()
        }
    }

    /**
     * Exchange the OAuth authorization code for access/refresh tokens.
     * TODO (Story 2.1.4): POST to https://oauth2.googleapis.com/token with:
     *   grant_type=authorization_code, code=$code, redirect_uri, client_id, client_secret
     * Store access_token, refresh_token, expires_in, email in tokenStore.
     * Until Story 2.1.4 lands, returns Left — the auth code alone is not an access token
     * and must not be stored as one.
     */
    private fun exchangeCodeForTokens(code: String): Either<DomainError, String> {
        return DomainError.NetworkError.HttpError(
            statusCode = 501,
            message = "Google token exchange is not yet implemented (Story 2.1.4). " +
                "Received auth code length=${code.length}.",
        ).left()
    }

    override suspend fun signOut() {
        tokenStore.clearTokens()
    }

    override suspend fun isAuthenticated(): Boolean = tokenStore.isAuthenticated()

    override suspend fun getConnectedEmail(): String? = tokenStore.getEmail()
}
