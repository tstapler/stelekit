// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.DeviceCodeResponse
import dev.stapler.stelekit.git.model.DeviceFlowPollState
import dev.stapler.stelekit.ui.screens.git.OAuthDialogState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubDeviceFlowClientTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun buildClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(lenientJson) }
        }

    // -------------------------------------------------------------------------
    // requestDeviceCode
    // -------------------------------------------------------------------------

    @Test
    fun `requestDeviceCode_returnsDeviceCodeResponse_onSuccess`() = runTest {
        val responseJson = """
            {
              "device_code": "dev123",
              "user_code": "ABCD-1234",
              "verification_uri": "https://github.com/login/device",
              "expires_in": 900,
              "interval": 5
            }
        """.trimIndent()

        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val result = client.requestDeviceCode()

        assertIs<Either.Right<DeviceCodeResponse>>(result)
        val response = result.value
        assertEquals("dev123", response.deviceCode)
        assertEquals("ABCD-1234", response.userCode)
        assertEquals("https://github.com/login/device", response.verificationUri)
        assertEquals(900, response.expiresIn)
        assertEquals(5, response.interval)
    }

    @Test
    fun `requestDeviceCode_returnsError_on4xx`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Forbidden)
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val result = client.requestDeviceCode()

        assertIs<Either.Left<DomainError.GitError>>(result)
        val error = result.value
        assertIs<DomainError.GitError.AuthFailed>(error)
        assertTrue(error.message.contains("403"))
    }

    // -------------------------------------------------------------------------
    // pollForToken
    // -------------------------------------------------------------------------

    @Test
    fun `pollForToken_returnsToken_onFirstSuccessfulPoll`() = runTest {
        val responseJson = """{"access_token":"gho_abc123","token_type":"bearer","scope":"repo"}"""

        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Right<String>>(result)
        assertEquals("gho_abc123", result.value)
        // No intermediate state changes expected on immediate success
        assertTrue(states.isEmpty())
    }

    @Test
    fun `pollForToken_continuesPolling_onAuthorizationPending`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val body = when (callCount) {
                1 -> """{"error":"authorization_pending"}"""
                else -> """{"access_token":"gho_pending123","token_type":"bearer","scope":"repo"}"""
            }
            respond(content = ByteReadChannel(body), status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Right<String>>(result)
        assertEquals("gho_pending123", result.value)
        assertEquals(1, states.size)
        assertIs<DeviceFlowPollState.Pending>(states.first())
        assertEquals(2, callCount)
    }

    @Test
    fun `pollForToken_retriesAndContinues_on5xxServerError`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            if (callCount == 1) {
                respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError, headers = jsonHeaders())
            } else {
                respond(
                    content = ByteReadChannel("""{"access_token":"gho_srv","token_type":"bearer","scope":"repo"}"""),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            }
        }
        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Right<String>>(result)
        assertEquals("gho_srv", result.value)
        assertEquals(1, states.size)
        assertIs<DeviceFlowPollState.ServerError>(states.first())
        assertEquals(2, callCount)
    }

    @Test
    fun `pollForToken_incrementsInterval_cumulativelyOnSlowDown`() = runTest {
        // Returns slow_down twice then a token. Verify onStateChange receives Pending
        // for each slow_down (interval grows, but polling continues).
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val body = when (callCount) {
                1 -> """{"error":"slow_down"}"""
                2 -> """{"error":"slow_down"}"""
                else -> """{"access_token":"gho_slow123","token_type":"bearer","scope":"repo"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Right<String>>(result)
        assertEquals("gho_slow123", result.value)
        // Two slow_down responses → two Pending state changes
        assertEquals(2, states.size)
        assertTrue(states.all { it is DeviceFlowPollState.Pending })
        assertEquals(3, callCount)
    }

    @Test
    fun `pollForToken_stopsPolling_onAccessDenied`() = runTest {
        val responseJson = """{"error":"access_denied"}"""

        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Left<DomainError.GitError>>(result)
        val error = result.value
        assertIs<DomainError.GitError.AuthFailed>(error)
        assertTrue(error.message.contains("denied") || error.message.contains("Authorization"))
        assertTrue(states.isEmpty())
    }

    @Test
    fun `pollForToken_stopsPolling_onExpiredToken`() = runTest {
        val responseJson = """{"error":"expired_token"}"""

        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Left<DomainError.GitError>>(result)
        val error = result.value
        assertIs<DomainError.GitError.AuthFailed>(error)
        assertTrue(error.message.contains("expired") || error.message.contains("Device code"))
        assertTrue(states.isEmpty())
    }

    @Test
    fun `pollForToken_retriesAndContinues_onNetworkError`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            if (callCount == 1) {
                throw IOException("Connection refused")
            }
            respond(
                content = ByteReadChannel("""{"access_token":"gho_retry456","token_type":"bearer","scope":"repo"}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client.pollForToken(
            deviceCode = "dev123",
            expiresIn = 300,
            initialInterval = 0,
            onStateChange = { states.add(it) },
        )

        assertIs<Either.Right<String>>(result)
        assertEquals("gho_retry456", result.value)
        // First call throws IOException → NetworkError state change, then success on second call
        assertEquals(1, states.size)
        val networkError = states.first()
        assertIs<DeviceFlowPollState.NetworkError>(networkError)
        assertTrue(networkError.message.contains("Connection refused"))
        assertEquals(2, callCount)
    }

    // -------------------------------------------------------------------------
    // fetchUsername
    // -------------------------------------------------------------------------

    @Test
    fun `fetchUsername_returnsLogin_onSuccess`() = runTest {
        val responseJson = """{"login":"testuser","id":42,"name":"Test User"}"""

        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val username = client.fetchUsername("gho_token123")

        assertEquals("testuser", username)
    }

    @Test
    fun `fetchUsername_returnsNull_onError`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }

        val client = GitHubDeviceFlowClient(buildClient(engine))
        val username = client.fetchUsername("gho_bad_token")

        assertEquals(null, username)
    }

    // -------------------------------------------------------------------------
    // OAuthDialogState.Polling — state model
    // -------------------------------------------------------------------------

    @Test
    fun `OAuthDialogState_Polling_carries_code_and_expiry_fields`() {
        val state = OAuthDialogState.Polling(
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresAt = 9_999_999L,
        )
        assertIs<OAuthDialogState.Polling>(state)
        assertEquals("ABCD-1234", state.userCode)
        assertEquals("https://github.com/login/device", state.verificationUri)
        assertEquals(9_999_999L, state.expiresAt)
    }
}
