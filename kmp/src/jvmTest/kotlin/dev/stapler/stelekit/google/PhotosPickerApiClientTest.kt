// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.GoogleAuthClient
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.PhotosPickerApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Direct tests of [PhotosPickerApiClient] edge branches; complements [GoogleApiClientTest]. */
class PhotosPickerApiClientTest {

    private class Store(private val token: String?) : GoogleTokenStore {
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
        override suspend fun getAccessToken() = token
        override suspend fun getRefreshToken() = token?.let { "rt" }
        override suspend fun getExpiresAt(): Long? = token?.let { System.currentTimeMillis() + 3_600_000L }
        override suspend fun clearTokens() {}
        override suspend fun isAuthenticated() = token != null
        override suspend fun saveEmail(email: String) {}
        override suspend fun getEmail(): String? = null
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(engine: MockEngine, authed: Boolean = true): PhotosPickerApiClient {
        val http = HttpClient(engine)
        return PhotosPickerApiClient(GoogleAuthClient(Store(if (authed) "tok" else null), http), http)
    }

    private fun engineFailing() = MockEngine { error("no HTTP call expected when unauthenticated") }

    private fun status(result: Either<DomainError, *>): Int =
        assertIs<DomainError.NetworkError.HttpError>((result as Either.Left).value).statusCode

    // ── unauthenticated: every method short-circuits to 401 without an HTTP call ──

    @Test
    fun `unauthenticated calls return 401 Left for every method`() = runTest {
        val c = client(engineFailing(), authed = false)
        assertEquals(401, status(c.createPhotosPickerSession()))
        assertEquals(401, status(c.getPickerSession("s")))
        assertEquals(401, status(c.downloadPickerMedia("https://x/y", "id")))
        assertEquals(401, status(c.listPickerMediaItems("s")))
        assertEquals(401, status(c.deletePickerSession("s")))
    }

    // ── HTTP failures ──

    @Test
    fun `getPickerSession returns Left on HTTP 500`() = runTest {
        val c = client(MockEngine { respond("", HttpStatusCode.InternalServerError) })
        assertEquals(500, status(c.getPickerSession("s")))
    }

    @Test
    fun `createPhotosPickerSession returns Left on HTTP 500 and on malformed body`() = runTest {
        assertEquals(500, status(client(MockEngine { respond("", HttpStatusCode.InternalServerError) }).createPhotosPickerSession()))
        val bad = client(MockEngine { respond("not json", HttpStatusCode.OK, json) })
        assertEquals(-1, status(bad.createPhotosPickerSession()))
    }

    @Test
    fun `deletePickerSession returns Left on HTTP 500`() = runTest {
        val c = client(MockEngine { respond("", HttpStatusCode.InternalServerError) })
        assertEquals(500, status(c.deletePickerSession("s")))
    }

    @Test
    fun `network exceptions map to status -1 for every method`() = runTest {
        val c = client(MockEngine { throw IOException("down") })
        assertEquals(-1, status(c.createPhotosPickerSession()))
        assertEquals(-1, status(c.getPickerSession("s")))
        assertEquals(-1, status(c.downloadPickerMedia("https://x/y", "id")))
        assertEquals(-1, status(c.listPickerMediaItems("s")))
        assertEquals(-1, status(c.deletePickerSession("s")))
    }

    // ── downloadPickerMedia =d suffix ──

    @Test
    fun `downloadPickerMedia appends =d when missing`() = runTest {
        var requested = ""
        val c = client(MockEngine { req -> requested = req.url.toString(); respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) })
        val result = c.downloadPickerMedia("https://lh3.example.com/abc", "id1")
        assertContentEquals(byteArrayOf(1, 2, 3), (result as Either.Right).value)
        assertTrue(requested.endsWith("/abc=d"), requested)
    }

    @Test
    fun `downloadPickerMedia does not double-append =d`() = runTest {
        var requested = ""
        val c = client(MockEngine { req -> requested = req.url.toString(); respond(byteArrayOf(9), HttpStatusCode.OK) })
        c.downloadPickerMedia("https://lh3.example.com/abc=d", "id1")
        assertTrue(requested.endsWith("/abc=d"), requested)
        assertTrue(!requested.endsWith("=d=d"), requested)
    }

    @Test
    fun `downloadPickerMedia returns Left on HTTP 404`() = runTest {
        val c = client(MockEngine { respond("", HttpStatusCode.NotFound) })
        val err = assertIs<DomainError.NetworkError.HttpError>((c.downloadPickerMedia("https://x/y", "item-7") as Either.Left).value)
        assertEquals(404, err.statusCode)
        assertTrue("item-7" in err.message)
    }

    // ── listPickerMediaItems ──

    @Test
    fun `listPickerMediaItems skips items without mediaFile and sends sessionId`() = runTest {
        var query = ""
        val c = client(MockEngine { req ->
            query = req.url.parameters["sessionId"].orEmpty()
            respond(
                """{"mediaItems":[
                    {"id":"keep","mediaFile":{"baseUrl":"https://b/1","mimeType":"image/jpeg"}},
                    {"id":"skip"},
                    {"id":"keep2","mediaFile":{"baseUrl":"https://b/2"}}
                ]}""",
                HttpStatusCode.OK, json,
            )
        })
        val result = c.listPickerMediaItems("sess-1")
        assertEquals(listOf("https://b/1" to "keep", "https://b/2" to "keep2"), (result as Either.Right).value)
        assertEquals("sess-1", query)
    }
}
