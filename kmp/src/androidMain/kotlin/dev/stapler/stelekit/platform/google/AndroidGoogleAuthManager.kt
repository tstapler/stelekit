// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.content.Intent
import android.net.Uri
import android.util.Base64
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.SteleKitContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
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
 * The authorization code is exchanged for tokens at Google's token endpoint with PKCE
 * ([exchangeGoogleAuthCode]); the real sign-in has not been exercised against a Google account yet
 * (plan item 12.5, owner device pass), and needs [clientId] to be set in the app build.
 */
class AndroidGoogleAuthManager(
    private val tokenStore: GoogleTokenStore,
    private val httpClient: HttpClient,
    private val clientId: String = "",
    private val clientSecret: String = "",
) : GoogleAuthManager {

    companion object {
        val SCOPES = listOf(
            "openid",
            "https://www.googleapis.com/auth/drive.file",
            "email",
            "profile",
        )

        /** RFC 7636 S256 code challenge for [verifier]. */
        fun pkceChallenge(verifier: String): String =
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )

        private fun newCodeVerifier(): String {
            val bytes = ByteArray(PKCE_VERIFIER_BYTES).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private const val PKCE_VERIFIER_BYTES = 48
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
        val codeVerifier = newCodeVerifier()
        val authUrl = "https://accounts.google.com/o/oauth2/auth" +
            "?client_id=$clientId" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${Uri.encode(scopeString)}" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&code_challenge=${pkceChallenge(codeVerifier)}" +
            "&code_challenge_method=S256" +
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

            exchangeGoogleAuthCode(httpClient, clientId, clientSecret, code, REDIRECT_URI, codeVerifier, tokenStore)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Failed to launch OAuth browser: ${e.message}",
            ).left()
        }
    }


    override suspend fun signOut() {
        tokenStore.clearTokens()
    }

    override suspend fun isAuthenticated(): Boolean = tokenStore.isAuthenticated()

    override suspend fun getConnectedEmail(): String? = tokenStore.getEmail()
}
