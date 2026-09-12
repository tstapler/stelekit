package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureAndImportTest {

    // ── withImportTimeout ───────────────────────────────────────────────────

    @Test
    fun `withImportTimeout returns null instead of hanging when the import operation never completes`() =
        runBlocking {
            val result = withImportTimeout<Either<DomainError, String>>(timeoutMs = 200L) {
                suspendCancellableCoroutine { /* never resumed — simulates a wedged save */ }
            }
            assertNull(result, "a stalled import must time out instead of hanging forever")
        }

    @Test
    fun `withImportTimeout returns the operation result when it completes in time`() = runBlocking {
        val expected: Either<DomainError, String> = "ok".right()
        val result = withImportTimeout(timeoutMs = 200L) { expected }
        assertEquals(expected, result)
    }
}
