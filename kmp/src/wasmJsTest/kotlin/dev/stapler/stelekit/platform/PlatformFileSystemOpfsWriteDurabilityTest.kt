// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epic 1.7 (Story 1.7.3 / Task 1.7.3a): dedicated coverage of the awaited-OPFS-write mechanism
 * itself (`PlatformFileSystem.opfsWriteInFlight` tracking + `opfsWriteDeferredFor`), tested
 * directly against `writeFile`/`writeFileBytes` — not just observed indirectly through
 * `HostDirectorySync` — so this epic's own new contract is verified independently, per this
 * story's Acceptance Criteria.
 *
 * `opfsWriteFile`/`opfsWriteFileBytes` (`OpfsInterop.kt`) are top-level `internal` functions with
 * no dependency-injection seam to substitute a literal test double, so — following this codebase's
 * existing precedent (`PlatformFileSystemDirtyTrackingIntegrationTest.kt`, which likewise exercises
 * the *real* wasmJs actual against real OPFS rather than a fake) — this test runs against the real
 * `PlatformFileSystem` actual and real OPFS. `writeFile`'s `opfsWriteInFlight[path] = scope.async
 * { ... }` genuinely does not run its body inline: `scope.async` posts the coroutine to
 * `Dispatchers.Default` and returns control to the caller first, so immediately after `writeFile`
 * returns — before this test ever suspends — there is a real, deterministic window in which the
 * tracked `Deferred` is provably non-null and still pending. No artificial slow-down is required to
 * observe this; it is the actual mechanism Task 1.7.1a added.
 *
 * NOTE: at the time these tests were authored, this sandboxed dev environment had no headless
 * Chrome / puppeteer cache available, so `./gradlew :kmp:wasmJsBrowserTest` could not actually be
 * executed here. See `PlatformFileSystemDirtyTrackingIntegrationTest.kt`'s doc comment for the same
 * caveat — verify with a real browser run before relying on these as a regression gate.
 */
class PlatformFileSystemOpfsWriteDurabilityTest {

    private fun freshGraphId(): String = "it-durability-${Random.nextInt(0, Int.MAX_VALUE)}"

    /** Polls [block] on a real (non-test-scheduler) dispatcher until true or the timeout elapses. */
    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun writeFile_should_TrackPendingDeferredImmediatelyThenResolveIt_When_CalledAgainstRealOpfs() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")
        val path = "/stelekit/$graphId/pages/Foo.md"

        // Regression check: writeFile is still synchronous / non-blocking — returns true
        // immediately, never suspends inline, and cache already reflects the write before any
        // suspension point is crossed.
        val result = fs.writeFile(path, "# Foo")
        assertTrue(result, "writeFile must still return true synchronously")
        assertEquals("# Foo", fs.readFile(path), "cache must already reflect the write synchronously")

        // The in-flight Deferred must be observable and NOT YET completed immediately after
        // writeFile() returns control to the caller — proving the OPFS write is tracked as a real
        // awaitable rather than being silently fire-and-forgotten.
        val deferred = fs.opfsWriteDeferredFor(path)
        assertNotNull(deferred, "expected an in-flight Deferred for '$path' immediately after writeFile()")
        assertFalse(deferred.isCompleted, "OPFS write must not have completed synchronously/inline")

        // Once the real OPFS write actually lands, the Deferred resolves and self-clears from the
        // tracking map — the edit was not lost or forgotten during the wait.
        deferred.await()
        assertTrue(deferred.isCompleted)
        awaitCondition { fs.opfsWriteDeferredFor(path) == null }
        assertNull(fs.opfsWriteDeferredFor(path), "Deferred must self-clear from the map once resolved")
    }

    @Test
    fun writeFileBytes_should_TrackPendingDeferredImmediatelyThenResolveIt_When_CalledAgainstRealOpfs() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")
        val path = "/stelekit/$graphId/pages/Secret.md.stek"
        val data = byteArrayOf(1, 2, 3, 4)

        val result = fs.writeFileBytes(path, data)
        assertTrue(result, "writeFileBytes must still return true synchronously")
        assertTrue(
            fs.getContentBytes(path).contentEquals(data),
            "bytesCache must already reflect the write synchronously",
        )

        val deferred = fs.opfsWriteDeferredFor(path)
        assertNotNull(deferred, "expected an in-flight Deferred for '$path' immediately after writeFileBytes()")
        assertFalse(deferred.isCompleted, "OPFS write must not have completed synchronously/inline")

        deferred.await()
        assertTrue(deferred.isCompleted)
        awaitCondition { fs.opfsWriteDeferredFor(path) == null }
        assertNull(fs.opfsWriteDeferredFor(path), "Deferred must self-clear from the map once resolved")
    }

    @Test
    fun opfsWriteDeferredFor_should_ReturnNull_When_PathWasNeverWrittenThisSession() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")

        assertNull(fs.opfsWriteDeferredFor("/stelekit/$graphId/pages/NeverWritten.md"))
    }
}
