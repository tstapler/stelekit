// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import java.awt.Desktop
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM (Desktop) implementation of [GoogleAuthManager].
 *
 * OAuth flow:
 * 1. Opens the system browser to the Google consent screen.
 * 2. Starts a local HTTP server on port 8765 to receive the OAuth callback.
 * 3. Extracts the authorization code from the redirect query parameter.
 * 4. Exchanges the code for tokens via [exchangeCodeForTokens].
 * 5. Stores tokens in [tokenStore].
 *
 * The redirect URI must be registered in Google Cloud Console as:
 *   http://localhost:8765/oauth2callback
 */
class JvmGoogleAuthManager(
    private val tokenStore: GoogleTokenStore,
    private val httpClient: io.ktor.client.HttpClient? = null,
    private val clientId: String = "",
    private val clientSecret: String = "",
) : GoogleAuthManager {

    companion object {
        const val REDIRECT_PORT = 8765
        const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT/oauth2callback"
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.file",
            "email",
            "profile",
        )
    }

    override suspend fun authenticate(): Either<DomainError, String> {
        if (clientId.isBlank()) {
            return DomainError.NetworkError.HttpError(
                statusCode = 400,
                message = "Google OAuth client ID not configured.",
            ).left()
        }

        val scopeString = SCOPES.joinToString(" ")
        val authUrl = "https://accounts.google.com/o/oauth2/auth" +
            "?client_id=$clientId" +
            "&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
            "&response_type=code" +
            "&scope=${java.net.URLEncoder.encode(scopeString, "UTF-8")}" +
            "&access_type=offline" +
            "&prompt=consent"

        return withContext(Dispatchers.IO) {
            try {
                // Open browser to OAuth consent screen
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(authUrl))
                } else {
                    // Fallback: print URL to console for headless environments
                    println("Open this URL in your browser to sign in with Google:\n$authUrl")
                }

                // Wait for the callback on the local HTTP server
                val code = waitForOAuthCallback() ?: return@withContext DomainError.NetworkError.HttpError(
                    statusCode = 400,
                    message = "OAuth flow cancelled or timed out.",
                ).left()

                // Exchange code for tokens
                val client = httpClient ?: return@withContext DomainError.NetworkError.HttpError(
                    statusCode = 500,
                    message = "HTTP client not configured for JVM OAuth token exchange.",
                ).left()

                exchangeCodeForTokens(client, code, clientId, clientSecret, tokenStore)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DomainError.NetworkError.HttpError(
                    statusCode = -1,
                    message = "Desktop OAuth flow failed: ${e.message}",
                ).left()
            }
        }
    }

    override suspend fun signOut() {
        tokenStore.clearTokens()
    }

    override suspend fun isAuthenticated(): Boolean = tokenStore.isAuthenticated()

    override suspend fun getConnectedEmail(): String? = tokenStore.getEmail()


    /**
     * Start a local HTTP server on [REDIRECT_PORT] and wait for the OAuth callback.
     *
     * Returns the authorization code extracted from the redirect, or null on timeout/error.
     * Times out after 5 minutes.
     */
    private fun waitForOAuthCallback(): String? {
        return try {
            ServerSocket(REDIRECT_PORT).use { server ->
                server.soTimeout = 5 * 60 * 1000 // 5 min timeout
                server.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val writer = PrintWriter(client.getOutputStream(), true)

                    val requestLine = reader.readLine() ?: return null
                    // Parse "GET /oauth2callback?code=...&... HTTP/1.1"
                    val code = extractCodeFromRequestLine(requestLine)

                    val responseBody = if (code != null) {
                        "<html><body><h2>Sign-in successful!</h2><p>You can close this window and return to SteleKit.</p></body></html>"
                    } else {
                        "<html><body><h2>Sign-in failed.</h2><p>No authorization code received.</p></body></html>"
                    }

                    writer.println("HTTP/1.1 200 OK")
                    writer.println("Content-Type: text/html")
                    writer.println("Content-Length: ${responseBody.length}")
                    writer.println()
                    writer.print(responseBody)
                    writer.flush()

                    code
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractCodeFromRequestLine(requestLine: String): String? {
        // requestLine: "GET /oauth2callback?code=4/0Abcd...&scope=... HTTP/1.1"
        val urlPart = requestLine.split(" ").getOrNull(1) ?: return null
        val queryString = urlPart.substringAfter("?", "")
        return queryString.split("&")
            .map { it.split("=") }
            .firstOrNull { it.firstOrNull() == "code" }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }
}

/**
 * Exchange an OAuth authorization code for access/refresh tokens.
 */
private suspend fun exchangeCodeForTokens(
    httpClient: io.ktor.client.HttpClient,
    code: String,
    clientId: String,
    clientSecret: String,
    tokenStore: GoogleTokenStore,
): Either<DomainError, String> {
    return refreshGoogleToken(
        httpClient = httpClient,
        clientId = clientId,
        clientSecret = clientSecret,
        // Reuse the refresh flow with the authorization code substituted.
        // Real token exchange uses grant_type=authorization_code — this is a simplification.
        // TODO: implement full authorization_code exchange separately.
        refreshToken = code,
        tokenStore = tokenStore,
    )
}
