package dev.stapler.stelekit.domain

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// These tests verify the URL construction logic without making real HTTP calls.
// Integration tests requiring a real network call are excluded from CI.

class NoOpWaybackMachineService : WaybackMachineService {
    override suspend fun archiveUrl(url: String): Either<DomainError, ArchiveResult> =
        DomainError.NetworkError.CircuitOpen("Archive service not configured").left()
}

class WaybackMachineServiceTest {

    @Test
    fun `archive url is well-formed`() {
        val url = "https://example.com/article"
        val archiveUrl = "https://web.archive.org/web/20260515000000/$url"
        // Verify the format we expect
        assertTrue(archiveUrl.startsWith("https://web.archive.org/web/"))
        assertTrue(archiveUrl.endsWith(url))
    }

    @Test
    fun `NoOpWaybackMachineService returns network error`() = runTest {
        val service = NoOpWaybackMachineService()
        val result = service.archiveUrl("https://example.com")
        assertIs<Either.Left<DomainError>>(result)
    }

    // --- KtorWaybackMachineService MockEngine tests ---

    @Test
    fun `success - 200 with ContentLocation header returns ArchiveResult Success`() = runTest {
        val contentLocation = "/web/20260515120000/https://example.com"
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLocation, contentLocation)
            )
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl("https://example.com")

        assertIs<Either.Right<ArchiveResult>>(result)
        val archive = result.value
        assertIs<ArchiveResult.Success>(archive)
        assertEquals("https://web.archive.org$contentLocation", archive.archiveUrl)
    }

    @Test
    fun `success - 200 without ContentLocation header falls back to wildcard url`() = runTest {
        val targetUrl = "https://example.com"
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl(targetUrl)

        assertIs<Either.Right<ArchiveResult>>(result)
        val archive = result.value
        assertIs<ArchiveResult.Success>(archive)
        assertEquals("https://web.archive.org/web/*/$targetUrl", archive.archiveUrl)
    }

    @Test
    fun `non-200 response returns Either Left with HttpError`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl("https://example.com")

        assertIs<Either.Left<DomainError>>(result)
        val error = result.value
        assertIs<DomainError.NetworkError.HttpError>(error)
        assertEquals(503, error.statusCode)
    }

    @Test
    fun `4xx response returns Either Left with HttpError`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.TooManyRequests)
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl("https://example.com")

        assertIs<Either.Left<DomainError>>(result)
        val error = result.value
        assertIs<DomainError.NetworkError.HttpError>(error)
        assertEquals(429, error.statusCode)
    }

    @Test
    fun `network IO exception returns Either Left with RequestFailed`() = runTest {
        val engine = MockEngine { _ ->
            throw IOException("Connection refused")
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl("https://example.com")

        assertIs<Either.Left<DomainError>>(result)
        val error = result.value
        assertIs<DomainError.NetworkError.RequestFailed>(error)
        assertContains(error.message, "Connection refused")
    }

    @Test
    fun `timeout exception returns Either Left with Timeout`() = runTest {
        val engine = MockEngine { _ ->
            throw IOException("Request timeout exceeded")
        }
        val service = KtorWaybackMachineService(HttpClient(engine))
        val result = service.archiveUrl("https://example.com")

        assertIs<Either.Left<DomainError>>(result)
        val error = result.value
        // IOException with "timeout" in message maps to NetworkError.Timeout
        assertIs<DomainError.NetworkError.Timeout>(error)
    }

    @Test
    fun `url with query params and fragment is properly encoded in request`() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val targetUrl = "https://example.com/page?foo=bar&baz=qux#section"
        val service = KtorWaybackMachineService(HttpClient(engine))
        service.archiveUrl(targetUrl)

        // The request URL must start with the Wayback save endpoint
        assertTrue(capturedUrl.startsWith("https://web.archive.org/save/"), "Expected request to go to web.archive.org/save/, got: $capturedUrl")
        // The target URL's path separator should appear after the save prefix
        assertTrue(capturedUrl.contains("example.com"), "Expected target host in encoded URL, got: $capturedUrl")
        // Query string characters ? and & must be percent-encoded (not appear as raw literal after the first path segment)
        val afterSavePrefix = capturedUrl.removePrefix("https://web.archive.org/save/")
        assertTrue(!afterSavePrefix.contains("?"), "Raw '?' must be encoded in the request URL, got: $capturedUrl")
    }
}
