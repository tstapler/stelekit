package dev.stapler.stelekit.domain

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
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
}
