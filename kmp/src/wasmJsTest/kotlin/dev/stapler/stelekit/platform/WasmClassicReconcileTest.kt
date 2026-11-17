// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ClassicFileSideSetting
import dev.stapler.stelekit.projectbridge.ClassicGatedSidecarStore
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.ReconcileOutcome
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarReconciler
import dev.stapler.stelekit.projectbridge.SyncedV2ProjectDiscovery
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 1.4.7n (D-147, D-153): the classic file side of the web build over the real wasm file system and OPFS, with the
 * setting `Off` and `On`. A desktop-synced bundle is seeded through the ungated store, then a "reloaded" file system
 * (fresh caches, fresh in-memory database) reconciles it through [ClassicGatedSidecarStore], as the browser does.
 */
@OptIn(DirectRepositoryWrite::class)
class WasmClassicReconcileTest {
    private val projectUuid = ProjectUuid("p1")
    private val slug = "proj-0a1b2c"

    private fun freshGraph(): String = "/stelekit/it-classic-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun sketch(name: String, uuid: String, position: Long, schema: Long = SheetPaths.SCHEMA_V1) = SketchSheet(
        uuid = SheetUuid(uuid), projectUuid = projectUuid, kind = "plan", viewDirection = "top",
        unit = "in", scaleSixteenthsPerUnit = 16, layers = listOf("walls"), position = position, schemaVersion = schema,
    ).also { check(name.isNotEmpty()) }

    private val v2Bytes = """{"schema":2,"sheetUuid":"p1-v2","scene":{"elements":[]}}""".encodeToByteArray()

    private fun bundle(name: String, withV2: Boolean): ProjectBundle {
        val classic = sketch("classic", "p1-s0", 0)
        val v2 = sketch("v2", "p1-v2", 1, SheetPaths.SCHEMA_V2)
        return ProjectBundle(
            project = SketchProject(uuid = projectUuid, slug = slug, name = name, createdAt = 1L),
            sheets = if (withV2) listOf(classic, v2) else listOf(classic),
            primitives = listOf(SketchPrimitive("p1-s0-pr0", classic.uuid, "line", 0, 0, 608, 0, "n", "walls", 0)),
            dimensions = emptyList(),
            sheetPayloads = if (withV2) mapOf(v2.uuid to SheetPayload.V2Opaque.of(v2Bytes)) else emptyMap(),
        )
    }

    private suspend fun readyFs(graph: String): PlatformFileSystem {
        val result = OpfsCapability().probe()
        assertIs<OpfsStorageVerdict.Available>(result.verdict, "the real probe must be Available: ${result.facts}")
        val fs = PlatformFileSystem()
        fs.recordProbe(result)
        fs.preload(graph)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        return fs
    }

    private fun rawStore(fs: PlatformFileSystem, graph: String) =
        FileProjectSidecarStore(fs, graph, AtomicFileWriter(fs, { it }, ".tmp"), AlwaysHeldSketchWriterLease()) { false }

    private fun gated(fs: PlatformFileSystem, graph: String, setting: ClassicFileSideSetting) =
        ClassicGatedSidecarStore(rawStore(fs, graph), fs.classicFileSideNeedsOptIn) { setting }

    /** What the desktop pushed and git synced: written through the real store and made durable. */
    private suspend fun seedSyncedBundle(graph: String, bundle: ProjectBundle) {
        val fs = readyFs(graph)
        val files = SidecarCodec.encode(bundle)
        assertEquals(dev.stapler.stelekit.projectbridge.SidecarWriteOutcome.Written, rawStore(fs, graph).write(slug, files))
        inBrowser { for (rel in files.keys) assertTrue(fs.awaitDurable("${SidecarLiveSet.rootOf(graph)}/$slug/$rel").isRight()) }
    }

    private fun reconciler(repos: SketchRepositories, store: ClassicGatedSidecarStore, setting: ClassicFileSideSetting) =
        SidecarReconciler(repos, store, null, v2Discovery = if (setting == ClassicFileSideSetting.Off) SyncedV2ProjectDiscovery(store) else null)

    private suspend fun <T> inBrowser(body: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { body() } }

    private suspend fun sheetsOf(repos: SketchRepositories) =
        repos.sheets.getSheets(projectUuid).first().getOrNull()!!.items.map { it.uuid.value }

    private fun filesUnder(fs: PlatformFileSystem, graph: String) = fs.listFiles("${SidecarLiveSet.rootOf(graph)}/$slug")

    @Test
    fun settingOffAndSyncedV2Project_should_OpenEditable() = runTest {
        val graph = freshGraph()
        seedSyncedBundle(graph, bundle("Kitchen", withV2 = true))
        val fs = readyFs(graph)
        val repos = inMemorySketchRepositories()

        val outcome = reconciler(repos, gated(fs, graph, ClassicFileSideSetting.Off), ClassicFileSideSetting.Off).reconcileAll().outcomes.single()

        assertIs<ReconcileOutcome.Pulled>(outcome)
        assertEquals(listOf("p1-v2"), sheetsOf(repos), "only the v2 sheet reaches the database")
    }

    @Test
    fun settingOff_should_LeaveEveryClassicFileSideUntouched() = runTest {
        val graph = freshGraph()
        seedSyncedBundle(graph, bundle("Kitchen", withV2 = false))
        val fs = readyFs(graph)
        val before = filesUnder(fs, graph)
        val repos = inMemorySketchRepositories()

        val outcomes = reconciler(repos, gated(fs, graph, ClassicFileSideSetting.Off), ClassicFileSideSetting.Off).reconcileAll().outcomes

        assertTrue(outcomes.isEmpty(), "a classic-only synced project is not found: $outcomes")
        assertEquals(null, repos.projects.loadProject(projectUuid).getOrNull())
        assertEquals(before, filesUnder(fs, graph), "nothing was written under the sidecar root")
    }

    @Test
    fun existingDatabaseAndSyncedBundle_should_ReconcileLikeDesktop() = runTest {
        val graph = freshGraph()
        seedSyncedBundle(graph, bundle("Kitchen", withV2 = false))
        val fs = readyFs(graph)
        val repos = inMemorySketchRepositories()
        val store = gated(fs, graph, ClassicFileSideSetting.On)

        val first = reconciler(repos, store, ClassicFileSideSetting.On).reconcileAll().outcomes.single()
        assertIs<ReconcileOutcome.Pulled>(first, "On: importNew finds the project as on desktop")
        assertEquals(listOf("p1-s0"), sheetsOf(repos))
        val second = reconciler(repos, store, ClassicFileSideSetting.On).reconcileAll().outcomes.single()
        assertIs<ReconcileOutcome.Unchanged>(second)
    }

    @Test
    fun divergentEdits_should_FollowTheDesktopOutcomeRows() = runTest {
        val graph = freshGraph()
        seedSyncedBundle(graph, bundle("Kitchen", withV2 = false))
        val fs = readyFs(graph)
        val repos = inMemorySketchRepositories()
        // The database holds a different edit of the same project and has never synced it (base null): both changed.
        repos.projects.replaceProjectContents(projectUuid, bundle("Pantry", withV2 = false)).getOrNull()

        val outcome = reconciler(repos, gated(fs, graph, ClassicFileSideSetting.On), ClassicFileSideSetting.On).reconcileAll().outcomes.single()

        assertIs<ReconcileOutcome.Conflict>(outcome)
    }

    @Test
    fun pullOverwrite_should_ReplaceTheDatabaseContentsOnlyThroughReplaceProjectContents() = runTest {
        val graph = freshGraph()
        seedSyncedBundle(graph, bundle("Kitchen", withV2 = false))
        val repos = inMemorySketchRepositories()
        val settle = reconciler(repos, gated(readyFs(graph), graph, ClassicFileSideSetting.On), ClassicFileSideSetting.On)
        assertIs<ReconcileOutcome.Pulled>(settle.reconcileAll().outcomes.single())

        // The other device pushes a renamed project; the database is unchanged since the last sync.
        seedSyncedBundle(graph, bundle("Kitchen v2", withV2 = false))
        val outcome = reconciler(repos, gated(readyFs(graph), graph, ClassicFileSideSetting.On), ClassicFileSideSetting.On).reconcileAll().outcomes.single()

        assertIs<ReconcileOutcome.Pulled>(outcome)
        assertEquals("Kitchen v2", repos.projects.loadProject(projectUuid).getOrNull()!!.project.name)
    }

    private companion object {
        const val AWAIT_MS = 20_000L
    }
}
