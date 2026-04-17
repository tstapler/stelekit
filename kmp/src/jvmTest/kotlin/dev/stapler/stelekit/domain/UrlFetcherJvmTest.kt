// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class UrlFetcherJvmTest {

    private fun buildFetcher(engine: MockEngine): UrlFetcherJvm {
        val client = HttpClient(engine) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
            }
        }
        return UrlFetcherJvm(client)
    }

    // 1. 200 OK — plain text extracted and <title> in pageTitle
    @Test
    fun `200 OK with HTML body returns Success with text and title`() = runTest {
        val html = """
            <html>
              <head><title>Hello World</title></head>
              <body><p>Some content here.</p></body>
            </html>
        """.trimIndent()

        val engine = MockEngine { _ ->
            respond(
                content = html,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = buildFetcher(engine).fetch("https://example.com")

        assertIs<FetchResult.Success>(result)
        assertEquals("Hello World", result.pageTitle)
        assert(result.text.contains("Some content here.")) {
            "Expected text to contain page content, got: ${result.text}"
        }
    }

    // 2. 404 Not Found → FetchResult.Failure.HttpError(404)
    @Test
    fun `404 response returns HttpError with code 404`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val result = buildFetcher(engine).fetch("https://example.com/missing")

        assertIs<FetchResult.Failure.HttpError>(result)
        assertEquals(404, result.code)
    }

    // 3. 200 OK with body > 2 MB → FetchResult.Failure.TooLarge
    @Test
    fun `response body over 2 MB returns TooLarge`() = runTest {
        val bigBody = "x".repeat(2 * 1024 * 1024 + 1)

        val engine = MockEngine { _ ->
            respond(
                content = bigBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = buildFetcher(engine).fetch("https://example.com/large")

        assertIs<FetchResult.Failure.TooLarge>(result)
    }

    // 4. file:// URL → rejected without network call
    @Test
    fun `file scheme URL is rejected without making a network call`() = runTest {
        var networkCallMade = false
        val engine = MockEngine { _ ->
            networkCallMade = true
            respond(content = "", status = HttpStatusCode.OK)
        }

        val result = buildFetcher(engine).fetch("file:///etc/passwd")

        assertIs<FetchResult.Failure.HttpError>(result)
        assertEquals(0, result.code)
        assert(!networkCallMade) { "No network call should be made for file:// URLs" }
    }

    // 5. HTML with <script> and <style> blocks → contents stripped from output
    @Test
    fun `script and style content is stripped from extracted text`() = runTest {
        val html = """
            <html>
              <head>
                <title>Stripped Page</title>
                <style>body { color: red; } .ugly { display: none; }</style>
              </head>
              <body>
                <p>Visible content.</p>
                <script>alert('evil');</script>
                <p>More visible content.</p>
              </body>
            </html>
        """.trimIndent()

        val engine = MockEngine { _ ->
            respond(
                content = html,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = buildFetcher(engine).fetch("https://example.com")

        assertIs<FetchResult.Success>(result)
        assert(!result.text.contains("alert")) { "Script content should be stripped, got: ${result.text}" }
        assert(!result.text.contains("color: red")) { "Style content should be stripped, got: ${result.text}" }
        assert(result.text.contains("Visible content.")) { "Visible text should remain, got: ${result.text}" }
    }

    // 6. <title> tag correctly extracted
    @Test
    fun `title tag is correctly extracted from HTML head`() = runTest {
        val html = """
            <html><head><title>My Page Title</title></head><body>content</body></html>
        """.trimIndent()

        val engine = MockEngine { _ ->
            respond(
                content = html,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = buildFetcher(engine).fetch("https://example.com")

        assertIs<FetchResult.Success>(result)
        assertNotNull(result.pageTitle)
        assertEquals("My Page Title", result.pageTitle)
    }

    // 7. Timeout → FetchResult.Failure.Timeout
    @Test
    fun `request timeout returns Timeout failure`() = runTest {
        val engine = MockEngine { _ ->
            // Simulate a very slow server by throwing timeout exception
            throw io.ktor.client.plugins.HttpRequestTimeoutException("https://example.com", 15_000)
        }

        val result = buildFetcher(engine).fetch("https://example.com/slow")

        assertIs<FetchResult.Failure.Timeout>(result)
    }

    // 8. non-http/https scheme (data://) also rejected
    @Test
    fun `data scheme URL is rejected as scheme error`() = runTest {
        var networkCallMade = false
        val engine = MockEngine { _ ->
            networkCallMade = true
            respond(content = "", status = HttpStatusCode.OK)
        }

        val result = buildFetcher(engine).fetch("data:text/html,<h1>hi</h1>")

        assertIs<FetchResult.Failure.HttpError>(result)
        assertEquals(0, result.code)
        assert(!networkCallMade) { "No network call should be made for data: URLs" }
    }
}
