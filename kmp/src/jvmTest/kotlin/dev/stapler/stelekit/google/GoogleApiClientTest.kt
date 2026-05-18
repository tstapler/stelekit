// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.GoogleApiClient
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.PhotosPickerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [GoogleApiClient] using a Ktor [MockEngine].
 *
 * Covers Drive upload, folder listing, folder creation, file download,
 * Google Photos Picker session operations, and mediaItems listing.
 * No real HTTP calls are made.
 */
class GoogleApiClientTest {

    // ── Test fixtures ─────────────────────────────────────────────────────────

    /** Stub token store with a pre-loaded, unexpired token. */
    private val tokenStore: GoogleTokenStore = object : GoogleTokenStore {
        private val expiresAt = System.currentTimeMillis() + 3_600_000L // 1 hour from now

        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
        override suspend fun getAccessToken(): String = "mock-access-token"
        override suspend fun getRefreshToken(): String = "mock-refresh-token"
        override suspend fun getExpiresAt(): Long = expiresAt
        override suspend fun clearTokens() {}
        override suspend fun isAuthenticated(): Boolean = true
    }

    private fun buildClient(mockEngine: MockEngine): GoogleApiClient {
        val httpClient = HttpClient(mockEngine)
        return GoogleApiClient(httpClient, tokenStore)
    }

    // ── uploadFile ────────────────────────────────────────────────────────────

    @Test
    fun `uploadFile returns file ID on success`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"drive-file-id-abc","name":"test.jpg","mimeType":"image/jpeg"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.uploadFile("test.jpg", "image/jpeg", ByteArray(100), null)
        assertIs<Either.Right<String>>(result)
        assertEquals("drive-file-id-abc", result.value)
    }

    @Test
    fun `uploadFile returns error on HTTP 403`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":{"code":403,"message":"Forbidden"}}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.uploadFile("test.jpg", "image/jpeg", ByteArray(100), null)
        assertIs<Either.Left<DomainError>>(result)
        assertIs<DomainError.NetworkError.HttpError>(result.value)
        assertEquals(403, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    @Test
    fun `uploadFile passes parentFolderId in request body`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{"id":"file-in-folder","name":"img.jpg","mimeType":"image/jpeg"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        client.uploadFile("img.jpg", "image/jpeg", ByteArray(10), "folder-xyz")
        assertTrue(capturedBody.contains("folder-xyz"), "Request body should contain the folder ID")
    }

    // ── listFiles ─────────────────────────────────────────────────────────────

    @Test
    fun `listFiles returns parsed DriveFile list`() = runTest {
        val responseJson = """
            {
                "files": [
                    {"id":"id1","name":"photo.jpg","mimeType":"image/jpeg","size":"204800","modifiedTime":"2026-03-15T10:00:00Z"},
                    {"id":"id2","name":"Measurements","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-03-14T08:00:00Z"}
                ]
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.listFiles(folderId = null)
        assertIs<Either.Right<List<DriveFile>>>(result)
        assertEquals(2, result.value.size)
        assertEquals("id1", result.value[0].id)
        assertEquals("photo.jpg", result.value[0].name)
        assertTrue(result.value[1].isFolder)
    }

    @Test
    fun `listFiles returns empty list when files array absent`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.listFiles()
        assertIs<Either.Right<List<DriveFile>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `listFiles returns error on HTTP 500`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = buildClient(engine)
        val result = client.listFiles()
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(500, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    // ── createFolder ──────────────────────────────────────────────────────────

    @Test
    fun `createFolder returns folder ID on success`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"new-folder-id","name":"SteleKit Exports","mimeType":"application/vnd.google-apps.folder"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.createFolder("SteleKit Exports", parentId = null)
        assertIs<Either.Right<String>>(result)
        assertEquals("new-folder-id", result.value)
    }

    @Test
    fun `createFolder returns error on HTTP 409`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":{"code":409,"message":"Conflict"}}""",
                status = HttpStatusCode.Conflict,
            )
        }
        val client = buildClient(engine)
        val result = client.createFolder("Dup", parentId = null)
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(409, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    // ── downloadFile ──────────────────────────────────────────────────────────

    @Test
    fun `downloadFile returns bytes on success`() = runTest {
        val expectedBytes = "JPEG_MAGIC_BYTES".toByteArray()
        val engine = MockEngine { _ ->
            respond(
                content = expectedBytes,
                status = HttpStatusCode.OK,
            )
        }
        val client = buildClient(engine)
        val result = client.downloadFile("some-file-id")
        assertIs<Either.Right<ByteArray>>(result)
        assertTrue(result.value.contentEquals(expectedBytes))
    }

    @Test
    fun `downloadFile returns error on HTTP 404`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Not Found", status = HttpStatusCode.NotFound)
        }
        val client = buildClient(engine)
        val result = client.downloadFile("missing-id")
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(404, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    // ── createPhotosPickerSession ─────────────────────────────────────────────

    @Test
    fun `createPhotosPickerSession returns session on success`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"session-001","pickerUri":"https://photospicker.googleapis.com/ui/session-001","mediaItemsSet":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.createPhotosPickerSession()
        assertIs<Either.Right<PhotosPickerSession>>(result)
        assertEquals("session-001", result.value.id)
        assertTrue(result.value.pickerUri.isNotBlank())
    }

    @Test
    fun `createPhotosPickerSession returns error on HTTP 401`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Unauthorized", status = HttpStatusCode.Unauthorized)
        }
        val client = buildClient(engine)
        val result = client.createPhotosPickerSession()
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(401, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    // ── listPickerMediaItems ──────────────────────────────────────────────────

    @Test
    fun `listPickerMediaItems returns baseUrl and mediaItemId pairs`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                        "mediaItems": [
                            {"id":"item-001","mediaFile":{"baseUrl":"https://lh3.google.com/photo-1","mimeType":"image/jpeg"}},
                            {"id":"item-002","mediaFile":{"baseUrl":"https://lh3.google.com/photo-2","mimeType":"image/jpeg"}}
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.listPickerMediaItems("session-001")
        assertIs<Either.Right<List<Pair<String, String>>>>(result)
        assertEquals(2, result.value.size)
        assertEquals("https://lh3.google.com/photo-1", result.value[0].first)
        assertEquals("item-001", result.value[0].second)
        assertEquals("item-002", result.value[1].second)
    }

    @Test
    fun `listPickerMediaItems returns empty list when mediaItems absent`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.listPickerMediaItems("session-001")
        assertIs<Either.Right<List<Pair<String, String>>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `listPickerMediaItems returns error on HTTP 403`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Forbidden", status = HttpStatusCode.Forbidden)
        }
        val client = buildClient(engine)
        val result = client.listPickerMediaItems("session-bad")
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(403, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }

    // ── getPickerSession ──────────────────────────────────────────────────────

    @Test
    fun `getPickerSession returns updated session with mediaItemsSet true`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"session-001","pickerUri":"https://photospicker.googleapis.com/ui/session-001","mediaItemsSet":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildClient(engine)
        val result = client.getPickerSession("session-001")
        assertIs<Either.Right<PhotosPickerSession>>(result)
        assertTrue(result.value.mediaItemsSet)
    }

    // ── deletePickerSession ───────────────────────────────────────────────────

    @Test
    fun `deletePickerSession succeeds on HTTP 204`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = buildClient(engine)
        val result = client.deletePickerSession("session-001")
        assertIs<Either.Right<Unit>>(result)
    }

    @Test
    fun `deletePickerSession succeeds on HTTP 200`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val client = buildClient(engine)
        val result = client.deletePickerSession("session-001")
        assertIs<Either.Right<Unit>>(result)
    }

    // ── unauthenticated store ─────────────────────────────────────────────────

    @Test
    fun `uploadFile returns 401 error when not authenticated`() = runTest {
        val emptyStore: GoogleTokenStore = object : GoogleTokenStore {
            override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
            override suspend fun getAccessToken(): String? = null
            override suspend fun getRefreshToken(): String? = null
            override suspend fun getExpiresAt(): Long? = null
            override suspend fun clearTokens() {}
            override suspend fun isAuthenticated(): Boolean = false
        }
        val httpClient = HttpClient(MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.OK)
        })
        val client = GoogleApiClient(httpClient, emptyStore)
        val result = client.uploadFile("file.jpg", "image/jpeg", ByteArray(10), null)
        assertIs<Either.Left<DomainError>>(result)
        assertEquals(401, (result.value as DomainError.NetworkError.HttpError).statusCode)
    }
}
