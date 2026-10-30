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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val authCodeJson = Json { ignoreUnknownKeys = true }

/** Shown when the token response carries no readable email (the account still counts as connected). */
const val FALLBACK_ACCOUNT_LABEL = "Google account"

@Serializable
private data class AuthCodeTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    val error: String? = null,
)

/**
 * Exchanges an OAuth authorization [code] (PKCE [codeVerifier]) for tokens at Google's token
 * endpoint, saves them in [tokenStore] and returns the account email (or [FALLBACK_ACCOUNT_LABEL]).
 *
 * Neither the code, the tokens nor the endpoint's error description appear in the returned error:
 * only the HTTP status and Google's short error code do.
 */
suspend fun exchangeGoogleAuthCode(
    httpClient: HttpClient,
    clientId: String,
    clientSecret: String,
    code: String,
    redirectUri: String,
    codeVerifier: String,
    tokenStore: GoogleTokenStore,
): Either<DomainError, String> {
    return try {
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                if (clientSecret.isNotEmpty()) append("client_secret", clientSecret)
                append("code", code)
                append("code_verifier", codeVerifier)
                append("grant_type", "authorization_code")
                append("redirect_uri", redirectUri)
            },
        )
        parseAuthCodeResponse(response.status.value, response.bodyAsText(), tokenStore)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.NetworkError.HttpError(statusCode = -1, message = "Google sign-in network error: ${e::class.simpleName}").left()
    }
}

/** Pure half of [exchangeGoogleAuthCode]: interprets the token endpoint's reply and stores the tokens. */
suspend fun parseAuthCodeResponse(
    status: Int,
    body: String,
    tokenStore: GoogleTokenStore,
): Either<DomainError, String> {
    val parsed = try {
        authCodeJson.decodeFromString<AuthCodeTokenResponse>(body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return DomainError.NetworkError.HttpError(status, "Google sign-in returned an unreadable response (HTTP $status)").left()
    }
    val accessToken = parsed.accessToken
    if (status !in 200..299 || accessToken == null) {
        val reason = parsed.error?.let { ": $it" }.orEmpty()
        return DomainError.NetworkError.HttpError(status, "Google sign-in failed (HTTP $status$reason)").left()
    }
    val refreshToken = parsed.refreshToken
        ?: return DomainError.NetworkError.HttpError(
            status,
            "Google sign-in returned no refresh token; remove SteleKit from your Google account permissions and retry",
        ).left()
    val expiresAt = Clock.System.now().toEpochMilliseconds() + parsed.expiresInSeconds * 1000L
    tokenStore.saveTokens(accessToken, refreshToken, expiresAt)
    val email = emailFromIdToken(parsed.idToken) ?: FALLBACK_ACCOUNT_LABEL
    tokenStore.saveEmail(email)
    return email.right()
}

/**
 * Reads the `email` claim of an OpenID id_token without verifying its signature: the token came
 * straight from Google's token endpoint over TLS and the value is only displayed.
 */
@OptIn(ExperimentalEncodingApi::class)
fun emailFromIdToken(idToken: String?): String? {
    val payload = idToken?.split('.')?.getOrNull(1) ?: return null
    return try {
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = Base64.UrlSafe.decode(padded).decodeToString()
        authCodeJson.parseToJsonElement(json).jsonObject["email"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }
}
