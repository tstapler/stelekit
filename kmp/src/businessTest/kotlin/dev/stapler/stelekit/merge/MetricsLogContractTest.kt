// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.capture.AppendOutcome
import dev.stapler.stelekit.capture.CaptureTarget
import dev.stapler.stelekit.capture.InboxFakeFileSystem
import dev.stapler.stelekit.capture.InboxFallbackAppender
import dev.stapler.stelekit.capture.JournalAppender
import dev.stapler.stelekit.capture.RouterOffGraphRoute
import dev.stapler.stelekit.capture.ShareInbox
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.fakefilesystem.FakeFileSystem
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the S15 log lines that are the local-only source for metrics M1-M5, and that they carry no page
 * body or shared text. On disk a line is `HH:mm:ss.SSS [LEVEL] Tag: message`.
 *
 * Reproduce M1-M3 from the owner's logs (`~/.stelekit/logs/stelekit-YYYY-MM-DD.log`):
 * `grep -hE 'PageMergeService: merge.summary|MergeUndo: merge.undo|JournalAppender: share.append' ~/.stelekit/logs/stelekit-*.log`
 * (M1 = count of `merge.summary`; M2 = `share.append` with `override=true` and `target` != active, or `writer=markdown`;
 * M3 = tally of `outcome=` on those; M4 = `merge.undo` vs `merge.summary`; M5 = `conflicted` / `combined`).
 */
class MetricsLogContractTest {
    private val active = GraphId("aaaaaaaaaaaaaaaa")
    private val other = GraphId("bbbbbbbbbbbbbbbb")
    private val otherRoot = "/graphs/other"
    private val day = LocalDate(2026, 10, 7)
    private val secretBody = "SECRET-PAGE-BODY-7f3a"
    private val secretShare = "SECRET-SHARE-TEXT-9c1d"
    private val epoch = Instant.fromEpochMilliseconds(0)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val lines = mutableListOf<LogEntry>()
    private val sink = LogManager.addSink(object : LogSink {
        override fun write(entry: LogEntry, formatted: String) { synchronized(lines) { lines += entry } }
    })
    private val managers = mutableListOf<GraphManager>()
    private val services = mutableListOf<PageMergeService>()
    private val fs = PlatformFileSystem()
    private val targetFs = FakeRelocationFileSystem()
    private val inbox = ShareInbox(InboxFakeFileSystem { 1_000L }, "/private/share-inbox", { 1_000L }, Dispatchers.Unconfined)

    @AfterTest
    fun cleanup() {
        sink.close()
        services.forEach { it.close() }
        managers.forEach { it.shutdown() }
    }

    private fun manager(): GraphManager {
        val graphs = listOf(active to "active", other to otherRoot).map { (id, name) ->
            val path = if (id == other) name else Files.createTempDirectory("metrics-$name").toString().also(fs::registerGraphRoot)
            GraphInfo(id = id, path = path, displayName = name, addedAt = 0L)
        }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = active, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = fs,
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
    }

    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    /** `key=value` pairs of the single [tag] line starting with [prefix], in emitted order. */
    private fun fields(tag: String, prefix: String): List<Pair<String, String>> {
        val matching = synchronized(lines) { lines.filter { it.tag == tag && it.message.startsWith(prefix) } }
        assertEquals(1, matching.size, "expected exactly one '$prefix' line from $tag, got ${matching.map { it.message }}")
        return matching.single().message.removePrefix(prefix).trim().split(' ').map { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun clearLines() = synchronized(lines) { lines.clear() }

    private fun assertNoContentLeak() {
        val all = synchronized(lines) { lines.map { it.message } }
        assertTrue(all.isNotEmpty())
        all.forEach { assertFalse(secretBody in it || secretShare in it, "content leaked into log line: $it") }
    }

    private class FakeSource(private val entries: List<Pair<Page, List<Block>>>) : PageSource {
        private val byUuid = entries.associateBy { it.first.uuid }
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int): Either<DomainError, List<Page>> =
            entries.sortedBy { it.first.name }.drop(offset).take(limit).map { it.first }.right()
        override suspend fun countPages(filter: SelectionFilter, search: String?) = entries.size.toLong().right()
        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> =
            uuids.mapNotNull { byUuid[it] }.map { SourcePage(it.first, it.second) }.right()
    }

    private fun entry(n: Int, name: String): Pair<Page, List<Block>> {
        val page = Page(uuid = PageUuid("00000000-0000-0000-0001-%012x".format(n)), name = name, createdAt = epoch, updatedAt = epoch)
        val u = "00000000-0000-0000-0002-%012x".format(n)
        val block = Block(
            uuid = BlockUuid(u), pageUuid = page.uuid, content = secretBody, position = "a0",
            createdAt = epoch, updatedAt = epoch, properties = mapOf("id" to u),
        )
        return page to listOf(block)
    }

    @Test
    fun `copy run and undo log the exact S15 key sets and no page body`() = realTime {
        val m = manager().also { it.awaitPendingMigration() }
        val writer = MarkdownTargetWriter(
            targetFs, OffGraphTarget(other, otherRoot, isActive = false), TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
        ) { it }
        val router = TargetWriterRouter(
            graphManager = m,
            locator = RegistryGraphLocator(m.graphRegistry),
            capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
            activeWriterFor = { writer },
            offGraphWriterFor = { writer },
        )
        val okio = FakeFileSystem()
        val service = PageMergeService(router, okio, "/app", { emptyList<Page>().right() }, null, graphRoot = { "/data/${it.value}" }, nowEpochMs = { 1_000L })
            .also { services += it }
        val request = PlanRequest(PageSelection(), active, other, "A", LinkClosurePolicy.Off)

        val plan = (service.plan(request, FakeSource(listOf(entry(1, "One"), entry(2, "Two")))) as Either.Right).value
        val result = (service.apply(plan) as Either.Right).value

        assertEquals(
            listOf("mergeId", "source", "target", "direction", "new", "combined", "unchanged", "conflicted", "failed", "assetsRenamed"),
            fields("PageMergeService", "merge.summary").map { it.first },
        )
        assertEquals(2, result.newPages)

        MergeUndo(MergeManifestStore(okio, "/app"), { writer }, { 2_000L }).undo(MergeId(result.mergeId))

        val undo = fields("MergeUndo", "merge.undo")
        assertEquals(listOf("mergeId", "target", "removedPages", "removedBlocks", "leftInPlace"), undo.map { it.first })
        assertEquals("2", undo.toMap().getValue("removedPages"))
        assertNoContentLeak()
    }

    private val shareKeys = listOf("target", "writer", "override", "outcome")

    private fun journalPath(): String =
        PageFileResolver.resolveJournal(day, otherRoot, targetFs.listFiles("$otherRoot/journals").map { it.substringBefore('.') })
            .fold({ error("unresolvable: $it") }, { it })

    @Test
    fun `share logs the exact JournalAppender key set on the active, markdown and inbox paths`() = realTime {
        val m = manager().also { it.awaitPendingMigration() }
        val caps = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)
        val router = TargetWriterRouter(
            graphManager = m,
            locator = RegistryGraphLocator(m.graphRegistry),
            capabilities = caps,
            activeWriterFor = { error("target is not the ready graph in these tests") },
            offGraphWriterFor = { info ->
                MarkdownTargetWriter(targetFs, OffGraphTarget(info.id, info.path, isActive = false), caps, MarkdownTargetWriter.NoSymlinks)
            },
        )
        val raw = RouterOffGraphRoute(router, RegistryGraphLocator(m.graphRegistry), AssetCopier(targetFs)) { day }
        val appender = JournalAppender(m, fs, InboxFallbackAppender(raw, inbox))
        val id = "00000000-0000-4000-8000-00000000000%d"

        assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.ActiveGraph, secretShare, id.format(1)))
        var f = fields("JournalAppender", "share.append")
        assertEquals(shareKeys, f.map { it.first })
        assertEquals(mapOf("target" to active.value, "writer" to "active", "override" to "false", "outcome" to "Appended"), f.toMap())

        clearLines()
        assertIs<AppendOutcome.AppendedOffGraph>(appender.append(CaptureTarget.NamedGraph(other), secretShare, id.format(2)))
        f = fields("JournalAppender", "share.append")
        assertEquals(shareKeys, f.map { it.first })
        assertEquals(mapOf("target" to other.value, "writer" to "markdown", "override" to "true", "outcome" to "Appended"), f.toMap())

        clearLines()
        targetFs.writeFile(journalPath(), MergeFixtures.FENCE_WITH_DASH_LINES)
        assertIs<AppendOutcome.Queued>(appender.append(CaptureTarget.NamedGraph(other), secretShare, id.format(3)))
        f = fields("JournalAppender", "share.append")
        assertEquals(shareKeys, f.map { it.first })
        assertEquals("Queued", f.toMap().getValue("outcome"))
        assertEquals("markdown", f.toMap().getValue("writer"))
        assertNoContentLeak()
    }
}
