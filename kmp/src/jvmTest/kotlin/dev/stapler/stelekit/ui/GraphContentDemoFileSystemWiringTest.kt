package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.createGraphLoader
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the bug where `GraphContent` (App.kt) loaded the demo graph against
 * the real (empty) filesystem instead of [DemoFileSystem]. `GraphContent` computes
 * `effectiveFileSystem = if (activeGraphInfo?.isDemo == true) DemoFileSystem() else fileSystem`
 * and must feed that value — not the raw `fileSystem` parameter — into the sidecar managers,
 * `ImageImportService`, `ImageSidecarIndexer`, and (most importantly) the `StelekitViewModel`
 * it builds. If any of those five call sites regress to raw `fileSystem`, the demo graph loads
 * against an empty on-disk path and only the auto-created "today's journal" page (from
 * `StelekitViewModel.loadGraph`'s unconditional `journalService.ensureTodayJournal()`) ends up
 * in the repository.
 *
 * Mounting `GraphContent`/`StelekitApp` end-to-end to catch this at runtime was attempted first
 * (per the task brief) but proved infeasible: `SkikoComposeUiTest.setContent {}` crashes with
 * `IllegalStateException: Unsupported concurrent change during composition` even for a bare
 * `StelekitApp` mount with no demo graph involved at all (verified with a throwaway scratch
 * test) — a pre-existing JVM Compose test-harness limitation caused by `GraphContent`'s real
 * production `viewModelScope` (`Dispatchers.Default`) racing the test's snapshot machinery, not
 * a symptom of this bug. Coverage is therefore split into two parts that together still fail on
 * any of the five call sites regressing:
 *
 * 1. [demoFileSystem_loadsRealDemoContent_rawFileSystem_loadsOnlyTodaysJournal] — behavioral,
 *    exercising the real production `StelekitViewModel` / `GraphLoader` / `DemoFileSystem`
 *    classes wired exactly the way `GraphContent` wires them (via
 *    `RepositorySet.createGraphLoader`, the same helper `GraphContent` calls), proving the
 *    actual mechanism: `DemoFileSystem` yields many pages, an empty raw filesystem yields
 *    exactly the one auto-created journal page.
 * 2. [graphContentSourceWiring_usesEffectiveFileSystemAtAllFiveCallSites] — a static check of
 *    App.kt's source (path injected via the `stelekit.appkt.file` Gradle system property) that
 *    fails immediately if any of the five call sites in `GraphContent` are reverted to raw
 *    `fileSystem`, closing the gap the behavioral test alone can't (it only exercises the
 *    `viewModel`/`graphLoader` sites, not `sidecarManager`/`imageSidecarManager`/`imageImportService`).
 */
class GraphContentDemoFileSystemWiringTest {

    private fun buildViewModel(
        fileSystemForLoad: FileSystem,
        repos: RepositorySet,
    ): StelekitViewModel {
        val graphLoader = repos.createGraphLoader(fileSystemForLoad)
        val graphWriter = GraphWriter(fileSystemForLoad)
        val scope = CoroutineScope(Dispatchers.Default)
        return StelekitViewModel(
            StelekitViewModelDependencies(
                pageRepository = repos.pageRepository,
                blockRepository = repos.blockRepository,
                searchRepository = repos.searchRepository,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                fileSystem = fileSystemForLoad,
                platformSettings = InMemorySettings(),
                scope = scope,
                journalService = repos.journalService,
                writeActor = repos.writeActor,
            )
        )
    }

    private fun waitForFullyLoaded(viewModel: StelekitViewModel, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && !viewModel.uiState.value.isFullyLoaded) {
            Thread.sleep(50)
        }
        assertTrue("graph did not finish loading within ${timeoutMillis}ms", viewModel.uiState.value.isFullyLoaded)
    }

    private fun newDemoRepositorySet(): RepositorySet {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = FakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        graphManager.addDemoGraph()
        graphManager.switchGraph(DEMO_GRAPH_ID)
        return runBlocking { graphManager.awaitPendingMigration() }
            ?: error("expected a RepositorySet for the demo graph")
    }

    @Test
    fun demoFileSystem_loadsRealDemoContent_rawFileSystem_loadsOnlyTodaysJournal() {
        // Correct wiring: effectiveFileSystem resolves to DemoFileSystem() for the demo graph.
        val demoRepos = newDemoRepositorySet()
        val demoViewModel = buildViewModel(DemoFileSystem(), demoRepos)
        demoViewModel.loadGraph("/demo")
        waitForFullyLoaded(demoViewModel)

        val demoPages = runBlocking { demoRepos.pageRepository.getAllPagesSnapshot() }.getOrNull()
        assertTrue(
            "expected DemoFileSystem to import real demo content (many pages), found ${demoPages?.size ?: 0}",
            (demoPages?.size ?: 0) > 1,
        )

        // Reproduces the bug: raw fileSystem is empty at "/demo" — only ensureTodayJournal's
        // auto-created page ends up in the DB.
        val buggyRepos = newDemoRepositorySet()
        val buggyViewModel = buildViewModel(FakeFileSystem(), buggyRepos)
        buggyViewModel.loadGraph("/demo")
        waitForFullyLoaded(buggyViewModel)

        val buggyPages = runBlocking { buggyRepos.pageRepository.getAllPagesSnapshot() }.getOrNull()
        assertEquals(1, buggyPages?.size ?: 0)
    }

    @Test
    fun graphContentSourceWiring_usesEffectiveFileSystemAtAllFiveCallSites() {
        val path = System.getProperty("stelekit.appkt.file")
            ?: error("stelekit.appkt.file system property not set — check build.gradle.kts jvmTest config")
        val source = File(path).readText()

        assertTrue(
            "sidecarManager must use effectiveFileSystem",
            source.contains("if (graphPath != null) SidecarManager(effectiveFileSystem, graphPath) else null"),
        )
        assertTrue(
            "imageSidecarManager must use effectiveFileSystem",
            source.contains("ImageSidecarManager(effectiveFileSystem) else null"),
        )
        assertTrue(
            "ImageImportService must use effectiveFileSystem",
            source.contains("dev.stapler.stelekit.db.ImageImportService(\n                fileSystem = effectiveFileSystem,"),
        )
        assertTrue(
            "ImageSidecarIndexer must use effectiveFileSystem",
            source.contains("dev.stapler.stelekit.db.sidecar.ImageSidecarIndexer(\n                    fileSystem = effectiveFileSystem,"),
        )
        assertTrue(
            "StelekitViewModelDependencies (the viewModel remember block) must use effectiveFileSystem",
            source.contains("StelekitViewModelDependencies(\n                fileSystem = effectiveFileSystem,"),
        )
    }
}
