// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.exchangeGoogleAuthCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [GoogleTokenStore]; the shared fake lives in businessTest, which jvmTest's Bazel target does not see. */
private class RecordingTokenStore : GoogleTokenStore {
    var accessToken: String? = null
    var refreshToken: String? = null
    var email: String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }
    override suspend fun getAccessToken() = accessToken
    override suspend fun getRefreshToken() = refreshToken
    override suspend fun getExpiresAt(): Long? = null
    override suspend fun clearTokens() { accessToken = null; refreshToken = null }
    override suspend fun isAuthenticated() = accessToken != null
    override suspend fun saveEmail(email: String) { this.email = email }
    override suspend fun getEmail() = email
}

/** Drives [exchangeGoogleAuthCode] through a real Ktor client against a mock token endpoint. */
class GoogleAuthCodeExchangeHttpTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private suspend fun exchange(
        engine: MockEngine,
        store: RecordingTokenStore,
        secret: String = "secret",
    ) = exchangeGoogleAuthCode(HttpClient(engine), "cid", secret, "the-code", "app://cb", "verifier", store)

    @Test
    fun exchange_should_PostPkceFormToTokenEndpointAndStoreTokens_When_GoogleSucceeds() = runTest {
        var url = ""
        var body = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = String(request.body.toByteArray())
            respond("""{"access_token":"at","expires_in":3600,"refresh_token":"rt"}""", HttpStatusCode.OK, json)
        }
        val store = RecordingTokenStore()

        val result = exchange(engine, store)

        assertEquals("Google account", assertIs<Either.Right<String>>(result).value)
        assertEquals("https://oauth2.googleapis.com/token", url)
        for (part in listOf("client_id=cid", "client_secret=secret", "code=the-code", "code_verifier=verifier",
            "grant_type=authorization_code", "redirect_uri=app%3A%2F%2Fcb")) {
            assertTrue(part in body, "form should contain $part but was $body")
        }
        assertEquals("rt", store.refreshToken)
    }

    @Test
    fun exchange_should_OmitClientSecret_When_SecretIsEmpty() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            respond("""{"access_token":"at","expires_in":1,"refresh_token":"rt"}""", HttpStatusCode.OK, json)
        }

        exchange(engine, RecordingTokenStore(), secret = "")

        assertFalse("client_secret" in body)
    }

    @Test
    fun exchange_should_ReturnHttpErrorWithoutStoringTokens_When_GoogleReturns400() = runTest {
        val engine = MockEngine { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json) }
        val store = RecordingTokenStore()

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(exchange(engine, store)).value

        assertEquals(400, error.statusCode)
        assertTrue("invalid_grant" in error.message)
        assertNull(store.accessToken)
    }

    @Test
    fun exchange_should_ReturnNetworkError_When_TheEngineThrows() = runTest {
        val engine = MockEngine { throw IllegalStateException("offline SECRET") }

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(exchange(engine, RecordingTokenStore())).value

        assertEquals(-1, error.statusCode)
        assertTrue("IllegalStateException" in error.message)
        assertFalse("SECRET" in error.message, "exception messages must not leak into the error")
    }

    @Test
    fun exchange_should_Rethrow_When_Cancelled() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { exchange(engine, RecordingTokenStore()) }
    }
}
