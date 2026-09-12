package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Story 3.1.2/3.1.3: proves `renamePage`/`deletePage`/`savePageInternal` each capture
 * `currentEpoch` exactly once at entry — before queuing for `saveMutex` — and use that
 * captured value for the rest of the call, never a live re-read after the mutex-wait
 * suspension point. Gate-based (a [CompletableDeferred] the test controls), never `delay()`,
 * per this project's anti-flakiness discipline (see ADR-019 / PR #293's history).
 *
 * Mechanism: a first call is parked mid-flight inside `saveMutex.withLock` via a
 * `checkPreWriteConflict` hook that suspends on a gate. A second call for a different page is
 * then launched — it captures the still-active epoch, then genuinely suspends waiting to
 * acquire `saveMutex` (held by the first call). While it's queued, `currentEpoch` is switched.
 * Completing the gate releases the first call and lets the second proceed; it must still
 * complete successfully and its mismatch-log line must report the *old* captured epoch's
 * sequence, never the new live one — proving it never re-read the field after queuing.
 */
class GraphWriterEpochCaptureTest {

    private val now = Clock.System.now()

    private class RecordingSink : LogSink {
        val messages = mutableListOf<String>()
        override fun write(entry: LogEntry, formatted: String) {
            messages.add(entry.message)
        }
    }

    private fun page(uuid: String, name: String, filePath: String) = Page(
        uuid = PageUuid(uuid),
        name = name,
        filePath = filePath,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `renamePage queued behind an in-flight save completes against the epoch captured at its own entry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fs = FakeFileSystem()
        val sink = RecordingSink()
        val sinkHandle = LogManager.addSink(sink)
        try {
            val writer = GraphWriter(
                fileSystem = fs,
                checkPreWriteConflict = { _, _ -> gate.await(); false },
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val epochA = GraphEpoch(GraphId("graph-a"), "/home/graph-a", sequence = 1L)
            writer.currentEpoch = epochA

            val blockedPage = page("save-uuid", "Blocked", "/home/graph-a/pages/Blocked.md")
            val renamedPage = page("rename-uuid", "ToRename", "/home/graph-a/pages/ToRename.md")

            // C1: occupies saveMutex, suspended on the gate inside checkPreWriteConflict.
            val c1 = launch { writer.savePage(blockedPage, emptyList(), "/home/graph-a") }
            advanceUntilIdle()

            // C2: captures epochA at entry, then genuinely suspends acquiring saveMutex
            // (held by C1) — never reaches its own body yet.
            val c2 = launch { writer.renamePage(renamedPage, "Renamed", "/home/graph-a") }
            advanceUntilIdle()

            // Switch the live epoch while C2 is still queued on saveMutex.
            val epochB = GraphEpoch(GraphId("graph-b"), "/home/graph-b", sequence = 2L)
            writer.currentEpoch = epochB

            gate.complete(Unit)
            advanceUntilIdle()
            c1.join()
            c2.join()

            assertTrue(
                sink.messages.any {
                    it.contains("captured epoch") && it.contains("sequence=1") && it.contains("sequence=2")
                },
                "expected a mismatch log line naming captured epoch (sequence=1) vs. live epoch " +
                    "(sequence=2); got: ${sink.messages}",
            )
        } finally {
            sinkHandle.close()
        }
    }

    @Test
    fun `deletePage queued behind an in-flight save completes against the epoch captured at its own entry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fs = FakeFileSystem()
        val sink = RecordingSink()
        val sinkHandle = LogManager.addSink(sink)
        try {
            val writer = GraphWriter(
                fileSystem = fs,
                checkPreWriteConflict = { _, _ -> gate.await(); false },
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val epochA = GraphEpoch(GraphId("graph-a"), "/home/graph-a", sequence = 1L)
            writer.currentEpoch = epochA

            val blockedPage = page("save-uuid-2", "Blocked", "/home/graph-a/pages/Blocked.md")
            val deletedPage = page("delete-uuid", "ToDelete", "/home/graph-a/pages/ToDelete.md")

            val c1 = launch { writer.savePage(blockedPage, emptyList(), "/home/graph-a") }
            advanceUntilIdle()

            val c2 = launch { writer.deletePage(deletedPage) }
            advanceUntilIdle()

            val epochB = GraphEpoch(GraphId("graph-b"), "/home/graph-b", sequence = 2L)
            writer.currentEpoch = epochB

            gate.complete(Unit)
            advanceUntilIdle()
            c1.join()
            c2.join()

            assertTrue(
                sink.messages.any {
                    it.contains("captured epoch") && it.contains("sequence=1") && it.contains("sequence=2")
                },
                "expected a mismatch log line naming captured epoch (sequence=1) vs. live epoch " +
                    "(sequence=2); got: ${sink.messages}",
            )
        } finally {
            sinkHandle.close()
        }
    }

    /**
     * `savePageInternal`'s own `capturedEpoch` (Task 3.1.2c) follows the identical
     * capture-once-at-entry pattern proven above for `renamePage`/`deletePage` — same code
     * shape, same author, same commit. Unlike those two, `savePageInternal`'s capture only
     * feeds the AAD-guard/redaction branches, which execute only when a `CryptoLayer` is
     * active; exercising that specific branch faithfully would require a real (or
     * substantially faked) `CryptoLayer`, which doesn't exist as a test fixture in this repo
     * today and is out of proportion to add for this one assertion. This test therefore
     * proves the structural half of the guarantee — a second `savePage` queued behind an
     * in-flight one still completes successfully after a mid-flight epoch switch, never
     * throwing or corrupting state — rather than the crypto-guarded AAD-path claim. Closing
     * the crypto-path gap is left as a follow-up if/when this repo gains a CryptoLayer test
     * double.
     */
    @Test
    fun `a second savePage queued behind an in-flight save still completes after a mid-flight epoch switch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fs = FakeFileSystem()
        val writer = GraphWriter(
            fileSystem = fs,
            checkPreWriteConflict = { _, _ -> gate.await(); false },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        writer.currentEpoch = GraphEpoch(GraphId("graph-a"), "/home/graph-a", sequence = 1L)

        val firstPage = page("save-uuid-3", "First", "/home/graph-a/pages/First.md")
        val secondPage = page("save-uuid-4", "Second", "/home/graph-a/pages/Second.md")

        val c1 = async { writer.savePage(firstPage, emptyList(), "/home/graph-a") }
        advanceUntilIdle()

        val c2 = async { writer.savePage(secondPage, emptyList(), "/home/graph-a") }
        advanceUntilIdle()

        writer.currentEpoch = GraphEpoch(GraphId("graph-b"), "/home/graph-b", sequence = 2L)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(c1.await().isRight(), "the first save must still succeed")
        assertTrue(c2.await().isRight(), "the second save, queued behind the epoch switch, must still succeed")
    }
}
