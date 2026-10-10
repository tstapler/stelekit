@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.createGraphLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Integration coverage for the Story 1.3.2 gap found by the spec-compliance sweep:
 * [RepositoryFactory.kt's createGraphLoader][dev.stapler.stelekit.repository.createGraphLoader]
 * previously never threaded a real graphId into [GraphFileWatcher], so [MoveInProgressFlag] —
 * although individually correct — was unreachable dead code in production; the watcher's poll
 * loop always checked `isMoveInProgress(null)`.
 *
 * Unlike [MoveInProgressFlagTest] (which only exercises the flag object itself) and
 * [GraphFileWatcherTest]/[GraphLoaderWatcherTest] (which construct [GraphFileWatcher]/
 * [GraphLoader] directly), this test goes through the real production construction path —
 * [RepositoryFactoryImpl.createRepositorySet] + [dev.stapler.stelekit.repository.createGraphLoader] —
 * so a regression that drops the graphId wiring anywhere along that path fails this test.
 */
class MoveInProgressFlagWatcherIntegrationTest {

    private val graphId = "relocate-guard-integration-${System.currentTimeMillis()}"
    private val editedMarker = "Edited while relocate is in progress"

    private data class Fixture(val repoSet: RepositorySet, val loader: GraphLoader, val page: Page)

    @AfterTest
    fun clearFlag() {
        MoveInProgressFlag.setMoveInProgress(graphId, false)
    }

    private fun tempGraphDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "move_in_progress_test_${System.currentTimeMillis()}")
        dir.mkdirs()
        File(dir, "pages").mkdirs()
        File(dir, "journals").mkdirs()
        return dir
    }

    /**
     * Builds a [GraphLoader] via the real production path — [RepositoryFactoryImpl] +
     * [createGraphLoader] — loads [graphDir] (which also starts the real watcher poll loop via
     * [GraphLoader.loadGraph]), and returns the seed page it discovers.
     */
    private suspend fun buildFixture(graphDir: File, fileSystem: PlatformFileSystem): Fixture {
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val repoSet = factory.createRepositorySet(GraphBackend.IN_MEMORY)
        // Fast poll so the test doesn't wait out the production 5s interval.
        val loader = repoSet.createGraphLoader(fileSystem, graphId = graphId, watcherPollIntervalMs = 100L)

        File(graphDir, "pages/Watched.md").writeText("- Original content\n")
        loader.loadGraph(graphDir.absolutePath) {}

        val page = repoSet.pageRepository.getAllPagesSnapshot().getOrNull()
            ?.firstOrNull { it.name.contains("Watched", ignoreCase = true) }
        assertNotNull(page, "Seed page should have loaded")
        return Fixture(repoSet, loader, page)
    }

    private suspend fun blockContents(fixture: Fixture): List<String> =
        fixture.repoSet.blockRepository.getBlocksForPage(fixture.page.uuid).first().getOrNull()
            .orEmpty().map { it.content }

    private suspend fun awaitMarkerReload(fixture: Fixture) = withTimeout(3_000L) {
        while (blockContents(fixture).none { it.contains(editedMarker) }) {
            delay(50)
        }
    }

    @Test
    fun watcher_built_via_RepositoryFactory_skips_rescan_while_MoveInProgressFlag_is_set_for_its_graphId() = runBlocking {
        val graphDir = tempGraphDir()
        try {
            val fixture = buildFixture(graphDir, PlatformFileSystem().also { it.registerGraphRoot(graphDir.absolutePath) })

            // Mark this graph as mid-relocate BEFORE the conflicting external edit lands.
            MoveInProgressFlag.setMoveInProgress(graphId, true)
            delay(50)
            File(graphDir, "pages/Watched.md").writeText("- $editedMarker\n")

            // Give the 100ms-interval watcher several real poll cycles' worth of time to
            // (incorrectly) pick this up if the graphId guard were not actually wired through.
            delay(800L)
            assertTrue(
                blockContents(fixture).none { it.contains(editedMarker) },
                "GraphFileWatcher must skip its rescan while MoveInProgressFlag is set for the " +
                    "SAME graphId it was constructed with via RepositoryFactory — got: ${blockContents(fixture)}",
            )

            // Clearing the flag restores normal behavior — proves the earlier silence was the
            // guard kicking in, not e.g. the watcher having died or the write not landing.
            MoveInProgressFlag.setMoveInProgress(graphId, false)
            awaitMarkerReload(fixture)
        } finally {
            graphDir.deleteRecursively()
        }
    }
}
