package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.DefaultCopyRunHost
import dev.stapler.stelekit.merge.InMemoryMergeStorage
import dev.stapler.stelekit.merge.MarkdownSourceGraphReader
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.MergePlan
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
import dev.stapler.stelekit.merge.offeredDirections
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
import dev.stapler.stelekit.ui.screens.copy.CopyFlowState
import dev.stapler.stelekit.ui.screens.copy.CopyGraphBinding
import dev.stapler.stelekit.ui.screens.copy.CopyServices
import dev.stapler.stelekit.ui.screens.copy.CopyStage
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DryRunUiState
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.inMemoryCopyHostConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS/Web (Pull) copy flow end to end with in-memory staging and capabilities faked to verified,
 * so it is proven the day Spike 0.1.5's flags flip.
 */
class PullCopyFlowControllerTest {
    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private object NoSource : PageSource {
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int) = emptyList<Page>().right()
        override suspend fun countPages(filter: SelectionFilter, search: String?) = 0L.right()
        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> = emptyList<SourcePage>().right()
    }

    private class FakeTarget : TargetWriter {
        val pages = HashMap<String, MergePage>()
        var failNames: Set<String> = emptySet()
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = pages[page.name].right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            if (page.name in failNames) return DomainError.MergeError.Retryable("destination closed").left()
            pages[page.name] = merged
            return WriteOutcome.Created("pages/${page.name}.md", "h-${page.name}").right()
        }
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
            RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    private val managers = mutableListOf<GraphManager>()
    private val controllers = mutableListOf<CopyFlowController>()

    @AfterTest
    fun cleanup() {
        controllers.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private inner class Env(
        runHost: CopyRunHost = DefaultCopyRunHost(),
        direction: CopyDirection = CopyDirection.Pull,
        registered: List<GraphId> = listOf(src, dst),
    ) {
        val target = FakeTarget()
        val switched = mutableListOf<GraphId>()
        val manager: GraphManager = run {
            val graphs = registered.map { GraphInfo(id = it, path = "/data/${it.value}", displayName = "G-${it.value.take(1)}", addedAt = 0L) }
            val settings = MapSettings()
            // The ACTIVE graph is the destination.
            settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = dst, graphs = graphs)))
            GraphManager(settings, DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY).also { managers += it }
        }
        private val capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = false)
        private val router = TargetWriterRouter(
            manager, RegistryGraphLocator(manager.graphRegistry), capabilities,
            activeWriterFor = { target }, offGraphWriterFor = { error("pull must never use an off-graph writer") },
        )
        val storage = InMemoryMergeStorage()
        private val files = FakeRelocationFileSystem().also { fs ->
            mapOf("pages/Alpha.md" to "- alpha\n", "pages/Beta.md" to "- beta\n", "journals/2026_10_07.md" to "- morning\n")
                .forEach { (rel, text) -> fs.writeFileBytes("/data/${src.value}/$rel", text.encodeToByteArray()) }
        }
        private val service = PageMergeService(router, storage, graphRoot = { "/data/${it.value}" }, nowEpochMs = { 1_000L })
        val services = CopyServices(
            service = service,
            runHost = runHost,
            router = router,
            manifests = storage.manifests,
            staging = storage.staging,
            undo = MergeUndo(storage.manifests, { RoutedTargetWriter(router, it) }, { 1_000L }),
            probe = CapabilityDestinationProbe(capabilities, SourceReadCapabilities(SourcePlatform.Ios, iosVerified = true)),
            destinationSettings = CopyDestinationSettings(MapSettings()),
            direction = direction,
            sourceReader = MarkdownSourceGraphReader(files),
        )
        val controller = CopyFlowController(services, manager.graphRegistry, { switched += it }).also { controllers += it }
        val dstBinding = CopyGraphBinding(dst, NoSource, { emptyList<Page>().right() }, {})
        val srcBinding = CopyGraphBinding(src, NoSource, { emptyList<Page>().right() }, {})
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block(this) } }
    }

    private suspend fun CopyFlowController.await(what: String, until: (CopyFlowState) -> Boolean) =
        try {
            withTimeout(20.seconds) { state.first(until) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("timed out waiting for $what; state=${state.value.stage} dryRun=${state.value.dryRun?.ui}")
        }

    /** Opens the pull picker, picks [src] as the source, selects every page and runs the dry run. */
    private suspend fun Env.reviewAll(preselectSource: GraphId? = null) {
        controller.attachGraph(dstBinding)
        manager.awaitPendingMigration()
        controller.open(preselectSource = preselectSource)
        val picker = controller.state.value.picker!!
        picker.state.first { s -> s.destinations.firstOrNull { it.graphId == src }?.status == DestinationStatus.Available }
        if (preselectSource == null) picker.chooseDestination(src)
        picker.state.first { it.destinationId == src && it.totalMatching == 3L && it.listLoad == ListLoad.Idle }
        picker.selectAllMatching()
        picker.state.first { !it.selecting && it.selectedCount == 3 }
        picker.review()
        controller.await("dry run") { it.dryRun?.ui is DryRunUiState.Ready }
    }

    @Test
    fun `pull copies the source graph into the active graph with in-memory staging`() = realTime {
        val env = Env()
        env.reviewAll()
        assertEquals(CopyDirection.Pull, env.controller.state.value.direction)
        assertEquals(3, (env.controller.state.value.dryRun!!.ui as DryRunUiState.Ready).summary.new)
        assertTrue(env.target.pages.isEmpty(), "dry run must not write")

        env.controller.dryRunConfirm()
        val done = env.controller.await("result") { it.stage == CopyStage.Finished }

        assertEquals(3, done.result!!.newPages)
        assertEquals(3, env.target.pages.size)
        assertEquals(dst, done.request!!.targetGraphId)
        assertEquals(src, done.request!!.sourceGraphId)
        assertEquals(0, env.storage.staging.open(dev.stapler.stelekit.merge.MergeId(done.result!!.mergeId))?.pageCount() ?: 0, "staging cleared on success")
        assertTrue(env.storage.manifests.findInterrupted().isEmpty())
        env.controller.done()
        assertEquals(CopyStage.Idle, env.controller.state.value.stage)
    }

    @Test
    fun `the graph-switcher row entry preselects the source`() = realTime {
        val env = Env()
        env.reviewAll(preselectSource = src)
        assertEquals(src, env.controller.state.value.picker!!.state.value.destinationId)
    }

    @Test
    fun `leaving the destination mid-run asks first and switches only after the run stops`() = realTime {
        val started = MutableStateFlow<((CopyRunOutcome) -> Unit)?>(null)
        var stopped = 0
        val host = object : CopyRunHost {
            override val running = MutableStateFlow(false)
            override fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit): Boolean {
                running.value = true
                started.value = onOutcome
                return true
            }
            override fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit) = false
            override fun stop(service: PageMergeService) { stopped++ }
        }
        val env = Env(runHost = host)
        env.reviewAll()
        env.controller.dryRunConfirm()
        env.controller.await("running") { it.stage == CopyStage.Running }
        var switchedTo = 0

        env.controller.requestGraphSwitch(src) { switchedTo++ }
        assertTrue(env.controller.state.value.switchConfirm)
        assertEquals(0, switchedTo)

        env.controller.cancelPullSwitch()
        assertEquals(false, env.controller.state.value.switchConfirm)
        env.controller.requestGraphSwitch(src) { switchedTo++ }
        env.controller.confirmPullSwitch()
        assertEquals(1, stopped)
        assertEquals(0, switchedTo, "switch waits for the run to stop")

        host.running.value = false
        assertNotNull(started.value).invoke(CopyRunOutcome.Crashed("stopped"))
        assertEquals(1, switchedTo)
    }

    @Test
    fun `switching is not gated in Push or when nothing is running`() = realTime {
        val push = Env(direction = CopyDirection.Push)
        var n = 0
        push.controller.requestGraphSwitch(src) { n++ }
        assertEquals(1, n)

        val idlePull = Env()
        idlePull.controller.requestGraphSwitch(src) { n++ }
        assertEquals(2, n)
    }

    @Test
    fun `Retry failed re-opens the destination before retrying`() = realTime {
        val env = Env()
        env.target.failNames = setOf("Beta")
        env.reviewAll()
        env.controller.dryRunConfirm()
        val done = env.controller.await("result") { it.stage == CopyStage.Finished }
        assertEquals(listOf("Beta"), done.result!!.failed.map { it.pageName })

        env.controller.detachGraph(env.dstBinding)
        env.controller.attachGraph(env.srcBinding)
        env.target.failNames = emptySet()
        env.controller.retryFailedPages()
        assertEquals(listOf(dst), env.switched, "the destination is re-opened first")
        assertEquals(CopyStage.Finished, env.controller.state.value.stage)

        env.controller.detachGraph(env.srcBinding)
        env.controller.attachGraph(env.dstBinding)
        val retried = env.controller.await("retry done") { it.stage == CopyStage.Finished && it.result?.failed?.isEmpty() == true }
        assertEquals(3, retried.result!!.newPages)
    }

    @Test
    fun `Push is offered only on Android and Desktop and Pull only on iOS and Web`() {
        for (p in listOf(SourcePlatform.Android, SourcePlatform.Desktop)) assertEquals(listOf(CopyDirection.Push), offeredDirections(p))
        for (p in listOf(SourcePlatform.Ios, SourcePlatform.Web)) assertEquals(listOf(CopyDirection.Pull), offeredDirections(p))
        assertEquals(SourcePlatform.Web, inMemoryCopyHostConfig(SourcePlatform.Web).sourcePlatform)
    }
}
