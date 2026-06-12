// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.DeviceCodeResponse
import dev.stapler.stelekit.git.model.DeviceFlowPollState
import dev.stapler.stelekit.git.model.GitHubUser
import dev.stapler.stelekit.git.model.TokenPollResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * GitHub Device Flow OAuth client.
 *
 * This is a stateless service: it has no internal CoroutineScope and no mutable state.
 * Callers launch coroutines in their own scope (e.g. rememberCoroutineScope in the composable).
 *
 * @param httpClient Ktor HTTP client with ContentNegotiation (JSON) installed.
 *                   Pass null in tests that don't need real HTTP calls.
 * @param clientId GitHub OAuth App client ID (defaults to the GitHub CLI's public ID).
 */
class GitHubDeviceFlowClient(
    private val httpClient: HttpClient?,
    private val clientId: String = GITHUB_CLIENT_ID,
) {
    companion object {
        const val GITHUB_CLIENT_ID = "178c6fc778ccc68e1d6a"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_API_URL = "https://api.github.com/user"
        const val SCOPE = "repo"

        fun withDefaultClient(): GitHubDeviceFlowClient {
            val client = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            return GitHubDeviceFlowClient(client)
        }
    }

    /**
     * Step 1 of the device flow: request a device code from GitHub.
     * Returns the device code response containing the user_code to display.
     */
    suspend fun requestDeviceCode(): Either<DomainError.GitError, DeviceCodeResponse> {
        val client = httpClient
            ?: return DomainError.GitError.AuthFailed("HTTP client not available").left()
        return try {
            val response = client.post(DEVICE_CODE_URL) {
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_id=$clientId&scope=$SCOPE")
            }
            if (response.status.value !in 200..299) {
                DomainError.GitError.AuthFailed("GitHub returned ${response.status.value}").left()
            } else {
                response.body<DeviceCodeResponse>().right()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.GitError.AuthFailed(e.message ?: "Failed to request device code").left()
        }
    }

    /**
     * Step 2 of the device flow: poll GitHub for the access token.
     * Respects slow_down responses by incrementing interval cumulatively.
     * Network errors and 5xx responses are retried with back-off, not treated as fatal.
     *
     * @param deviceCode The device_code from [requestDeviceCode].
     * @param expiresIn Seconds until the device code expires.
     * @param initialInterval Polling interval in seconds from the device code response.
     * @param onStateChange Called with intermediate poll states for UI feedback.
     * @return The access token string on success, or a [DomainError.GitError] on terminal failure.
     */
    suspend fun pollForToken(
        deviceCode: String,
        expiresIn: Int,
        initialInterval: Int,
        onStateChange: (DeviceFlowPollState) -> Unit,
    ): Either<DomainError.GitError, String> {
        val client = httpClient
            ?: return DomainError.GitError.AuthFailed("HTTP client not available").left()

        var intervalMs = initialInterval * 1000L
        val deadline = Clock.System.now().toEpochMilliseconds() + expiresIn * 1000L

        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            delay(intervalMs)

            try {
                val response = client.post(TOKEN_URL) {
                    header(HttpHeaders.Accept, "application/json")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        "client_id=$clientId" +
                        "&device_code=$deviceCode" +
                        "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
                    )
                }

                // Handle 5xx server errors with back-off
                if (response.status.value in 500..599) {
                    onStateChange(DeviceFlowPollState.ServerError)
                    intervalMs = minOf(intervalMs + 30_000L, 60_000L)
                    continue
                }

                val poll = response.body<TokenPollResponse>()

                when {
                    poll.error == null && poll.accessToken != null -> {
                        return poll.accessToken.right()
                    }
                    poll.error == "authorization_pending" -> {
                        onStateChange(DeviceFlowPollState.Pending)
                        // interval stays the same
                    }
                    poll.error == "slow_down" -> {
                        // Cumulative — each slow_down adds 5 seconds to the current interval
                        intervalMs += 5_000L
                        onStateChange(DeviceFlowPollState.Pending)
                    }
                    poll.error == "expired_token" -> {
                        return DomainError.GitError.AuthFailed("Device code expired").left()
                    }
                    poll.error == "access_denied" -> {
                        return DomainError.GitError.AuthFailed("Authorization denied by user").left()
                    }
                    poll.error != null -> {
                        return DomainError.GitError.AuthFailed(
                            poll.errorDescription ?: poll.error
                        ).left()
                    }
                    else -> {
                        return DomainError.GitError.AuthFailed("Unexpected empty token response").left()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Network error — retry with back-off, do NOT stop polling
                onStateChange(DeviceFlowPollState.NetworkError(e.message ?: "Network error"))
                intervalMs = minOf(intervalMs * 2, 60_000L)
            } catch (e: Exception) {
                // Other transport error — retry with back-off
                onStateChange(DeviceFlowPollState.NetworkError(e.message ?: "Request failed"))
                intervalMs = minOf(intervalMs * 2, 60_000L)
            }
        }

        return DomainError.GitError.AuthFailed("Device flow timed out").left()
    }

    /**
     * Fetches the authenticated GitHub user's login name.
     * Returns null on any failure — username display is cosmetic.
     */
    suspend fun fetchUsername(token: String): String? {
        val client = httpClient ?: return null
        return try {
            val user = client.get(USER_API_URL) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.body<GitHubUser>()
            user.login
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
