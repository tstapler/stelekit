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
    }

    override suspend fun authenticate(): Either<DomainError, String> {
        if (clientId.isBlank()) {
            return DomainError.NetworkError.HttpError(
                statusCode = 400,
                message = "Google OAuth client ID not configured. Set WEB_CLIENT_ID in build config.",
            ).left()
        }

        val scopeString = SCOPES.joinToString(" ")
        val authUrl = "https://accounts.google.com/o/oauth2/auth" +
            "?client_id=$clientId" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${Uri.encode(scopeString)}" +
            "&access_type=offline" +
            "&prompt=consent"

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SteleKitContext.context.startActivity(intent)
            // Browser launched — actual token storage happens via deep-link callback.
            // Return a pending state; real token saving occurs in the deep-link handler.
            DomainError.NetworkError.HttpError(
                statusCode = 202,
                message = "OAuth flow initiated in browser. Complete sign-in to continue.",
            ).left()
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
}
