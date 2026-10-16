// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.refreshGoogleToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleTokenRefresherTest {

    private class RecordingStore : GoogleTokenStore {
        var accessToken: String? = null
        var refreshToken: String? = null
        var expiresAt: Long? = null
        var saves = 0
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
            this.accessToken = accessToken; this.refreshToken = refreshToken; this.expiresAt = expiresAt; saves++
        }
        override suspend fun getAccessToken() = accessToken
        override suspend fun getRefreshToken() = refreshToken
        override suspend fun getExpiresAt() = expiresAt
        override suspend fun clearTokens() {}
        override suspend fun isAuthenticated() = accessToken != null
        override suspend fun saveEmail(email: String) {}
        override suspend fun getEmail(): String? = null
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun clientReturning(status: HttpStatusCode, body: String) =
        HttpClient(MockEngine { respond(body, status, json) })

    @Test
    fun `success saves token with expiry and returns access token`() = runTest {
        val store = RecordingStore()
        val before = System.currentTimeMillis()
        val result = refreshGoogleToken(
            clientReturning(HttpStatusCode.OK, """{"access_token":"new-at","expires_in":3600,"token_type":"Bearer"}"""),
            "cid", "secret", "old-rt", store,
        )
        assertEquals("new-at", (result as Either.Right).value)
        assertEquals("new-at", store.accessToken)
        assertEquals("old-rt", store.refreshToken)
        assertTrue(store.expiresAt!! >= before + 3_600_000L)
    }

    @Test
    fun `request is a form post carrying client credentials and refresh grant`() = runTest {
        var form = ""
        val client = HttpClient(MockEngine { req ->
            form = req.body.toByteArray().decodeToString()
            respond("""{"access_token":"a","expires_in":1}""", HttpStatusCode.OK, json)
        })
        refreshGoogleToken(client, "my-cid", "my-secret", "my-rt", RecordingStore())
        assertTrue("client_id=my-cid" in form)
        assertTrue("client_secret=my-secret" in form)
        assertTrue("refresh_token=my-rt" in form)
        assertTrue("grant_type=refresh_token" in form)
    }

    @Test
    fun `HTTP 400 returns Left with status and does not save`() = runTest {
        val store = RecordingStore()
        val result = refreshGoogleToken(
            clientReturning(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}"""),
            "cid", "secret", "rt", store,
        )
        val err = assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value)
        assertEquals(400, err.statusCode)
        assertEquals(0, store.saves)
    }

    @Test
    fun `HTTP 401 returns Left with that status`() = runTest {
        val result = refreshGoogleToken(
            clientReturning(HttpStatusCode.Unauthorized, "{}"), "cid", "secret", "rt", RecordingStore(),
        )
        assertEquals(401, assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value).statusCode)
    }

    @Test
    fun `error field in a 200 body returns Left and does not save`() = runTest {
        val store = RecordingStore()
        val result = refreshGoogleToken(
            clientReturning(
                HttpStatusCode.OK,
                """{"access_token":"x","expires_in":1,"error":"invalid_client","error_description":"bad secret"}""",
            ),
            "cid", "secret", "rt", store,
        )
        val err = assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value)
        assertTrue("invalid_client" in err.message && "bad secret" in err.message)
        assertEquals(0, store.saves)
    }

    @Test
    fun `network exception returns Left with status -1`() = runTest {
        val client = HttpClient(MockEngine { throw IOException("connection reset") })
        val store = RecordingStore()
        val result = refreshGoogleToken(client, "cid", "secret", "rt", store)
        val err = assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value)
        assertEquals(-1, err.statusCode)
        assertTrue("connection reset" in err.message)
        assertNull(store.accessToken)
    }

    @Test
    fun `malformed body returns Left rather than throwing`() = runTest {
        val result = refreshGoogleToken(
            clientReturning(HttpStatusCode.OK, "not json"), "cid", "secret", "rt", RecordingStore(),
        )
        assertEquals(-1, assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value).statusCode)
    }

    @Test
    fun `rotated refresh token replaces the old one`() = runTest {
        val store = RecordingStore()
        refreshGoogleToken(
            clientReturning(HttpStatusCode.OK, """{"access_token":"at2","expires_in":60,"refresh_token":"rotated-rt"}"""),
            "cid", "secret", "old-rt", store,
        )
        assertEquals("rotated-rt", store.refreshToken)
    }
}
