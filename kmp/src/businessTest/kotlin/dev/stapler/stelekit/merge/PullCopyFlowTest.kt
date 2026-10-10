package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.screens.copy.PullCopyGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pull flow end to end: markdown files of an inactive graph, read through [MarkdownSourceGraphReader]
 * and [PullPageSource], merged by [PageMergeService] into the ACTIVE graph's writer.
 */
class PullCopyFlowTest {
    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")
    private val srcInfo = GraphInfo(id = src, path = "/data/${src.value}", displayName = "Source", addedAt = 0L)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class FakeTarget : TargetWriter {
        val pages = HashMap<String, MergePage>()
        var failWith: (String) -> DomainError? = { null }

        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = pages[page.name].right()

        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            failWith(page.name)?.let { return it.left() }
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

    private val managers = mutableListOf<GraphManager>()
    private val services = mutableListOf<PageMergeService>()
    private val sources = mutableListOf<PullPageSource>()

    @AfterTest
    fun cleanup() {
        sources.forEach { it.close() }
        services.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private inner class Env(files: Map<String, ByteArray>, reader: ((SourceGraphReader) -> SourceGraphReader)? = null) {
        val target = FakeTarget()
        val manager: GraphManager
        val service: PageMergeService
        val okio = FakeFileSystem()
        val source: PullPageSource

        init {
            val graphs = listOf(src, dst).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
            val settings = MapSettings()
            // The ACTIVE graph is the destination: pull always writes through the active writer.
            settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = dst, graphs = graphs)))
            manager = GraphManager(
                platformSettings = settings, driverFactory = DriverFactory(), fileSystem = StubFileSystem(),
                defaultBackend = GraphBackend.IN_MEMORY,
            ).also { managers += it }
            val router = TargetWriterRouter(
                graphManager = manager,
                locator = RegistryGraphLocator(manager.graphRegistry),
                capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = false),
                activeWriterFor = { target },
                offGraphWriterFor = { error("pull must never use an off-graph writer") },
            )
            service = PageMergeService(router, okio, "/app", nowEpochMs = { 1_000L }).also { services += it }
            val fs = FakeRelocationFileSystem()
            files.forEach { (rel, bytes) -> fs.writeFileBytes("${srcInfo.path}/$rel", bytes) }
            val base = MarkdownSourceGraphReader(fs)
            source = PullPageSource(reader?.invoke(base) ?: base).also { sources += it }
        }

        fun request() = PlanRequest(
            selection = PageSelection(), sourceGraphId = src, targetGraphId = dst, sourceGraphName = "Source",
            direction = CopyDirection.Pull,
        )

        suspend fun indexed(): Env {
            source.select(srcInfo)
            source.indexState.first { it is PullIndexState.Ready }
            manager.awaitPendingMigration()
            return this
        }

        suspend fun plan(): MergePlan = (service.plan(request(), source) as Either.Right).value
    }

    private fun text(s: String) = s.encodeToByteArray()

    private val basic = mapOf(
        "pages/Alpha.md" to text("- alpha one\n- alpha two\n"),
        "pages/Beta.md" to text("- beta\n\t- child\n"),
        "journals/2026_10_07.md" to text("- morning\n"),
    )

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(120.seconds) { block(this) } }
    }

    @Test
    fun `pull writes through the active writer and a repeat pull is unchanged`() = realTime {
        val env = Env(basic).indexed()
        val plan = env.plan()
        assertEquals(DryRunSummary(new = 3), plan.summary)
        assertEquals(CopyDirection.Pull, plan.direction)
        assertEquals(0, env.target.pages.size, "dry run must not write")

        val result = (env.service.apply(plan) as Either.Right).value
        assertEquals(3, result.newPages)
        assertEquals(0, result.failed.size)
        assertEquals(setOf("Alpha", "Beta", "2026_10_07"), env.target.pages.keys.map { it.replace('-', '_') }.toSet())
        assertTrue(env.target.pages.getValue("Beta").blocks.single().children.size == 1)

        val again = env.plan()
        assertEquals(DryRunSummary(unchanged = 3), again.summary)
    }

    @Test
    fun `readPages round-trips the staged page for many siblings and explicit ids`() = realTime {
        val many = (1..15).joinToString("\n") { "- item $it" } + "\n"
        val withId = "- keep\n  id:: 33333333-3333-3333-3333-333333333333\n- other\n"
        val env = Env(mapOf("pages/Many.md" to text(many), "pages/Ids.md" to text(withId))).indexed()
        val listed = (env.source.listPages(SelectionFilter(), null, 100, 0) as Either.Right).value
        val read = (env.source.readPages(listed.map { it.uuid }) as Either.Right).value
        val reader = MarkdownSourceGraphReader(FakeRelocationFileSystem().also { fs ->
            fs.writeFileBytes("${srcInfo.path}/pages/Many.md", text(many))
            fs.writeFileBytes("${srcInfo.path}/pages/Ids.md", text(withId))
        })
        for (sp in read) {
            val entry = (reader.listEntries(srcInfo, null, 100) as Either.Right).value.first { it.name == sp.page.name }
            val expected = (reader.readPage(srcInfo, entry) as Either.Right).value.toMergePage()
            assertEquals(expected, sp.toMergePage(), sp.page.name)
        }
    }

    @Test
    fun `an unreadable source page is counted failed with its reason and never queued`() = realTime {
        val files = basic + ("pages/Broken.md" to byteArrayOf(0xC3.toByte(), 0x28))
        val env = Env(files).indexed()
        val plan = env.plan()
        assertEquals(3, plan.summary.new)
        assertEquals(1, plan.summary.unreadable)

        val result = (env.service.apply(plan) as Either.Right).value
        assertEquals(3, result.newPages)
        assertEquals(listOf("Broken"), result.failed.map { it.pageName })
        assertEquals(0, result.notAttempted)
        assertTrue(result.failed.single().error.message.isNotBlank())

        // Retry cannot conjure a file that never staged; it stays failed, still not queued.
        val retried = (env.service.retryFailed() as Either.Right).value
        assertEquals(listOf("Broken"), retried.failed.map { it.pageName })
        assertTrue(env.okio.listRecursively("/app".toPath()).none { "inbox" in it.toString().lowercase() })
    }

    @Test
    fun `switching away from the destination fails later pages and Retry failed re-opens it`() = realTime {
        val env = Env(basic).indexed()
        val plan = env.plan()
        val refused = DomainError.MergeError.WriteRefused(WriteRefusedReason.Unwritable(WriteCapabilityReason.PlatformUnsupported))
        env.target.failWith = { name -> if (name != "Alpha") refused else null }

        val result = (env.service.apply(plan) as Either.Right).value
        assertEquals(1, result.newPages)
        assertEquals(2, result.failed.size)
        assertEquals(setOf("Beta", "2026_10_07"), result.failed.map { it.pageName }.toSet().map { it.replace('-', '_') }.toSet())

        var reopened = false
        val gateway = PullCopyGateway(env.service, env.source) {
            reopened = true
            env.target.failWith = { null }
            true
        }
        val retried = (gateway.retryFailed() as Either.Right).value
        assertTrue(reopened)
        assertEquals(3, retried.newPages)
        assertEquals(0, retried.failed.size)
    }

    @Test
    fun `a destination that cannot be re-opened leaves the failures in place`() = realTime {
        val env = Env(basic).indexed()
        val plan = env.plan()
        env.target.failWith = { DomainError.MergeError.Retryable("switched away") }
        (env.service.apply(plan) as Either.Right)

        val gateway = PullCopyGateway(env.service, env.source) { false }
        assertTrue(gateway.retryFailed() is Either.Left)
        assertEquals(0, env.target.pages.size)
    }

    @Test
    fun `name index pages are at most 100 and search kind and date filters run over the entries`() = realTime {
        var maxLimit = 0
        val files = (1..250).associate { "pages/P${it.toString().padStart(3, '0')}.md" to text("- body $it\n") } +
            ("journals/2026_10_07.md" to text("- a\n")) + ("journals/2026_10_09.md" to text("- b\n"))
        val env = Env(files) { base ->
            object : SourceGraphReader by base {
                override suspend fun listEntries(graph: GraphInfo, afterName: String?, limit: Int): Either<ReadError, List<SourceEntry>> {
                    maxLimit = maxOf(maxLimit, limit)
                    return base.listEntries(graph, afterName, limit)
                }
            }
        }.indexed()

        assertTrue(maxLimit in 1..SourceGraphReader.MAX_PAGE_SIZE, "listing limit was $maxLimit")
        assertEquals(252L, (env.source.countPages(SelectionFilter(), null) as Either.Right).value)
        assertEquals(1L, (env.source.countPages(SelectionFilter(), "p042") as Either.Right).value)
        assertEquals(2L, (env.source.countPages(SelectionFilter(journals = true, dateFrom = kotlinx.datetime.LocalDate(1, 1, 1)), null) as Either.Right).value)
        assertEquals(250L, (env.source.countPages(SelectionFilter(journals = false), null) as Either.Right).value)
        val oct8on = SelectionFilter(dateFrom = kotlinx.datetime.LocalDate(2026, 10, 8))
        assertEquals(1L, (env.source.countPages(oct8on, null) as Either.Right).value)
        assertEquals(100, (env.source.listPages(SelectionFilter(), null, 500, 0) as Either.Right).value.size)
    }

    @Test
    fun `a grant lost mid-listing surfaces NoGrant and keeps what loaded`() = realTime {
        val files = (1..150).associate { "pages/P${it.toString().padStart(3, '0')}.md" to text("- x\n") }
        var calls = 0
        val env = Env(files) { base ->
            object : SourceGraphReader by base {
                override suspend fun listEntries(graph: GraphInfo, afterName: String?, limit: Int): Either<ReadError, List<SourceEntry>> =
                    if (++calls > 1) ReadError.NoGrant.left() else base.listEntries(graph, afterName, limit)
            }
        }
        env.source.select(srcInfo)
        val failed = env.source.indexState.first { it is PullIndexState.Failed } as PullIndexState.Failed
        assertEquals(ReadError.NoGrant, failed.error)
        assertEquals(100L, (env.source.countPages(SelectionFilter(), null) as Either.Right).value)
    }
}
