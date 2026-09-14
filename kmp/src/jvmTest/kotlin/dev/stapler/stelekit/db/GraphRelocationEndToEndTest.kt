// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RefDatabase
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish

/**
 * Epic 5.3, Story 5.3.1 — end-to-end regression coverage closing requirements.md's Feasibility
 * Risk: "no existing regression test exercises a live storage-location migration for a graph
 * with real content." Every existing `GraphRelocationCoordinator*Test` (businessTest) exercises
 * the coordinator's state-machine/orchestration logic against a handful of files via
 * [FakeRelocationFileSystem]; this test instead drives the real [GraphRelocationCoordinator] +
 * [BulkCopyVerifier] + [AtomicFileRelocationStep] against a real, disk-backed, ~50-page
 * git-cloned graph, matching the evidentiary bar `LargeGraphWarmStartCrashTest` set for this
 * codebase's other crash/regression-prevention suites.
 *
 * Fixture choices, both driven by what a real JVM environment can/can't do:
 * - **Real JGit clone, not a fake.** `org.eclipse.jgit` is already on the `jvmTest` classpath
 *   (`JvmGitRepositoryTest`) and a local-filesystem clone of ~50 small files is fast (well under
 *   a second), so there is no reason to fake git history here the way `GitObjectContentVerifierTest`
 *   fakes pack-file bytes for a narrower, corruption-focused unit test.
 * - **`StorageLocation.DirectAccessFolder` stands in for `SafFolder`.** `SafFolder`'s `saf://`
 *   path convention (`BulkCopyVerifier.kt`'s `resolveRootPathOrNull`) is Android-only — a real
 *   `PlatformFileSystem` on the JVM has no SAF resolver and would reject it. Every existing
 *   `GraphRelocationCoordinator*Test` already uses `DirectAccessFolder` as the real-filesystem
 *   stand-in on this platform (see `GraphRelocationCoordinatorAppOwnedTest`); this test follows
 *   the same precedent instead of inventing a new one.
 * - **A test-local `newAppOwnedGraphPath()` implementation.** The real JVM `PlatformFileSystem`
 *   does not implement app-owned storage (`supportsAppOwnedStorage = false`, the interface
 *   default — Desktop has unrestricted filesystem access and gains nothing from a second,
 *   app-private mode). [RealAppOwnedFileSystem] below delegates every other operation to a real
 *   `PlatformFileSystem` (so the copy/verify/rename walk is 100% real disk I/O) and only supplies
 *   a fresh real directory for `newAppOwnedGraphPath()` — no production code changes.
 *
 * Page/block content parity (Task 5.3.1b) is checked independently of [GraphManager]'s own
 * SQLite-backed repository set: relocate never touches page/block rows (the SQLite DB lives in
 * its own per-graph, `devDataDir`-rooted location — see `RelocationCoordinatorTestSupport` — not
 * inside the markdown content root `copyIntoStagingThenRepoint` moves), so comparing
 * [GraphManager]'s DB before/after would trivially pass without ever reading the relocated files.
 * Instead this test independently re-parses the graph from the source path (before) and the
 * resolved destination path (after) via a throwaway [GraphLoader] + [InMemoryPageRepository] /
 * [InMemoryBlockRepository] pair each time — [getAllPagesSnapshot] is exactly the bounded-batch
 * one-shot method `PageRepository.kt`'s class doc names for this kind of whole-graph comparison.
 */
class GraphRelocationEndToEndTest {

    private companion object {
        const val PAGE_COUNT = 50
        const val PAGES_PER_COMMIT = 10
    }

    // Mirrors RelocationCoordinatorTestSupport's isolation precedent (Story 3.1.5's businessTest
    // suite) — without this, GraphManager's real SQLite files would collide with this
    // dev machine's actual app-data directory.
    private var originalDevDataDir: String? = null
    private lateinit var isolatedDataDir: File

    @BeforeTest
    fun setUpIsolatedDataDir() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        isolatedDataDir = createTempDirectory("stelekit_relocation_e2e_data_").toFile()
        System.setProperty("stelekit.devDataDir", isolatedDataDir.absolutePath)
    }

    @AfterTest
    fun tearDownIsolatedDataDir() {
        val original = originalDevDataDir
        if (original != null) {
            System.setProperty("stelekit.devDataDir", original)
        } else {
            System.clearProperty("stelekit.devDataDir")
        }
        isolatedDataDir.deleteRecursively()
    }

    @Test
    fun `relocate should PreservePageBlockAndGitObjectParity When RelocatingSynthetic50PageGitClonedGraph`() = runBlocking {
        val tempRoot = createTempDirectory("stelekit_relocation_e2e_").toFile()
        val appOwnedBaseDir = File(tempRoot, "appowned").apply { mkdirs() }
        val delegate = PlatformFileSystem()
        delegate.registerGraphRoot(tempRoot.absolutePath)
        val fileSystem = RealAppOwnedFileSystem(delegate, appOwnedBaseDir)

        val sourceDir = buildGitClonedGraphFixture(tempRoot)
        assertTrue(File(sourceDir, ".git").isDirectory, "fixture setup: source graph must be a real git clone")

        val pagesBefore = snapshotGraph(fileSystem, sourceDir.absolutePath)
        assertEquals(PAGE_COUNT, pagesBefore.size, "fixture setup: expected $PAGE_COUNT parsed pages before relocate")

        val graphManager = newGraphManager()
        graphManager.openGraph(sourceDir.absolutePath)
        val graphId = graphManager.getActiveGraphId()!!

        val coordinator = GraphRelocationCoordinator(graphManager, fileSystem, NoOpQuiesceStrategy)
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, sourceDir.absolutePath),
            destination = StorageLocation.AppOwned(graphId.value),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("relocate failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last(), "relocate must reach Summary: $states")

        val destinationRoot = graphManager.getGraphInfo(graphId)?.path
        assertTrue(
            destinationRoot != null && destinationRoot != sourceDir.absolutePath,
            "GraphInfo.path must be updated to a freshly-allocated app-owned path after relocate, was: $destinationRoot",
        )
        val destinationDir = File(destinationRoot)

        // Task 5.3.1b — page/block content parity.
        val pagesAfter = snapshotGraph(fileSystem, destinationDir.absolutePath)
        assertEquals(pagesBefore.size, pagesAfter.size, "page count must be identical before/after relocate")
        assertEquals(pagesBefore, pagesAfter, "page names + block content must be identical before/after relocate")

        // Task 5.3.1c — git ref/object-count parity, an end-to-end sanity check layered ON TOP
        // OF (not a substitute for) BulkCopyVerifier's per-file content-hash verification (Story
        // 3.1.1), which already ran, over every file BulkCopyVerifier's tree walk visited, as
        // part of copyAndVerifyStep above.
        val sourceGitDir = File(sourceDir, ".git")
        val destinationGitDir = File(destinationDir, ".git")
        assertTrue(
            destinationGitDir.isDirectory,
            "expected the relocated destination to contain a .git directory mirroring the source " +
                "git-cloned graph at $sourceGitDir; found nothing at $destinationGitDir. " +
                "destination contents: ${destinationDir.walkTopDown().map { it.relativeTo(destinationDir).path }.toList()}",
        )

        val (sourceRefCount, sourceCommitCount) = gitRefAndCommitCounts(sourceDir)
        val (destRefCount, destCommitCount) = gitRefAndCommitCounts(destinationDir)
        assertEquals(sourceRefCount, destRefCount, "git ref count must match after relocate")
        assertEquals(
            sourceCommitCount,
            destCommitCount,
            "git rev-list --all --count-equivalent commit count must match after relocate",
        )

        graphManager.shutdown()
    }

    /**
     * REQ-11 — consistent with `LargeGraphWarmStartCrashTest`'s ≤100-row-batch assertion style:
     * every [StorageMoveUiState.Copying] progress emission observed during a realistic-scale
     * relocate must report a batch no larger than [BulkCopyVerifier.COPY_BATCH_SIZE], the same
     * bound `BulkCopyVerifierTest`'s unit-level 8 030-file test asserts in isolation.
     */
    @Test
    fun `relocate should ReadDestinationInBoundedBatches When VerifyingSynthetic50PageGraph`() = runBlocking {
        val tempRoot = createTempDirectory("stelekit_relocation_e2e_batches_").toFile()
        val appOwnedBaseDir = File(tempRoot, "appowned").apply { mkdirs() }
        val delegate = PlatformFileSystem()
        delegate.registerGraphRoot(tempRoot.absolutePath)
        val fileSystem = RealAppOwnedFileSystem(delegate, appOwnedBaseDir)

        val sourceDir = buildGitClonedGraphFixture(tempRoot)

        val graphManager = newGraphManager()
        graphManager.openGraph(sourceDir.absolutePath)
        val graphId = graphManager.getActiveGraphId()!!

        val coordinator = GraphRelocationCoordinator(graphManager, fileSystem, NoOpQuiesceStrategy)
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, sourceDir.absolutePath),
            destination = StorageLocation.AppOwned(graphId.value),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("relocate failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last(), "relocate must reach Summary: $states")

        val copyingStates = states.filterIsInstance<StorageMoveUiState.Copying>()
        assertTrue(copyingStates.isNotEmpty(), "expected at least one Copying progress state: $states")
        copyingStates.forEach { copying ->
            assertTrue(
                copying.total <= BulkCopyVerifier.COPY_BATCH_SIZE,
                "each copy/verify batch must be bounded to COPY_BATCH_SIZE=${BulkCopyVerifier.COPY_BATCH_SIZE}; " +
                    "observed a batch of ${copying.total}: $states",
            )
        }

        graphManager.shutdown()
    }

    /**
     * Builds a real, git-cloned ~[PAGE_COUNT]-page graph under [tempRoot]: a bare "origin" repo,
     * seeded from a separate working clone across [PAGES_PER_COMMIT]-sized commits (real,
     * multi-commit git history), then cloned a second time into the returned directory — the
     * actual fixture `GraphRelocationCoordinator.relocate()` is exercised against below. Each
     * page cross-references the next in a 50-page ring via a `[[Page N]]` wikilink, matching this
     * codebase's own `LargeGraphWarmStartCrashTest` fixture convention.
     */
    private fun buildGitClonedGraphFixture(tempRoot: File): File {
        val originBare = File(tempRoot, "origin.git")
        Git.init().setDirectory(originBare).setBare(true).call().close()

        val seedDir = File(tempRoot, "seed")
        Git.init().setDirectory(seedDir).call().use { seedGit ->
            setTestIdentity(seedGit)
            seedGit.remoteAdd().setName("origin").setUri(URIish(originBare.toURI().toString())).call()

            val branch = seedGit.repository.branch
            val pagesDir = File(seedDir, "pages").apply { mkdirs() }

            var batchStart = 1
            while (batchStart <= PAGE_COUNT) {
                val batchEnd = minOf(batchStart + PAGES_PER_COMMIT - 1, PAGE_COUNT)
                for (n in batchStart..batchEnd) {
                    File(pagesDir, "Page $n.md").writeText(pageContent(n))
                }
                seedGit.add().addFilepattern("pages").call()
                seedGit.commit().setMessage("Add pages $batchStart-$batchEnd").call()
                batchStart = batchEnd + 1
            }

            seedGit.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .call()
        }

        val sourceDir = File(tempRoot, "source-graph")
        Git.cloneRepository()
            .setURI(originBare.toURI().toString())
            .setDirectory(sourceDir)
            .call()
            .close()

        return sourceDir
    }

    private fun setTestIdentity(git: Git) {
        val storedConfig = git.repository.config
        storedConfig.setString("user", null, "name", "Stelekit Test")
        storedConfig.setString("user", null, "email", "stelekit-test@example.com")
        storedConfig.save()
    }

    private fun pageContent(n: Int): String {
        val next = (n % PAGE_COUNT) + 1
        return "- Content block A for Page $n\n- Content block B for Page $n references [[Page $next]]\n"
    }

    /** `git rev-list --all --count`-equivalent commit count, plus a plain ref count. */
    private fun gitRefAndCommitCounts(repoDir: File): Pair<Int, Int> =
        Git.open(repoDir).use { git ->
            val refCount = git.repository.refDatabase.getRefsByPrefix(RefDatabase.ALL).size
            val commitCount = git.log().all().call().count()
            refCount to commitCount
        }

    /** Parses [graphPath] fresh into a throwaway in-memory repository pair — see class doc for why. */
    private suspend fun snapshotGraph(fileSystem: FileSystem, graphPath: String): Map<String, List<String>> {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val loader = GraphLoader(fileSystem, pageRepo, blockRepo)
        loader.loadGraph(graphPath) { }
        val pages = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty()
        return pages.associate { page ->
            val blocks = blockRepo.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
            page.name to blocks.map { it.content }
        }
    }

    private fun newGraphManager(): GraphManager = GraphManager(
        platformSettings = E2eStubSettings(),
        driverFactory = DriverFactory(),
        fileSystem = E2eStubGraphManagerFileSystem(),
        defaultBackend = GraphBackend.SQLDELIGHT,
    )
}

/**
 * Delegates every operation to a real, disk-backed [PlatformFileSystem] — so the copy/verify/
 * rename walk BulkCopyVerifier and AtomicFileRelocationStep perform is 100% real file I/O — and
 * supplies only [newAppOwnedGraphPath], which the real JVM [PlatformFileSystem] does not
 * implement (`supportsAppOwnedStorage = false` is the correct answer for a real Desktop install;
 * this is a test-only stand-in, not a production gap). See [GraphRelocationEndToEndTest]'s class
 * doc for why `DirectAccessFolder`/this class are used instead of `SafFolder`/`AppOwned`-native.
 */
private class RealAppOwnedFileSystem(
    private val delegate: PlatformFileSystem,
    private val appOwnedBaseDir: File,
) : FileSystem by delegate {
    override val supportsAppOwnedStorage: Boolean = true
    override fun newAppOwnedGraphPath(): String =
        File(appOwnedBaseDir, "graph-${UUID.randomUUID()}").absolutePath
}

/** Desktop has no wired [GraphMoveQuiesceStrategy] actual yet (no git-shadow-worktree concept to
 * quiesce) — mirrors `WasmJsGraphMoveQuiesceStrategy`'s pre-3.3.2 no-op precedent. */
private object NoOpQuiesceStrategy : GraphMoveQuiesceStrategy {
    override suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit> = Unit.right()
    override suspend fun release(op: StorageMoveOperation) {}
    override suspend fun releaseSourceGrant(source: StorageLocation) {}
}

private class E2eStubSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** GraphManager's own registry/DB bookkeeping never reads markdown content directly — only
 * GraphLoader (invoked independently by [GraphRelocationEndToEndTest.snapshotGraph]) does — so
 * this stub matches `GraphRelocationCoordinatorTest.kt`'s `StubGraphManagerFileSystem` precedent. */
private class E2eStubGraphManagerFileSystem : FileSystem {
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
}
