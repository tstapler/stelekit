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
 * 2. [graphContentSourceWiring_usesEffectiveFileSystemAtAllFiveCallSites] — a static check of the
 *    source of App.kt, GraphContentActiveShell.kt, GraphContentGraphIoSetup.kt, and
 *    GraphContentViewModelSetup.kt. The five effectiveFileSystem call sites currently live in the
 *    latter three (GraphContentActiveShell.kt is scanned defensively — it's `GraphContent`'s own
 *    render-tree extraction, so a future edit moving one of the sites into it is exactly the
 *    regression this test exists to catch) — fails immediately if any of them regress to raw `fileSystem`,
 *    closing the gap the behavioral test alone can't (it only exercises the
 *    `viewModel`/`graphLoader` sites, not `sidecarManager`/`imageSidecarManager`/
 *    `imageImportService`). Each file's source is read via a classpath resource first — bundled
 *    under Bazel by `//kmp/src/jvmTest/kotlin:graph_content_wiring_test_sources_as_resources`,
 *    the same idiom as `MigrationRunnerSchemaSyncTest`'s `SteleDatabase.sq` resource, since App.kt
 *    et al. are only compiled into `jvm_main_lib` there and never staged as readable files at
 *    Bazel test runtime — falling back to the `stelekit.ui.dir` Gradle system property (set in
 *    `build.gradle.kts`'s `jvmTest` task) for `./gradlew jvmTest`. If `GraphContent` is split
 *    across files again, add the new file to both the Bazel filegroup/resource targets and this
 *    test's file list.
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

    /**
     * `isFullyLoaded` and `ensureTodayJournal()`'s auto-created page are independent, unawaited
     * `scope.launch {}`es (see `StelekitViewModel.loadGraph`'s `onPhase1Complete`/`onFullyLoaded`)
     * — `isFullyLoaded` flipping true is not a guarantee the journal write has landed yet. A
     * one-shot read right after [waitForFullyLoaded] is therefore flaky under load (observed:
     * ~1-in-5 under `bazel test --runs_per_test=5`, isolated, no other suite contention). Poll
     * until the count reaches [expectedAtLeast] or the timeout elapses, mirroring
     * [waitForFullyLoaded]'s own busy-poll style, instead of loosening the assertion itself.
     */
    private fun waitForPageCount(repos: RepositorySet, expectedAtLeast: Int, timeoutMillis: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var count = 0
        while (System.currentTimeMillis() < deadline) {
            count = runBlocking { repos.pageRepository.getAllPagesSnapshot() }.getOrNull()?.size ?: 0
            if (count >= expectedAtLeast) return count
            Thread.sleep(50)
        }
        return count
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

        val demoPageCount = waitForPageCount(demoRepos, expectedAtLeast = 2)
        assertTrue(
            "expected DemoFileSystem to import real demo content (many pages), found $demoPageCount",
            demoPageCount > 1,
        )

        // Reproduces the bug: raw fileSystem is empty at "/demo" — only ensureTodayJournal's
        // auto-created page ends up in the DB.
        val buggyRepos = newDemoRepositorySet()
        val buggyViewModel = buildViewModel(FakeFileSystem(), buggyRepos)
        buggyViewModel.loadGraph("/demo")
        waitForFullyLoaded(buggyViewModel)

        assertEquals(1, waitForPageCount(buggyRepos, expectedAtLeast = 1))
    }

    @Test
    fun graphContentSourceWiring_usesEffectiveFileSystemAtAllFiveCallSites() {
        val wiringFiles = listOf(
            "App.kt",
            "GraphContentActiveShell.kt",
            "GraphContentGraphIoSetup.kt",
            "GraphContentViewModelSetup.kt",
        )
        val source = wiringFiles.joinToString("\n") { readWiringSource(it) }

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
            source.contains("dev.stapler.stelekit.db.sidecar.ImageSidecarIndexer(\n        fileSystem = effectiveFileSystem,"),
        )
        assertTrue(
            "StelekitViewModelDependencies (the viewModel remember block) must use effectiveFileSystem",
            source.contains("StelekitViewModelDependencies(\n        fileSystem = effectiveFileSystem,"),
        )
    }

    /**
     * Classpath resource first (Bazel: bundled by `:graph_content_wiring_test_sources_as_resources`
     * from `//kmp/src/commonMain/kotlin:graph_content_wiring_test_sources`), falling back to the
     * `stelekit.ui.dir` Gradle system property (`./gradlew jvmTest`) — same two-path idiom as
     * `MigrationRunnerSchemaSyncTest`'s `SteleDatabase.sq` lookup.
     */
    private fun readWiringSource(fileName: String): String =
        javaClass.classLoader.getResourceAsStream(fileName)?.bufferedReader()?.readText()
            ?: run {
                val dirPath = System.getProperty("stelekit.ui.dir")
                    ?: error(
                        "Could not locate $fileName: no classpath resource of that name (Bazel's " +
                            "graph_content_wiring_test_sources_as_resources target) and " +
                            "stelekit.ui.dir system property not set — check build.gradle.kts jvmTest config"
                    )
                File(dirPath, fileName).readText()
            }
}
