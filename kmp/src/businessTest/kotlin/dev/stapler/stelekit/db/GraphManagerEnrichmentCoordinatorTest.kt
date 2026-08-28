// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.db

import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Regression coverage for `GraphManager.getOrCreateEnrichmentCoordinator()` (Epic 1.2,
 * project_plans/stelekit-capture-auto-enrich, AC #8). Uses a real `GraphManager` against an
 * `IN_MEMORY` backend (mirrors `GraphManagerAddGraphTest`'s setup), and reflectively seeds the
 * private `coordinatorFor` cache field to deterministically exercise the failure-eviction and
 * different-graph-not-blocked paths without needing an artificially slow/broken production
 * dependency.
 */
class GraphManagerEnrichmentCoordinatorTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
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

    private fun newGraphManager() = GraphManager(
        platformSettings = StubSettings(),
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    /** Reads the private `coordinatorFor` cache field via reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun coordinatorForField(graphManager: GraphManager) =
        GraphManager::class.java.getDeclaredField("coordinatorFor").apply { isAccessible = true }

    private fun seedCoordinatorFor(
        graphManager: GraphManager,
        graphId: GraphId,
        deferred: Deferred<CaptureEnrichmentCoordinator>,
    ) {
        coordinatorForField(graphManager).set(graphManager, graphId to deferred)
    }

    @Test
    fun getOrCreateEnrichmentCoordinator_twoConcurrentCallsSameGraph_returnSameInstanceBuiltOnce() = runTest {
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-concurrent")

        // Launched from the test's own scope before either is awaited, per Story 1.2.2/Task 1.2.2a.
        val first = async { graphManager.getOrCreateEnrichmentCoordinator() }
        val second = async { graphManager.getOrCreateEnrichmentCoordinator() }

        val firstResult = first.await()
        val secondResult = second.await()

        assertNotNull(firstResult)
        assertNotNull(secondResult)
        assertSame(firstResult, secondResult, "Two concurrent callers for the same graph must share one coordinator")
        // Only one PageNameIndex was constructed — if two had been built, these references
        // (each PageNameIndex is a fresh instance per CaptureEnrichmentCoordinator construction)
        // would differ.
        assertSame(firstResult.pageNameIndex, secondResult.pageNameIndex)
    }

    @Test
    fun getOrCreateEnrichmentCoordinator_constructionThrows_evictsFailedEntryForRetry() = runTest {
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-failure")
        val graphId = graphManager.getActiveGraphId()
        assertNotNull(graphId)

        val failedDeferred = CompletableDeferred<CaptureEnrichmentCoordinator>()
        failedDeferred.completeExceptionally(IllegalStateException("boom"))
        seedCoordinatorFor(graphManager, graphId, failedDeferred)

        assertFailsWith<IllegalStateException> {
            graphManager.getOrCreateEnrichmentCoordinator()
        }

        // The failed entry must have been evicted — the next call attempts a fresh construction
        // instead of re-awaiting (and rethrowing) the same failed Deferred forever.
        val retryResult = graphManager.getOrCreateEnrichmentCoordinator()
        assertNotNull(retryResult, "A call after a construction failure must retry, not replay the failure")
    }

    @Test
    fun getOrCreateEnrichmentCoordinator_concurrentDifferentGraphs_g2NotBlockedByG1InFlightConstruction() = runTest {
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-g1")
        val g1Id = graphManager.getActiveGraphId()
        assertNotNull(g1Id)

        // Seed a Deferred for g1 that never completes, simulating a construction stuck in flight.
        val stuckDeferred = CompletableDeferred<CaptureEnrichmentCoordinator>()
        seedCoordinatorFor(graphManager, g1Id, stuckDeferred)

        // A caller awaiting g1's (stuck) coordinator — launched off the test's structured scope
        // so it doesn't block runTest from completing; cancelled explicitly at the end.
        val stuckCaller = CoroutineScope(Dispatchers.Default).launch {
            graphManager.getOrCreateEnrichmentCoordinator()
        }

        try {
            // Switch the active graph to g2 while g1's construction is (per the seeded stuck
            // Deferred) still in flight.
            graphManager.openGraph("/test/graph-g2")
            val g2Id = graphManager.getActiveGraphId()
            assertNotNull(g2Id)

            // If coordinatorMutex were held across await() for g1, this call would hang since
            // the mutex would still be (incorrectly) contended. It must complete promptly.
            // GraphManager's internal work runs on the real Dispatchers.Default (not the
            // TestCoroutineScheduler's virtual clock), so the timeout itself must resolve
            // against real time too — withContext(Dispatchers.Default) switches the ambient
            // delay mechanism off the test scheduler for the withTimeout below (kotlinx-coroutines-test
            // would otherwise auto-skip virtual time and fire the timeout immediately, even
            // though the real background work completes near-instantly).
            val g2Result = withContext(Dispatchers.Default) {
                withTimeout(5_000) { graphManager.getOrCreateEnrichmentCoordinator() }
            }
            assertNotNull(g2Result)
        } finally {
            stuckCaller.cancel()
        }
    }
}
