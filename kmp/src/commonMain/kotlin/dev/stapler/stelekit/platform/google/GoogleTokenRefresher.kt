// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val tokenRefreshJson = Json { ignoreUnknownKeys = true }

/**
 * Response shape from the Google OAuth 2.0 token endpoint.
 */
@Serializable
private data class TokenRefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Int,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * Exchange a [refreshToken] for a new access token via the Google OAuth 2.0 token endpoint.
 *
 * This is a standalone suspend function — it is called by [GoogleApiClient] when
 * [GoogleTokenStore.isTokenExpired] returns true before each API call.
 *
 * On success, updates the [tokenStore] with the new token values.
 * On failure, returns a [DomainError.NetworkError.HttpError] with the OAuth error.
 *
 * @param httpClient A bare Ktor [HttpClient] without any auth plugins attached.
 * @param clientId Google OAuth 2.0 client ID.
 * @param clientSecret Google OAuth 2.0 client secret.
 * @param refreshToken The stored refresh token.
 * @param tokenStore Store to update with the new tokens on success.
 */
suspend fun refreshGoogleToken(
    httpClient: HttpClient,
    clientId: String,
    clientSecret: String,
    refreshToken: String,
    tokenStore: GoogleTokenStore,
): Either<DomainError, String> {
    return try {
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            },
        )

        if (!response.status.isSuccess()) {
            return DomainError.NetworkError.HttpError(
                statusCode = response.status.value,
                message = "Token refresh failed: HTTP ${response.status.value}",
            ).left()
        }

        val body = response.bodyAsText()
        val parsed = tokenRefreshJson.decodeFromString<TokenRefreshResponse>(body)

        if (parsed.error != null) {
            return DomainError.NetworkError.HttpError(
                statusCode = 400,
                message = "Token refresh error: ${parsed.error} — ${parsed.errorDescription}",
            ).left()
        }

        val expiresAt = System.currentTimeMillis() + (parsed.expiresInSeconds * 1000L)
        // Google may rotate the refresh token; if a new one is returned, store it.
        val newRefreshToken = parsed.refreshToken ?: refreshToken
        tokenStore.saveTokens(parsed.accessToken, newRefreshToken, expiresAt)

        parsed.accessToken.right()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        DomainError.NetworkError.HttpError(
            statusCode = -1,
            message = "Token refresh network error: ${e.message}",
        ).left()
    }
}
