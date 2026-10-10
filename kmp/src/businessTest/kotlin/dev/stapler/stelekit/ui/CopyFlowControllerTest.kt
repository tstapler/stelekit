package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.CreatedFile
import dev.stapler.stelekit.merge.ManifestPageEntry
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.DefaultCopyRunHost
import dev.stapler.stelekit.merge.MergeId
import dev.stapler.stelekit.merge.MergeManifestStore
import dev.stapler.stelekit.merge.OkioMergeStagingStore
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeStagingDirectory
import dev.stapler.stelekit.merge.MergeUndo
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.PageMergeService
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.RemoveReport
import dev.stapler.stelekit.merge.RoutedTargetWriter
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.merge.SourcePage
import dev.stapler.stelekit.merge.SourcePlatform
import dev.stapler.stelekit.merge.SourceReadCapabilities
import dev.stapler.stelekit.merge.TargetWriter
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.screens.copy.CapabilityDestinationProbe
import dev.stapler.stelekit.ui.screens.copy.CopyDestinationSettings
import dev.stapler.stelekit.ui.screens.copy.CopyFlowController
import dev.stapler.stelekit.ui.screens.copy.CopyGraphBinding
import dev.stapler.stelekit.ui.screens.copy.CopyPagesEvent
import dev.stapler.stelekit.ui.screens.copy.CopyServices
import dev.stapler.stelekit.ui.screens.copy.CopyStage
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DryRunUiState
import dev.stapler.stelekit.ui.screens.copy.InterruptedProblem
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.ReviewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.fakefilesystem.FakeFileSystem
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The copy flow's sequencing: picker -> dry run -> run -> result -> undo, plus the interrupted-copy notice. */
class CopyFlowControllerTest {
    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class FakeSource(val entries: List<Pair<Page, List<Block>>>) : PageSource {
        private val sorted = entries.sortedBy { it.first.name }
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int) =
            sorted.drop(offset).take(limit).map { it.first }.right()
        override suspend fun countPages(filter: SelectionFilter, search: String?) = sorted.size.toLong().right()
        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> =
            uuids.mapNotNull { id -> entries.firstOrNull { it.first.uuid == id } }.map { SourcePage(it.first, it.second) }.right()
    }

    private class FakeTarget : TargetWriter {
        val pages = HashMap<String, MergePage>()

        /** When set, [write] signals [writeStarted] then waits for it before writing. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val writeStarted = CompletableDeferred<Unit>()
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = pages[page.name].right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            gate?.let { writeStarted.complete(Unit); it.await() }
            val created = page.name !in pages
            pages[page.name] = merged
            return (if (created) WriteOutcome.Created("pages/${page.name}.md", "h-${page.name}")
            else WriteOutcome.Updated("pages/${page.name}.md", "h2-${page.name}", 1)).right()
        }
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
            RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    private fun hex(prefix: Int, n: Int) = "00000000-0000-0000-%04x-%012x".format(prefix, n)

    private fun entry(n: Int): Pair<Page, List<Block>> {
        val page = Page(uuid = PageUuid(hex(1, n)), name = "page$n", createdAt = epoch, updatedAt = epoch)
        val u = hex(2, n)
        return page to listOf(
            Block(BlockUuid(u), page.uuid, content = "body $n", position = "a0", createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u)),
        )
    }

    private val managers = mutableListOf<GraphManager>()
    private val controllers = mutableListOf<CopyFlowController>()

    @AfterTest
    fun cleanup() {
        controllers.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private inner class Env(registered: List<GraphId> = listOf(src, dst), runHost: CopyRunHost = DefaultCopyRunHost()) {
        val target = FakeTarget()
        val okio = FakeFileSystem()
        val switched = mutableListOf<GraphId>()
        val manager: GraphManager = run {
            val graphs = registered.map { GraphInfo(id = it, path = "/data/${it.value}", displayName = "G-${it.value.take(1)}", addedAt = 0L) }
            val settings = MapSettings()
            settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = src, graphs = graphs)))
            GraphManager(settings, DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY).also { managers += it }
        }
        val capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)
        val router = TargetWriterRouter(
            manager, RegistryGraphLocator(manager.graphRegistry), capabilities,
            activeWriterFor = { target }, offGraphWriterFor = { target },
        )
        val manifests = MergeManifestStore(okio, "/app")
        val service = PageMergeService(router, okio, "/app", graphRoot = { "/data/${it.value}" }, nowEpochMs = { 1_000L })
        val services = CopyServices(
            service = service,
            runHost = runHost,
            router = router,
            manifests = manifests,
            undo = MergeUndo(manifests, { RoutedTargetWriter(router, it) }, { 1_000L }),
            probe = CapabilityDestinationProbe(capabilities, SourceReadCapabilities(SourcePlatform.Desktop)),
            destinationSettings = CopyDestinationSettings(MapSettings()),
            staging = OkioMergeStagingStore(okio, "/app"),
        )
        val controller = CopyFlowController(services, manager.graphRegistry, { switched += it }).also { controllers += it }
        val entries = (1..3).map(::entry)
        val binding = CopyGraphBinding(src, FakeSource(entries), { emptyList<Page>().right() }, {})
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block(this) } }
    }

    private suspend fun CopyFlowController.await(what: String, until: (dev.stapler.stelekit.ui.screens.copy.CopyFlowState) -> Boolean) =
        try {
            withTimeout(20.seconds) { state.first(until) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("timed out waiting for $what; state=${state.value.stage} dryRun=${state.value.dryRun?.ui}")
        }

    /** Opens the picker, ticks [pick] and runs the dry run against [dst]. */
    private suspend fun Env.reviewPages(pick: Int) {
        controller.attachGraph(binding)
        manager.awaitPendingMigration()
        controller.open(entries[0].first.uuid.takeIf { pick == 1 })
        val picker = controller.state.value.picker!!
        picker.state.first { it.listLoad == ListLoad.Idle }
        if (pick > 1) picker.selectAllMatching()
        picker.state.first { !it.selecting && it.selectedCount == pick }
        picker.state.first { s -> s.destinations.firstOrNull { it.graphId == dst }?.status == DestinationStatus.Available }
        picker.chooseDestination(dst)
        picker.review()
        controller.await("dry run") { it.dryRun?.ui is DryRunUiState.Ready }
    }

    @Test
    fun `disposing the controller mid-apply does not kill the run and a new controller adopts it`() = realTime {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.target.gate = gate
        env.reviewPages(1)
        env.controller.dryRunConfirm()
        env.target.writeStarted.await()

        env.controller.close()
        val next = CopyFlowController(env.services, env.manager.graphRegistry, {}).also { controllers += it }
        assertEquals(CopyStage.Running, next.state.value.stage)

        gate.complete(Unit)
        next.await("finished") { it.stage == CopyStage.Finished }
        assertEquals(1, next.state.value.result!!.newPages)
        assertEquals(1, env.target.pages.size)
    }

    @Test
    fun `an outcome that lands while no controller is attached is delivered on the next attach`() = realTime {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.target.gate = gate
        env.reviewPages(1)
        env.controller.dryRunConfirm()
        env.target.writeStarted.await()

        env.controller.close()
        gate.complete(Unit)
        env.services.runHost.running.first { !it }
        val next = CopyFlowController(env.services, env.manager.graphRegistry, {}).also { controllers += it }
        next.await("finished") { it.stage == CopyStage.Finished }
        assertEquals(1, next.state.value.result!!.newPages)
    }

    @Test
    fun `entry from a page preselects exactly that page`() = realTime {
        val env = Env()
        env.controller.attachGraph(env.binding)
        env.controller.open(env.entries[1].first.uuid)

        val state = env.controller.state.value
        assertEquals(CopyStage.Picking, state.stage)
        assertEquals(setOf(env.entries[1].first.uuid), state.picker!!.state.value.picked.uuids)
    }

    @Test
    fun `picker to dry run to result copies the pages and Done returns to idle`() = realTime {
        val env = Env()
        env.reviewPages(pick = 3)

        val ready = env.controller.state.value.dryRun!!.ui as DryRunUiState.Ready
        assertEquals(3, ready.summary.new)
        env.controller.dryRunConfirm()
        val done = env.controller.await("result") { it.stage == CopyStage.Finished }

        assertEquals(3, done.result!!.newPages)
        assertEquals(setOf("page1", "page2", "page3"), env.target.pages.keys)
        assertNull(done.picker, "picker is released once the run has an outcome")
        env.controller.done()
        assertEquals(CopyStage.Idle, env.controller.state.value.stage)
    }

    @Test
    fun `dry run Back returns to the picker with the selection intact`() = realTime {
        val env = Env()
        env.reviewPages(pick = 3)
        env.controller.dryRunBack()

        val state = env.controller.state.value
        assertEquals(CopyStage.Picking, state.stage)
        assertNull(state.dryRun)
        assertEquals(3, state.picker!!.state.value.selectedCount)
        assertEquals(ReviewState.Idle, state.picker!!.state.value.review)
    }

    @Test
    fun `undo through the router removes what the copy created`() = realTime {
        val env = Env()
        env.reviewPages(pick = 3)
        env.controller.dryRunConfirm()
        env.controller.await("result") { it.stage == CopyStage.Finished }

        env.controller.requestUndo()
        assertEquals(CopyStage.ConfirmUndo, env.controller.state.value.stage)
        env.controller.confirmUndo()
        env.controller.await("idle after undo") { it.stage == CopyStage.Idle }
        val notice = withTimeout(20.seconds) { env.controller.noticeFlow.first { it.startsWith("Undid copy") } }
        assertTrue(notice.contains("removed"), notice)
    }

    @Test
    fun `starting while a copy runs is refused with a notice`() = realTime {
        val busy = object : CopyRunHost {
            override val running = MutableStateFlow(true)
            override fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit) = false
            override fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit) = false
            override fun stop(service: PageMergeService) = Unit
        }
        val env = Env(runHost = busy)
        env.controller.attachGraph(env.binding)
        env.controller.open()

        assertEquals("A copy is already running", withTimeout(20.seconds) { env.controller.noticeFlow.first() })
        // A controller built while the host is mid-run adopts it (Activity recreation) instead of showing Idle.
        assertEquals(CopyStage.Running, env.controller.state.value.stage)
    }

    @Test
    fun `detaching during a run marks it backgrounded and the run still finishes`() = realTime {
        val env = Env()
        env.reviewPages(pick = 3)
        env.controller.dryRunConfirm()
        env.controller.detachGraph(env.binding)
        val done = env.controller.await("result") { it.stage == CopyStage.Finished }
        assertEquals(3, done.result!!.newPages)
        assertEquals(setOf("page1", "page2", "page3"), env.target.pages.keys)
    }

    @Test
    fun `an interrupted manifest is offered at start and Dismiss completes it`() = realTime {
        val env = Env()
        val id = MergeId("run1")
        MergeStagingDirectory.create(env.okio, "/app", id, src, dst, 1L).getOrNull()!!
            .writePage(0, MergePage("page1"))
        env.manifests.begin(id, src.value, dst.value, 1L)

        env.controller.checkInterrupted()
        val st = env.controller.await("interrupted") { it.stage == CopyStage.Interrupted }
        assertEquals(1, st.interrupted!!.totalPages)
        assertNull(st.interrupted!!.problem)

        env.controller.dismissInterrupted()
        assertEquals(CopyStage.Idle, env.controller.state.value.stage)
        env.controller.await("idle") { it.stage == CopyStage.Idle }
        kotlinx.coroutines.delay(200)
        assertTrue(env.manifests.findInterrupted().isEmpty(), "dismissed manifest is completed, kept for undo")
        assertNotNull(env.manifests.load(id))
    }

    @Test
    fun `finishing a resumed copy folds the interrupted manifest into the new one so undo covers both`() = realTime {
        val env = Env()
        val oldId = MergeId("run3")
        MergeStagingDirectory.create(env.okio, "/app", oldId, src, dst, 1L).getOrNull()!!
            .writePage(0, MergePage("page1"))
        env.manifests.begin(oldId, src.value, dst.value, 1L).getOrNull()!!
            .appendPage(ManifestPageEntry("firstRunPage", createdFiles = listOf(CreatedFile("pages/firstRunPage.md", "h0"))))

        env.controller.attachGraph(CopyGraphBinding(src, FakeSource(env.entries), { listOf(env.entries[0].first).right() }, {}))
        env.controller.checkInterrupted()
        env.controller.await("interrupted") { it.stage == CopyStage.Interrupted }
        env.controller.resume()
        env.controller.await("resume dry run") { it.dryRun?.ui is DryRunUiState.Ready }
        env.controller.dryRunConfirm()
        val done = env.controller.await("finished") { it.stage == CopyStage.Finished }

        val newId = MergeId(done.result!!.mergeId)
        withTimeout(10.seconds) {
            while (env.manifests.load(oldId) != null) kotlinx.coroutines.delay(20)
        }
        assertTrue(env.manifests.findInterrupted().isEmpty(), "no interrupted copy left to re-offer")
        val names = env.manifests.load(newId)!!.pages.map { it.pageName }.toSet()
        assertEquals(setOf("page1", "firstRunPage"), names)
    }

    @Test
    fun `a controller rebuilt mid-resume still folds the interrupted manifest in`() = realTime {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.target.gate = gate
        val oldId = MergeId("run4")
        MergeStagingDirectory.create(env.okio, "/app", oldId, src, dst, 1L).getOrNull()!!
            .writePage(0, MergePage("page1"))
        env.manifests.begin(oldId, src.value, dst.value, 1L).getOrNull()!!
            .appendPage(ManifestPageEntry("firstRunPage", createdFiles = listOf(CreatedFile("pages/firstRunPage.md", "h0"))))

        env.controller.attachGraph(CopyGraphBinding(src, FakeSource(env.entries), { listOf(env.entries[0].first).right() }, {}))
        env.controller.checkInterrupted()
        env.controller.await("interrupted") { it.stage == CopyStage.Interrupted }
        env.controller.resume()
        env.controller.await("resume dry run") { it.dryRun?.ui is DryRunUiState.Ready }
        env.controller.dryRunConfirm()
        env.target.writeStarted.await()

        env.controller.close()
        val next = CopyFlowController(env.services, env.manager.graphRegistry, {}).also { controllers += it }
        gate.complete(Unit)
        val done = next.await("finished") { it.stage == CopyStage.Finished }

        withTimeout(10.seconds) {
            while (env.manifests.load(oldId) != null) kotlinx.coroutines.delay(20)
        }
        val names = env.manifests.load(MergeId(done.result!!.mergeId))!!.pages.map { it.pageName }.toSet()
        assertEquals(setOf("page1", "firstRunPage"), names)
    }

    @Test
    fun `an interrupted copy whose target graph is gone cannot resume`() = realTime {
        val env = Env(registered = listOf(src))
        val id = MergeId("run2")
        MergeStagingDirectory.create(env.okio, "/app", id, src, dst, 1L).getOrNull()!!.writePage(0, MergePage("page1"))
        env.manifests.begin(id, src.value, dst.value, 1L)

        env.controller.checkInterrupted()
        val st = env.controller.await("interrupted") { it.stage == CopyStage.Interrupted }
        assertEquals(InterruptedProblem.TargetGone, st.interrupted!!.problem)
    }

    @Test
    fun `OpenGraph from the picker closes the flow and switches graph`() = realTime {
        val env = Env()
        env.controller.attachGraph(env.binding)
        env.controller.open()
        env.controller.onPickerEvent(CopyPagesEvent.OpenGraph(dst))

        assertEquals(listOf(dst), env.switched)
        assertEquals(CopyStage.Idle, env.controller.state.value.stage)
    }

    @Test
    fun `Review conflicts on a closed target switches to it and waits`() = realTime {
        val env = Env()
        env.reviewPages(pick = 3)
        env.controller.dryRunConfirm()
        env.controller.await("result") { it.stage == CopyStage.Finished }
        env.controller.reviewConflicts()

        assertEquals(listOf(dst), env.switched)
        assertEquals(CopyStage.Conflicts, env.controller.state.value.stage)
        assertEquals(dst, env.controller.state.value.conflictsTarget)
    }
}
