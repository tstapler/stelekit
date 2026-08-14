// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — see HostDirectoryTestFixtures.kt for the
// makeWritableHostRoot/makeThrowingWritableHostRoot builders and writableRoot* accessors this file
// uses (Task 4.5.1a-e).

/**
 * Epic 4.1-4.4 (Story 4.5.1): dedicated coverage of the write-through queue's coalescing scheduler
 * ([HostDirectorySync.scheduleHostWriteThrough]), its flush logic
 * ([HostDirectorySync.flushHostWrite] — freshness check, proactive permission check, paranoid-mode
 * bytes, actual write), and failure surfacing. Constructed against [HostDirectorySync] directly
 * with a [FakeCacheAccess] (Task 1.6.1c's established pattern), using [makeWritableHostRoot]'s
 * flat (top-level-files-only) fake `FileSystemDirectoryHandle` for [HostDirectorySync.hostDirHandle]
 * — a different, write-capable surface than [HostDirectoryTestFixtures.kt]'s read-only
 * `values()`-based reconciliation fixtures.
 *
 * `writeFile`'s one-line delegation call site (including the `hostDirHandle == null` regression)
 * is tested separately in `PlatformFileSystemHostSyncDelegationTest.kt`, per Story 4.5.1's own
 * scoping note.
 */
class HostDirectorySyncWriteThroughTest {

    private fun freshOpfsPath(): String = "/stelekit/wt-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newSync(
        opfsPath: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
        rootHandle: JsAny,
    ): HostDirectorySync {
        val graphId = opfsPath.substringAfterLast("/")
        val sync = HostDirectorySync(
            graphIdProvider = { graphId },
            cacheAccess = cacheAccess,
            scope = scope,
        )
        sync.hostDirHandle = rootHandle
        sync.hostGraphOpfsPath = opfsPath
        return sync
    }

    /** Polls [block] on a real (non-test-scheduler) dispatcher until true or the timeout elapses —
     * mirrors PlatformFileSystemOpfsWriteDurabilityTest.kt's helper, needed here because
     * scheduleHostWriteThrough launches onto a real, independently-dispatched [CoroutineScope]
     * rather than suspending inline. */
    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    // ── Story 4.1.1: scheduleHostWriteThrough — single write + coalescing (Task 4.5.1a) ────────

    @Test
    fun scheduleHostWriteThrough_should_FlushExactlyOnce_When_CalledOnceForAPathWithHostDirHandleSet() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("hello"))

        awaitCondition { writableRootCreateWritableCallCount(root) >= 1 }
        assertEquals(1, writableRootCreateWritableCallCount(root))
        assertEquals("hello", writableRootGetContent(root, "Foo.md"))
        awaitCondition { "Foo.md" !in sync.hostWritePending }
        assertFalse("Foo.md" in sync.hostWritePending, "successful flush must dequeue")

        testScope.cancel()
    }

    @Test
    fun scheduleHostWriteThrough_should_CollapseToOneWriteOfLatestContent_When_CalledTwiceForSamePathBeforeFirstFlushCompletes() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        // Rapid succession, synchronously back-to-back — before either flush has a chance to run.
        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("v1"))
        // scheduleHostWriteThrough's map/queue updates happen asynchronously inside its own
        // scope.launch — awaiting the returned completion Deferred (rather than polling
        // hostWritePending's absence, which is racy: the map is empty both *before* the launch
        // has run and *after* a successful flush) is the race-free way to know the flush ran.
        val completion = sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("v2"))
        completion.await()
        assertEquals("v2", writableRootGetContent(root, "Foo.md"), "must reflect the latest content, not v1")
        assertEquals(1, writableRootCreateWritableCallCount(root), "exactly one host write, not two separate writes")

        testScope.cancel()
    }

    // ── Story 4.2.1: freshness check (Task 4.5.1b) ──────────────────────────────────────────────

    @Test
    fun flushHostWrite_should_WritePendingContentAndDequeue_When_HostHashMatchesLastKnownHash() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Foo.md", "original content")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)
        sync.hostContentHashes["$opfsPath/Foo.md"] = "original content".hashCode()

        // See CollapseToOneWriteOfLatestContent's comment: await the returned completion Deferred,
        // not hostWritePending's absence, which is racy against scheduleHostWriteThrough's own
        // async scope.launch not having run yet.
        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("browser edit")).await()

        assertEquals("browser edit", writableRootGetContent(root, "Foo.md"))
        assertEquals("browser edit".hashCode(), sync.hostContentHashes["$opfsPath/Foo.md"])

        testScope.cancel()
    }

    @Test
    fun flushHostWrite_should_RouteThroughOnHostConflictInsteadOfOverwriting_When_HostHashMismatchesLastKnownHash() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Foo.md", "external edit")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)
        // Stale baseline — an external tool wrote "external edit" after this hash was recorded.
        sync.hostContentHashes["$opfsPath/Foo.md"] = "original content".hashCode()

        var conflictPath: String? = null
        var conflictContent: String? = null
        sync.onHostConflict = { path, content -> conflictPath = path.value; conflictContent = content }

        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("browser edit"))

        awaitCondition { conflictPath != null }
        assertEquals("$opfsPath/Foo.md", conflictPath)
        assertEquals("external edit", conflictContent)
        // Never overwritten with the browser's pending content.
        assertEquals("external edit", writableRootGetContent(root, "Foo.md"))
        assertEquals(0, writableRootCreateWritableCallCount(root), "must never write on a conflict")

        testScope.cancel()
    }

    // ── Story 4.2.2: paranoid-mode bytes (Task 4.5.1c) ──────────────────────────────────────────

    @Test
    fun flushHostWrite_should_SkipHashGuardAndUseWritableWriteBuffer_When_PayloadIsBytesForMdStekPath() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)
        // Deliberately no hostContentHashes baseline — bytes payloads never consult it anyway.

        // See CollapseToOneWriteOfLatestContent's comment: await the returned completion Deferred,
        // not hostWritePending's absence, which is racy against scheduleHostWriteThrough's own
        // async scope.launch not having run yet.
        sync.scheduleHostWriteThrough("$opfsPath/Secret.md.stek", HostWritePayload.Bytes(byteArrayOf(1, 2, 3, 4))).await()

        assertTrue(writableRootHasBuffer(root, "Secret.md.stek"), "must write via writableWriteBuffer, not writableWrite")
        assertEquals(1, writableRootCreateWritableCallCount(root))

        testScope.cancel()
    }

    // ── Story 4.2.3: proactive permission check (Task 4.5.1e) ──────────────────────────────────

    @Test
    fun flushHostWrite_should_NeverCallCreateWritable_When_ProactivePermissionCheckIsNotGranted() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot(permission = "denied")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        var failureCount = 0
        sync.onHostWriteFailed = { failureCount++ }

        sync.scheduleHostWriteThrough("$opfsPath/A.md", HostWritePayload.Text("a"))
        sync.scheduleHostWriteThrough("$opfsPath/B.md", HostWritePayload.Text("b"))
        sync.scheduleHostWriteThrough("$opfsPath/C.md", HostWritePayload.Text("c"))

        awaitCondition { failureCount >= 3 }
        assertEquals(3, failureCount)
        assertEquals(0, writableRootCreateWritableCallCount(root), "no write may ever be attempted")
        assertEquals(HostAccessState.Denied, sync.hostAccessStateFlow.value)
        assertTrue("A.md" in sync.hostWritePending)
        assertTrue("B.md" in sync.hostWritePending)
        assertTrue("C.md" in sync.hostWritePending)

        testScope.cancel()
    }

    @Test
    fun flushHostWrite_should_ProceedToWriteNormally_When_ProactivePermissionCheckIsGranted() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot(permission = "granted")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        // See CollapseToOneWriteOfLatestContent's comment: await the returned completion Deferred,
        // not hostWritePending's absence, which is racy against scheduleHostWriteThrough's own
        // async scope.launch not having run yet.
        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("hi")).await()

        assertEquals("hi", writableRootGetContent(root, "Foo.md"))
        assertEquals(1, writableRootCreateWritableCallCount(root))

        testScope.cancel()
    }

    // ── Story 4.4.1: failure surfacing (Task 4.5.1d) ────────────────────────────────────────────

    @Test
    fun flushHostWrite_should_KeepPathQueuedAndSetDisconnected_When_ThrowsNotFoundError() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeThrowingWritableHostRoot("NotFoundError: the requested file could not be found")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        var failure: DomainError.FileSystemError.WriteFailed? = null
        sync.onHostWriteFailed = { failure = it }

        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("hi"))

        awaitCondition { failure != null }
        assertTrue("Foo.md" in sync.hostWritePending, "must stay queued for retry")
        assertEquals(HostAccessState.Disconnected("NotFoundError: the requested file could not be found"), sync.hostAccessStateFlow.value)
        assertEquals("Foo.md", failure?.path)

        testScope.cancel()
    }

    @Test
    fun flushHostWrite_should_TransitionToPromptNeededOrDenied_When_ThrowsNotAllowedErrorAndPermissionRequeryConfirmsLoss() = runTest {
        val opfsPath = freshOpfsPath()
        // First queryPermission call (the proactive check) resolves "granted" so the write is
        // actually attempted; getFileHandle then throws NotAllowedError; the catch block's
        // re-query is the SECOND queryPermission call, which resolves "denied" — permission was
        // revoked mid-write.
        val root = makeThrowingWritableHostRoot(
            "NotAllowedError: permission revoked mid-write",
            permission = "granted-then-denied",
        )
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        var failure: DomainError.FileSystemError.WriteFailed? = null
        sync.onHostWriteFailed = { failure = it }

        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("hi"))

        awaitCondition { failure != null }
        assertTrue("Foo.md" in sync.hostWritePending, "must stay queued for retry")
        assertTrue(
            sync.hostAccessStateFlow.value == HostAccessState.PromptNeeded || sync.hostAccessStateFlow.value == HostAccessState.Denied,
            "must never be left at Granted once the re-query confirms the grant is gone — was ${sync.hostAccessStateFlow.value}",
        )
        assertFalse(sync.hostWriteStuckFlow.value, "a confirmed permission loss is not the transient-failure SyncDegraded case")

        testScope.cancel()
    }

    // ── BUG-2 regression: stuck-write recovery (retryStuckHostWrites) ──────────────────────────

    @Test
    fun retryStuckHostWrites_should_EventuallyFlushAndDequeue_When_FirstAttemptFailsTransientlyAndSecondCallSucceeds() = runTest {
        val opfsPath = freshOpfsPath()
        // A transient (non-NotFoundError) failure with permission still "granted" on re-query is
        // exactly handleFlushFailure's SyncDegraded branch: _hostWriteStuckFlow is set true and
        // the entry is deliberately left queued. Before this fix, nothing ever re-attempted it.
        val root = makeFlakyWritableHostRoot(failuresBeforeSuccess = 1, errorMessage = "QuotaExceededError: disk quota blip")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        var failureCount = 0
        sync.onHostWriteFailed = { failureCount++ }

        sync.scheduleHostWriteThrough("$opfsPath/Foo.md", HostWritePayload.Text("hello"))

        awaitCondition { failureCount >= 1 }
        assertTrue("Foo.md" in sync.hostWritePending, "must stay queued after the transient failure")
        assertTrue(sync.hostWriteStuckFlow.value, "transient failure with permission still granted must flip SyncDegraded")

        // Simulates the next startHostDirectoryPolling tick invoking the retry.
        sync.retryStuckHostWrites()

        awaitCondition { "Foo.md" !in sync.hostWritePending }
        assertFalse("Foo.md" in sync.hostWritePending, "retry must dequeue once the underlying failure clears")
        assertEquals("hello", writableRootGetContent(root, "Foo.md"))
        assertEquals(2, flakyRootAttemptCount(root), "exactly one failed attempt then one successful retry")
        assertFalse(sync.hostWriteStuckFlow.value, "SyncDegraded must clear once the retry succeeds")

        testScope.cancel()
    }

    @Test
    fun pollHostDirectoryOnce_should_AutomaticallyRepollAndUpdateCache_When_ExternalPollWasSuppressedDuringInFlightWrite() = runTest {
        // Regression for the bug where an external edit landing on a path while its own write
        // is in flight was silently dropped: pollHostDirectoryOnce's suppression guard skips
        // hostModTimes/hostFileSizes/cache updates for any hostWriteInFlight path, and without
        // repollIfSuppressedDuringFlush firing once the write finishes, nothing else would ever
        // revisit that path. This drives the real race: schedule a write that fails once (so it's
        // pending but not yet in flight), start retryStuckHostWrites in the background, wait for
        // its flush to actually claim hostWriteInFlight and block mid-attempt, manually poll to
        // prove the suppression guard skips the path, then release the flush and assert the cache
        // becomes current WITHOUT the test itself calling pollHostDirectoryOnce again.
        val opfsPath = freshOpfsPath()
        val root = makeWritableEnumerableHostRoot(failuresBeforeSuccess = 1, errorMessage = "Error: transient I/O blip")
        val cacheAccess = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, cacheAccess, testScope, root)

        var failureCount = 0
        sync.onHostWriteFailed = { failureCount++ }

        val path = "$opfsPath/Foo.md"
        sync.scheduleHostWriteThrough(path, HostWritePayload.Text("v1"))
        awaitCondition { failureCount >= 1 }

        testScope.launch { sync.retryStuckHostWrites() }
        awaitCondition { writableEnumerableRootAttemptCount(root) >= 2 }

        writableEnumerableRootSetContent(root, "Foo.md", "external edit", 999)

        sync.pollHostDirectoryOnce(root, opfsPath)
        assertNull(cacheAccess.get(path), "poll must suppress a path whose write is in flight, not read stale/half-written content")

        openWritableEnumerableRootGate(root)

        awaitCondition { "Foo.md" !in sync.hostWritePending }
        awaitCondition { cacheAccess.get(path) != null }
        assertEquals("v1", cacheAccess.get(path), "repoll after flush completion must reflect the file's current on-disk content")

        testScope.cancel()
    }

    @Test
    fun hostDirectorySync_should_ConstructWithNoHostDirHandle_When_NeverConnected() = runTest {
        // Regression guard: HostDirectorySync itself never assumes a non-null hostDirHandle at
        // construction. flushHostWrite's own hostDirHandle == null early-return is exercised
        // indirectly by every test above's setup requiring an explicit handle; this asserts the
        // field's true default.
        val cacheAccess = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = HostDirectorySync(graphIdProvider = { "g" }, cacheAccess = cacheAccess, scope = testScope)
        assertNull(sync.hostDirHandle)
        testScope.cancel()
    }
}
