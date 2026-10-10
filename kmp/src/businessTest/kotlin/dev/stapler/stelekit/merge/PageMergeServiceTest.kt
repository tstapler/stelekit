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
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PageMergeServiceTest {
    private val src = GraphId("aaaaaaaaaaaaaaaa")
    private val dst = GraphId("bbbbbbbbbbbbbbbb")
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val policy = MergePolicy(src, "A")

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private fun hex(prefix: Int, n: Int) = "00000000-0000-0000-%04x-%012x".format(prefix, n)

    /** Source of `Page` + flat blocks; records the largest read it was asked for. */
    private inner class FakeSource(val entries: List<Pair<Page, List<Block>>>) : PageSource {
        private val sorted = entries.sortedBy { it.first.name }
        private val byUuid = entries.associateBy { it.first.uuid }
        var maxListed = 0
        var maxRead = 0
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int): Either<DomainError, List<Page>> {
            val out = sorted.drop(offset).take(limit).map { it.first }
            maxListed = maxOf(maxListed, out.size, limit)
            return out.right()
        }
        override suspend fun countPages(filter: SelectionFilter, search: String?) = sorted.size.toLong().right()
        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> {
            maxRead = maxOf(maxRead, uuids.size)
            return uuids.mapNotNull { byUuid[it] }.map { SourcePage(it.first, it.second) }.right()
        }
    }

    private fun entry(n: Int, name: String = "p%04d".format(n), bodies: List<String> = listOf("body $name")): Pair<Page, List<Block>> {
        val page = Page(uuid = PageUuid(hex(1, n)), name = name, createdAt = epoch, updatedAt = epoch)
        val blocks = bodies.mapIndexed { i, text ->
            val u = hex(2, n * 10 + i)
            Block(
                uuid = BlockUuid(u), pageUuid = page.uuid, content = text, position = "a$i",
                createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u),
            )
        }
        return page to blocks
    }

    private fun merged(e: Pair<Page, List<Block>>): MergePage = SourcePage(e.first, e.second).toMergePage()

    /** Target-side page exactly as a previous copy would have left it. */
    private fun copiedPage(e: Pair<Page, List<Block>>): MergePage =
        (mergePage(null, merged(e), policy) as MergeOutcome.New).page

    private class FakeTarget : TargetWriter {
        val pages = HashMap<String, MergePage>()
        var writes = 0
        var onWrite: (String) -> Unit = {}
        var failWith: (String) -> DomainError? = { null }

        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = pages[page.name].right()

        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> {
            failWith(page.name)?.let { return it.left() }
            writes++
            val created = page.name !in pages
            pages[page.name] = merged
            onWrite(page.name)
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

    @AfterTest
    fun cleanup() {
        services.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private fun manager(): GraphManager {
        val graphs = listOf(src, dst).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = src, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
    }

    private inner class Env(
        val target: TargetWriter = FakeTarget(),
        offGraph: Boolean = true,
        closureLookup: suspend (List<String>) -> Either<DomainError, List<Page>> = { emptyList<Page>().right() },
        copier: AssetCopier? = null,
        activeWriter: TargetWriter? = null,
    ) {
        val manager = manager()
        val okio = FakeFileSystem()
        val router = TargetWriterRouter(
            graphManager = manager,
            locator = RegistryGraphLocator(manager.graphRegistry),
            capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = offGraph),
            activeWriterFor = { activeWriter ?: target },
            offGraphWriterFor = { target },
        )
        val service = PageMergeService(
            router, okio, "/app", closureLookup, copier,
            graphRoot = { "/data/${it.value}" },
            nowEpochMs = { 1_000L },
        ).also { services += it }
        val fake get() = target as FakeTarget

        fun request(closure: LinkClosurePolicy = LinkClosurePolicy.Off) = PlanRequest(
            selection = PageSelection(), sourceGraphId = src, targetGraphId = dst, sourceGraphName = "A", closure = closure,
        )

        suspend fun plan(source: PageSource, closure: LinkClosurePolicy = LinkClosurePolicy.Off): MergePlan {
            manager.awaitPendingMigration()
            return (service.plan(request(closure), source) as Either.Right).value
        }
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(120.seconds) { block(this) } }
    }

    private fun <A, B> Either<A, B>.ok(): B = if (this is Either.Right) value else error("expected Right but was $this")

    // ---- dry run ----

    private fun thirtyPages(env: Env): FakeSource {
        val all = (0 until 30).map { if (it in 10..14) entry(it, bodies = listOf("body $it", "extra $it")) else entry(it) }
        all.take(10).forEach { env.fake.pages[it.first.name] = copiedPage(it) }
        all.subList(10, 15).forEach { e ->
            val firstOnly = e.copy(second = e.second.take(1))
            env.fake.pages[e.first.name] = copiedPage(firstOnly)
        }
        return FakeSource(all)
    }

    @Test
    fun `dry run classifies 10 identical 5 overlapping 15 absent and writes nothing`() = realTime {
        val env = Env()
        val plan = env.plan(thirtyPages(env))

        assertEquals(DryRunSummary(new = 15, combined = 5, unchanged = 10, conflicts = 0, unreadable = 0), plan.summary)
        assertEquals(20, plan.summary.toCopy)
        assertEquals(0, env.fake.writes)
    }

    @Test
    fun `conflicts are a subset of combined and details are capped at 50`() = realTime {
        val env = Env()
        val all = (0 until 120).map { entry(it) }
        all.forEach { e ->
            val b = merged(e).blocks.single()
            val clash = b.copy(
                uuid = UuidRemap.uuidFor(src, b.uuid!!),
                content = "different ${e.first.name}",
                properties = mapOf(MergePropertyKeys.SRC_ID to SourceBlockRef.of(src, b.uuid!!).value),
            )
            env.fake.pages[e.first.name] = MergePage(e.first.name, blocks = listOf(clash))
        }
        val plan = env.plan(FakeSource(all))

        assertTrue(plan.summary.conflicts > 0, "fixture should produce conflicts: ${plan.summary}")
        assertTrue(plan.summary.conflicts <= plan.summary.combined)
        assertEquals(MergePlan.MAX_CONFLICT_DETAILS, plan.conflictDetails.size)
    }

    @Test
    fun `8000 page plan stays small and reads in chunks of at most 100`() = realTime {
        val env = Env()
        val source = FakeSource((0 until 8000).map { entry(it) })
        val plan = env.plan(source)

        assertEquals(8000, plan.summary.new)
        val bytes = Json.encodeToString(MergePlan.serializer(), plan).encodeToByteArray().size
        assertTrue(bytes < 256 * 1024, "serialized plan was $bytes bytes")
        assertTrue(source.maxListed <= PageSource.MAX_PAGE_SIZE && source.maxRead <= PageSource.MAX_PAGE_SIZE)
        assertEquals(0, env.fake.writes)
    }

    // ---- apply ----

    @Test
    fun `apply commits every page once and re-apply is a no-op`() = realTime {
        val env = Env()
        val source = thirtyPages(env)
        val result = env.service.apply(env.plan(source)).ok()

        assertEquals(15, result.newPages)
        assertEquals(5, result.combinedPages)
        assertEquals(10, result.unchangedPages)
        assertTrue(result.failed.isEmpty())
        assertEquals(30, result.newPages + result.combinedPages + result.unchangedPages + result.failed.size)
        assertEquals(20, env.fake.writes)
        assertNull(MergeStagingDirectory.open(env.okio, "/app", MergeId(result.mergeId)), "staging removed on clean success")

        val writesBefore = env.fake.writes
        val again = env.service.apply(env.plan(source)).ok()
        assertEquals(0, again.newPages)
        assertEquals(0, again.combinedPages)
        assertEquals(30, again.unchangedPages)
        assertEquals(writesBefore, env.fake.writes)
    }

    // Ported from the removed snapshot-based merge test.

    @Test
    fun `copies every page when the target is empty`() = realTime {
        val env = Env()
        val source = FakeSource(listOf(entry(1, name = "Page A", bodies = listOf("content a")), entry(2, name = "Page B", bodies = listOf("content b"))))
        val result = env.service.apply(env.plan(source)).ok()

        assertEquals(2, result.newPages)
        assertTrue(result.failed.isEmpty())
        assertEquals(setOf("Page A", "Page B"), env.fake.pages.keys)
    }

    @Test
    fun `combines blocks of same-named page without overwriting the target`() = realTime {
        val env = Env()
        env.fake.pages["Shared Page"] = MergePage("Shared Page", blocks = listOf(MergeBlock(uuid = hex(9, 1), content = "original target content")))
        val source = FakeSource(
            listOf(
                entry(1, name = "Shared Page", bodies = listOf("from source")),
                entry(2, name = "New Page", bodies = listOf("only in source")),
            ),
        )
        val result = env.service.apply(env.plan(source)).ok()

        assertEquals(1, result.newPages)
        assertEquals(1, result.combinedPages)
        assertTrue(result.failed.isEmpty())
        val texts = env.fake.pages.getValue("Shared Page").blocks.map { it.content }
        assertTrue("original target content" in texts, "target block must survive: $texts")
        assertTrue("from source" in texts, "source block must be added: $texts")
    }

    @Test
    fun `manifest records one entry per written page`() = realTime {
        val env = Env()
        val result = env.service.apply(env.plan(thirtyPages(env))).ok()

        val manifest = assertNotNull(MergeManifestStore(env.okio, "/app").load(MergeId(result.mergeId)))
        assertEquals(MergeStatus.Complete, manifest.status)
        assertEquals(20, manifest.pages.size)
        assertEquals(15, manifest.pages.count { it.createdFiles.isNotEmpty() })
    }

    @Test
    fun `stale plan returns PlanStale with recomputed summary and writes nothing`() = realTime {
        val env = Env()
        val source = thirtyPages(env)
        val plan = env.plan(source)
        // Someone adds a page to the target after the plan: p0020 was absent, is now present.
        env.fake.pages["p0020"] = copiedPage(entry(20))

        val res = env.service.apply(plan)

        val stale = assertIs<ApplyFailure.PlanStale>((res as Either.Left).value)
        assertEquals(DryRunSummary(new = 14, combined = 5, unchanged = 11), stale.recomputed)
        assertEquals(0, env.fake.writes)
    }

    @Test
    fun `old plan applied after a completed apply is stale and writes nothing`() = realTime {
        val env = Env()
        val source = thirtyPages(env)
        val plan = env.plan(source)
        env.service.apply(plan).ok()
        val writes = env.fake.writes

        val stale = assertIs<ApplyFailure.PlanStale>((env.service.apply(plan) as Either.Left).value)
        assertEquals(30, stale.recomputed.unchanged)
        assertEquals(writes, env.fake.writes)
    }

    @Test
    fun `partial failure continues, keeps staging, and retryFailed succeeds after the fault clears`() = realTime {
        val env = Env()
        val source = FakeSource((0 until 10).map { entry(it) })
        var faulty = true
        env.fake.failWith = { n -> if (faulty && n == "p0002") DomainError.FileSystemError.WriteFailed("p0002", "disk full") else null }

        val first = env.service.apply(env.plan(source)).ok()

        assertEquals(9, first.newPages)
        val failure = first.failed.single()
        assertEquals("p0002", failure.pageName)
        assertIs<DomainError.FileSystemError.WriteFailed>(failure.error)
        assertEquals(10, first.newPages + first.failed.size)
        val staging = MergeStagingDirectory.open(env.okio, "/app", MergeId(first.mergeId))
        assertNotNull(staging, "staging kept for retry")

        faulty = false
        val retried = env.service.retryFailed().ok()
        assertTrue(retried.failed.isEmpty())
        assertEquals(10, retried.newPages)
        assertEquals(10, env.fake.pages.size)
        assertNull(MergeStagingDirectory.open(env.okio, "/app", MergeId(first.mergeId)), "staging removed after a clean retry")
    }

    @Test
    fun `WriteRefused and retry-exhausted become failed entries and are never queued`() = realTime {
        val env = Env()
        val source = FakeSource((0 until 5).map { entry(it) })
        val refused = DomainError.MergeError.WriteRefused(WriteRefusedReason.InvalidPageName("p0001"))
        env.fake.failWith = { n ->
            when (n) {
                "p0001" -> refused
                "p0003" -> DomainError.MergeError.Retryable("channel closed")
                else -> null
            }
        }

        val result = env.service.apply(env.plan(source)).ok()

        assertEquals(3, result.newPages)
        assertEquals(setOf("p0001", "p0003"), result.failed.map { it.pageName }.toSet())
        assertTrue(result.failed.any { it.error is DomainError.MergeError.WriteRefused })
        assertTrue(result.failed.any { it.error is DomainError.MergeError.Retryable })
        assertEquals(0, result.notAttempted)
    }

    @Test
    fun `cancel after 1200 of 4000 keeps committed pages and a re-run converges`() = realTime {
        val env = Env()
        val source = FakeSource((0 until 4000).map { entry(it) })
        env.fake.onWrite = { if (env.fake.writes == 1200) env.service.cancel() }

        val stopped = env.service.apply(env.plan(source)).ok()

        assertEquals(1200, stopped.stoppedAfter)
        assertEquals("Stopped after 1,200 of 4,000", stopped.stoppedMessage)
        assertEquals(1200, stopped.newPages)
        assertEquals(2800, stopped.notAttempted)
        assertEquals(MergePhase.Stopped, env.service.progress.value.phase)
        assertEquals(1200, env.fake.pages.size)

        env.fake.onWrite = {}
        val rerunPlan = env.plan(source)
        assertEquals(DryRunSummary(new = 2800, unchanged = 1200), rerunPlan.summary)
        val done = env.service.apply(rerunPlan).ok()
        assertEquals(2800, done.newPages)
        assertEquals(4000, env.fake.pages.size)
        assertEquals(4000, env.plan(source).summary.unchanged)
    }

    @Test
    fun `progress reports done and total`() = realTime {
        val env = Env()
        env.service.apply(env.plan(FakeSource((0 until 12).map { entry(it) }))).ok()

        assertEquals(MergeProgress(MergePhase.Finished, 12, 12, 0), env.service.progress.value)
    }

    // ---- closure + assets ----

    @Test
    fun `link closure pulls linked pages in and renamed assets are counted`() = realTime {
        val assetFs = FakeRelocationFileSystem()
        val newBytes = byteArrayOf(9, 8, 7)
        val hash8 = ContentHasher.sha256(newBytes).take(8)
        assetFs.writeFileBytes("/data/${src.value}/assets/a.png", newBytes)
        assetFs.writeFileBytes("/data/${dst.value}/assets/a.png", byteArrayOf(1, 2, 3))
        val a = entry(1, "Alpha", listOf("see [[Beta]] ![x](../assets/a.png)"))
        val b = entry(2, "Beta", listOf("linked"))
        val env = Env(
            closureLookup = { names -> listOf(b.first).filter { it.name in names }.right() },
            copier = AssetCopier(assetFs),
        )
        val source = object : PageSource by FakeSource(listOf(a, b)) {
            override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int) =
                (if (offset == 0) listOf(a.first) else emptyList()).right()
        }

        val off = env.plan(source)
        assertEquals(1, off.summary.total)
        val plan = env.plan(source, LinkClosurePolicy.Depth1())
        assertEquals(DryRunSummary(new = 2, assetsRenamed = 1), plan.summary)
        assertEquals(1, plan.closure.added)

        val result = env.service.apply(plan).ok()
        assertEquals(2, result.newPages)
        assertEquals(1, result.assetsRenamed)
        assertTrue("a-$hash8.png" in env.fake.pages.getValue("Alpha").blocks.single().content)
        assertNotNull(assetFs.readFileBytes("/data/${dst.value}/assets/a-$hash8.png"))
    }

    // ---- undo through the router (deferred from Story 2.5.1) ----

    private class RoutedWriter(val router: TargetWriterRouter, val target: GraphId) : TargetWriter {
        override suspend fun readExisting(page: PageKey) = router.withWriter(target) { it.readExisting(page) }
        override suspend fun write(page: PageKey, merged: MergePage) = router.withWriter(target) { it.write(page, merged) }
        override suspend fun deletePageFile(page: PageKey, expectedHash: String) =
            router.withWriter(target) { it.deletePageFile(page, expectedHash) }
        override suspend fun fileHash(page: PageKey) = router.withWriter(target) { it.fileHash(page) }
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
            router.withWriter(target) { it.removeBlocks(page, uuids, expectedContentHashes) }
    }

    @Test
    fun `apply then undo through the router, with the target active at undo time`() = realTime {
        val files = FakeRelocationFileSystem()
        val root = "/data/${dst.value}"
        files.writeFileBytes("$root/pages/Ex.md", "- keep".encodeToByteArray())
        val writer = MarkdownTargetWriter(
            files, OffGraphTarget(dst, root, isActive = false), TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
        ) { it }
        val env = Env(target = writer)
        val source = FakeSource(listOf(entry(1, "Fresh"), entry(2, "Ex")))

        val result = env.service.apply(env.plan(source)).ok()
        assertEquals(1, result.newPages)
        assertEquals(1, result.combinedPages)
        assertNotNull(files.readFileBytes("$root/pages/Fresh.md"))
        assertTrue("body Ex" in files.readFileBytes("$root/pages/Ex.md")!!.decodeToString())

        env.manager.switchGraph(dst)
        env.manager.awaitPendingMigration()
        assertEquals(dst, env.manager.readyGraphId, "target is the active graph at undo time")

        val undo = MergeUndo(MergeManifestStore(env.okio, "/app"), { RoutedWriter(env.router, it) }, { 2_000L })
        val done = assertIs<UndoResult.Done>(undo.undo(MergeId(result.mergeId)))

        assertEquals(1, done.filesDeleted)
        assertEquals(1, done.blocksRemoved)
        assertTrue(done.issues.isEmpty(), "issues: ${done.issues}")
        assertNull(files.readFileBytes("$root/pages/Fresh.md"))
        val ex = files.readFileBytes("$root/pages/Ex.md")!!.decodeToString()
        assertTrue("keep" in ex && "body Ex" !in ex, ex)
    }
}
