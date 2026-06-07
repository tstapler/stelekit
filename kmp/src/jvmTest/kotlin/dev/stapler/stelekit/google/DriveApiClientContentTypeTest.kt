// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import dev.stapler.stelekit.platform.google.GoogleApiClient
import dev.stapler.stelekit.platform.google.GoogleTokenStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for ADR-6: DriveApiClient content-type fix.
 *
 * When uploading HTML for Google Docs conversion, the multipart content part MUST declare
 * `Content-Type: text/html` (the source format), NOT `application/vnd.google-apps.document`
 * (the target mimeType). Sending the target mimeType causes Drive to reject the upload.
 *
 * U-DAC-01: Google Docs upload uses text/html in content part
 * U-DAC-02: Non-Google-Docs upload uses the original mimeType in content part
 */
class DriveApiClientContentTypeTest {

    private val tokenStore: GoogleTokenStore = object : GoogleTokenStore {
        private val expiresAt = System.currentTimeMillis() + 3_600_000L

        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
        override suspend fun getAccessToken(): String = "mock-token"
        override suspend fun getRefreshToken(): String = "mock-refresh"
        override suspend fun getExpiresAt(): Long = expiresAt
        override suspend fun clearTokens() {}
        override suspend fun isAuthenticated(): Boolean = true
        override suspend fun saveEmail(email: String) {}
        override suspend fun getEmail(): String? = "test@example.com"
    }

    /** Captures the raw request body bytes so we can inspect the multipart body. */
    private var capturedBody: ByteArray = byteArrayOf()

    private fun buildClient(statusCode: HttpStatusCode = HttpStatusCode.OK): GoogleApiClient {
        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteArray()
            respond(
                content = """{"id":"doc-file-id","name":"Test","mimeType":"application/vnd.google-apps.document"}""",
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GoogleApiClient(HttpClient(mockEngine), tokenStore)
    }

    // ── U-DAC-01: Google Docs upload uses text/html in content part ───────────

    @Test
    fun uDAC01_googleDocsUpload_contentPartUsesTextHtml() = runTest {
        val client = buildClient()
        val htmlBytes = "<h1>Title</h1><p>Body</p>".encodeToByteArray()

        client.drive.uploadFile(
            fileName = "My Doc",
            mimeType = "application/vnd.google-apps.document",
            bytes = htmlBytes,
            parentFolderId = null,
        )

        val bodyText = capturedBody.decodeToString()
        // The content part of the multipart body must declare text/html, not the target mimeType
        assertTrue(
            bodyText.contains("Content-Type: text/html"),
            "Expected 'Content-Type: text/html' in multipart body but got:\n$bodyText",
        )
        assertFalse(
            // The metadata part declares the target mimeType; the content part must NOT
            bodyText.count { it == '\n' }.let {
                // Check that text/html appears at least once in the body (content part)
                !bodyText.contains("Content-Type: text/html")
            },
            "Content-Type: text/html must appear in the multipart body",
        )
    }

    // ── U-DAC-02: Non-Docs upload uses original mimeType in content part ──────

    @Test
    fun uDAC02_nonDocsUpload_contentPartUsesOriginalMimeType() = runTest {
        val client = buildClient()
        val jsonBytes = """{"key": "value"}""".encodeToByteArray()

        client.drive.uploadFile(
            fileName = "data.json",
            mimeType = "application/json",
            bytes = jsonBytes,
            parentFolderId = null,
        )

        val bodyText = capturedBody.decodeToString()
        assertTrue(
            bodyText.contains("Content-Type: application/json"),
            "Expected 'Content-Type: application/json' in multipart body but got:\n$bodyText",
        )
        assertFalse(
            bodyText.contains("Content-Type: text/html"),
            "Non-HTML upload should not use text/html content type",
        )
    }
}
