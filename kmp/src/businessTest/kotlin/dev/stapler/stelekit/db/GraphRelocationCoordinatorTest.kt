// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Task 3.1.5g — full happy-path relocate state sequence (REQ-3, AC34, validation.md
 * `relocate_should_EmitQuiescingCopyingVerifyingSummaryInOrder_When_HappyPath`).
 */
class GraphRelocationCoordinatorTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should EmitQuiescingCopyingVerifyingSummaryInOrder When HappyPath`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        markdownFs.writeFileBytes("source/page2.md", "world".encodeToByteArray())

        val quiesce = FakeGraphMoveQuiesceStrategy()
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        // Quiescing must come before any Copying state, and the whole sequence ends in Summary.
        assertEquals(StorageMoveUiState.Quiescing, states.first())
        val quiescingIndex = states.indexOfFirst { it is StorageMoveUiState.Quiescing }
        val firstCopyingIndex = states.indexOfFirst { it is StorageMoveUiState.Copying }
        assertTrue(firstCopyingIndex > quiescingIndex, "Copying must follow Quiescing: $states")
        assertTrue(states.any { it is StorageMoveUiState.Verifying }, "expected a Verifying state: $states")
        assertEquals(StorageMoveUiState.Summary, states.last())

        // Driver reopened and confirmed (step 6) before Summary was emitted.
        val reopenedRepoSet = graphManager.activeRepositorySet.value
        assertNotNull(reopenedRepoSet)

        // Files landed at the destination; source is left untouched (AC34/Surface 9 default).
        assertTrue(markdownFs.fileExists("source/page1.md"))
        assertTrue(markdownFs.fileExists("source/page2.md"))
        assertTrue(markdownFs.fileExists("dest/page1.md"))
        assertTrue(markdownFs.fileExists("dest/page2.md"))

        // Step 8 cleanup ran exactly once.
        assertEquals(1, quiesce.releaseCalls.size)
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))

        // Step 7: onGraphLocationDetermined actually persisted through the reopened connection.
        graphManager.shutdown()
        val row = selectStorageLocationRow(graphId.value)
        assertEquals("DirectAccessFolder", row?.kind)
    }

    @Test
    fun `relocate should LeaveSourceUntouchedAfterSummary When UserHasNotChosenDeleteOldCopy`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())

        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy())
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        assertEquals(StorageMoveUiState.Summary, states.last())

        // No automatic/time-boxed deletion of the source absent an explicit cleanup call.
        assertTrue(markdownFs.fileExists("source/page1.md"), "source must remain until explicitly deleted")

        graphManager.shutdown()
    }

    /**
     * BLOCKER 1 regression test (PR #327 review): `GraphWriter`'s pending 500ms-debounced saves
     * must be flushed before `relocate()` does anything else — including before `quiesce()` is
     * called — so an edit made just before the user clicks "Relocate" can't be lost or split
     * between the old and new locations.
     */
    @Test
    fun `relocate should InvokeFlushPendingSavesBeforeQuiesce When Started`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/graph-flush-order-$runId")

        val quiesce = FakeGraphMoveQuiesceStrategy()
        var flushCallCount = 0
        var flushRanBeforeAnyQuiesceCall = false
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            quiesce,
            flushPendingSaves = {
                flushCallCount++
                flushRanBeforeAnyQuiesceCall = quiesce.quiesceCalls.isEmpty()
            },
        )

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        coordinator.relocate(operation).toList()

        assertEquals(1, flushCallCount)
        assertTrue(flushRanBeforeAnyQuiesceCall, "flushPendingSaves must run before quiesce() is called")
        assertEquals(1, quiesce.quiesceCalls.size)

        graphManager.shutdown()
    }
}

internal class StubSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

internal class StubGraphManagerFileSystem : FileSystem {
    override fun getDefaultGraphPath() = "/test"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = null
    override fun writeFile(path: String, content: String) = true
    override fun listFiles(path: String) = emptyList<String>()
    override fun listDirectories(path: String) = emptyList<String>()
    override fun fileExists(path: String) = false
    override fun directoryExists(path: String) = true
    override fun createDirectory(path: String) = true
    override fun deleteFile(path: String) = true
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
    override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
    override fun stopExternalChangeDetection() {}
}

/** Shared GraphManager test harness for `GraphRelocationCoordinator*Test` files (Story 3.1.5). */
internal fun newGraphManager(preFlightJob: kotlinx.coroutines.Deferred<Unit>? = null): GraphManager = GraphManager(
    platformSettings = StubSettings(),
    driverFactory = DriverFactory(),
    fileSystem = StubGraphManagerFileSystem(),
    defaultBackend = GraphBackend.SQLDELIGHT,
    preFlightJob = preFlightJob,
)

/** Reads `storage_locations` for [graphId] via a fresh connection to that graph's db file. */
internal fun selectStorageLocationRow(graphId: String): Storage_locations? {
    val url = DriverFactory().getDatabaseUrl(graphId)
    val driver = DriverFactory().createDriver(url)
    try {
        return SteleDatabase(driver).steleDatabaseQueries.selectStorageLocation(graphId).executeAsOneOrNull()
    } finally {
        driver.close()
    }
}
