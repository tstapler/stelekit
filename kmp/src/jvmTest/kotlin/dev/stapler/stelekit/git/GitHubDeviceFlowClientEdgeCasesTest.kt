// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.DeviceFlowPollState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Failure and guard paths of [GitHubDeviceFlowClient]; happy paths are in `GitHubDeviceFlowClientTest`. jvmTest-reason: shares that test's ktor MockEngine fixtures. */
class GitHubDeviceFlowClientEdgeCasesTest {
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK, onRequest: (String) -> Unit = {}): GitHubDeviceFlowClient {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GitHubDeviceFlowClient(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    @Test
    fun withoutAnHttpClient_everyStepFailsClosedWithoutThrowing() = runTest {
        val client = GitHubDeviceFlowClient(httpClient = null)

        assertEquals("HTTP client not available", client.requestDeviceCode().leftOrNull()?.message)
        assertEquals("HTTP client not available", client.pollForToken("d", 10, 0) { }.leftOrNull()?.message)
        assertNull(client.fetchUsername("token"))
        client.close()
    }

    @Test
    fun requestDeviceCode_malformedBodyBecomesAuthFailedNotAnException() = runTest {
        val result = client("not json at all").requestDeviceCode()

        assertTrue(result.leftOrNull() is DomainError.GitError.AuthFailed, result.toString())
    }

    @Test
    fun requestDeviceCode_postsToTheDeviceCodeEndpoint() = runTest {
        var url: String? = null
        client("""{"device_code":"d","user_code":"U","verification_uri":"v","expires_in":1,"interval":1}""", onRequest = { url = it })
            .requestDeviceCode()

        assertEquals("https://github.com/login/device/code", url)
    }

    @Test
    fun pollForToken_surfacesGithubErrorDescriptionForUnknownErrors() = runTest {
        val result = client("""{"error":"unsupported_grant_type","error_description":"The grant type is not supported"}""")
            .pollForToken("d", expiresIn = 300, initialInterval = 0) { }

        assertEquals(DomainError.GitError.AuthFailed("The grant type is not supported"), result.leftOrNull())
    }

    @Test
    fun pollForToken_fallsBackToErrorCodeWhenNoDescription() = runTest {
        val result = client("""{"error":"incorrect_client_credentials"}""")
            .pollForToken("d", expiresIn = 300, initialInterval = 0) { }

        assertEquals(DomainError.GitError.AuthFailed("incorrect_client_credentials"), result.leftOrNull())
    }

    @Test
    fun pollForToken_emptyResponseIsATerminalFailure() = runTest {
        val states = mutableListOf<DeviceFlowPollState>()

        val result = client("{}").pollForToken("d", expiresIn = 300, initialInterval = 0) { states += it }

        assertEquals(DomainError.GitError.AuthFailed("Unexpected empty token response"), result.leftOrNull())
        assertTrue(states.isEmpty())
    }

    @Test
    fun fetchUsername_returnsLoginFromTheUserEndpoint() = runTest {
        var url: String? = null

        val login = client("""{"login":"octocat"}""", onRequest = { url = it }).fetchUsername("tok")

        assertEquals("octocat", login)
        assertEquals("https://api.github.com/user", url)
    }
}
