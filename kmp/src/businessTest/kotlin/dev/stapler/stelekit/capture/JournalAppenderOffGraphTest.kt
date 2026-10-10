package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.merge.MarkdownTargetWriter
import dev.stapler.stelekit.merge.MergeFixtures
import dev.stapler.stelekit.merge.OffGraphTarget
import dev.stapler.stelekit.merge.RoundTripGuard
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class JournalAppenderOffGraphTest {
    private val active = GraphId("aaaaaaaaaaaaaaaa")
    private val work = GraphId("bbbbbbbbbbbbbbbb")
    private val workRoot = "/graphs/work"
    private val day = LocalDate(2026, 10, 7)
    private val c1 = "00000000-0000-4000-8000-000000000001"

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val managers = mutableListOf<GraphManager>()
    private val targetFs = FakeRelocationFileSystem()
    private val inboxFs = InboxFakeFileSystem { 1_000L }
    private val inbox = ShareInbox(inboxFs, "/private/share-inbox", { 1_000L }, Dispatchers.Unconfined)

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(workPath: String = workRoot): GraphManager {
        val graphs = listOf(active to "/graphs/active", work to workPath)
            .map { (id, path) -> GraphInfo(id = id, path = path, displayName = id.value, addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = active, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
    }

    private class Fixture(val appender: JournalAppender, val raw: RouterOffGraphRoute)

    private fun fixture(
        m: GraphManager,
        capabilities: TargetWriterCapabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
    ): Fixture {
        val router = TargetWriterRouter(
            graphManager = m,
            locator = RegistryGraphLocator(m.graphRegistry),
            capabilities = capabilities,
            activeWriterFor = { error("target is not the ready graph in these tests") },
            offGraphWriterFor = { info ->
                MarkdownTargetWriter(
                    targetFs,
                    OffGraphTarget(info.id, info.path, isActive = false),
                    capabilities,
                    MarkdownTargetWriter.NoSymlinks,
                )
            },
        )
        val raw = RouterOffGraphRoute(router, RegistryGraphLocator(m.graphRegistry), AssetCopier(targetFs)) { day }
        return Fixture(JournalAppender(m, PlatformFileSystem(), InboxFallbackAppender(raw, inbox)), raw)
    }

    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    private fun stems() = targetFs.listFiles("$workRoot/journals").map { it.substringBefore('.') }

    private fun journalPath(): String =
        PageFileResolver.resolveJournal(day, workRoot, stems()).fold({ error("unresolvable: $it") }, { it })

    private fun seedJournal(stem: String, text: String): String {
        val path = "$workRoot/journals/$stem.md"
        targetFs.writeFile(path, text)
        return path
    }

    private suspend fun ready(): Fixture {
        val m = newManager()
        m.awaitPendingMigration()
        return fixture(m)
    }

    private val target get() = CaptureTarget.NamedGraph(work)

    @Test
    fun append_should_AppendOneBlockWithCaptureUuid_When_InactiveGraphHasJournal() = realTime {
        val f = ready()
        val path = seedJournal("2026_10_07", "- existing\n")

        val outcome = f.appender.append(target, "hello", c1)

        assertIs<AppendOutcome.AppendedOffGraph>(outcome)
        assertEquals(path, journalPath())
        assertEquals("- existing\n- hello\n  id:: $c1\n", targetFs.readFile(path))
    }

    @Test
    fun append_should_ReturnAlreadyPresent_And_NotRewrite_When_CaptureIdReplayed() = realTime {
        val f = ready()
        val path = seedJournal("2026_10_07", "- existing\n")
        f.appender.append(target, "hello", c1)
        val afterFirst = targetFs.readFile(path)

        val second = f.appender.append(target, "hello", c1)

        assertEquals(AppendOutcome.AlreadyPresent, second)
        assertEquals(afterFirst, targetFs.readFile(path))
    }

    @Test
    fun append_should_ReuseDashSeparatedStem_When_ThatJournalExists() = realTime {
        val f = ready()
        val path = seedJournal("2026-10-07", "- existing\n")

        assertIs<AppendOutcome.AppendedOffGraph>(f.appender.append(target, "hello", c1))

        assertEquals(path, journalPath())
        assertEquals(listOf("2026-10-07"), stems())
        assertTrue(targetFs.readFile(path)!!.contains("- hello"))
    }

    @Test
    fun append_should_CreateJournalAtResolverPath_When_NoneExists() = realTime {
        val f = ready()
        val expected = journalPath()

        assertIs<AppendOutcome.AppendedOffGraph>(f.appender.append(target, "hello", c1))

        assertEquals(listOf(expected.substringAfterLast('/').substringBefore('.')), stems())
        assertEquals("- hello\n  id:: $c1\n", targetFs.readFile(expected))
        assertEquals(day, dev.stapler.stelekit.outliner.JournalUtils.parseJournalDate(stems().single()))
    }

    @Test
    fun append_should_QueuePermission_And_KeepText_When_GrantRevoked() = realTime {
        val m = newManager(workPath = "saf://tree/work")
        m.awaitPendingMigration()
        val f = fixture(m, TargetWriterCapabilities(platformSupportsOffGraphWrite = true, hasVerifiedSafGrant = { false }))

        val outcome = f.appender.appendContent(target, ShareContent("hello"), c1)

        assertEquals(AppendOutcome.Queued("permission"), outcome)
        assertEquals(1, inbox.state.value.pendingCount(work))
        assertEquals("hello", inbox.readContent(InboxSlot.Graph(work), c1).getOrNull()?.text)
        assertTrue(targetFs.allFilePaths().isEmpty())
    }

    @Test
    fun append_should_QueueNotRoundTrippable_And_NeverWrite_When_GuardFails() = realTime {
        val f = ready()
        val path = seedJournal("2026_10_07", MergeFixtures.FENCE_WITH_DASH_LINES)

        val outcome = f.appender.append(target, "hello", c1)

        assertEquals(AppendOutcome.Queued("not-round-trippable"), outcome)
        assertEquals(MergeFixtures.FENCE_WITH_DASH_LINES, targetFs.readFile(path))
        assertEquals(1, inbox.state.value.pendingCount(work))
    }

    @Test
    fun appendContent_should_StoreImageUnderTargetAssets_And_LinkIt() = realTime {
        val f = ready()
        val image = byteArrayOf(1, 2, 3, 4, 5)

        val outcome = f.appender.appendContent(target, ShareContent("look", image, "image/png"), c1)

        val written = assertIs<AppendOutcome.AppendedOffGraph>(outcome)
        val asset = targetFs.allFilePaths().single { it.startsWith("$workRoot/assets/") }
        assertTrue(asset.endsWith(".png"))
        assertContentEquals(image, targetFs.readFileBytes(asset))
        val link = "![image](../assets/${asset.substringAfterLast('/')})"
        assertTrue(targetFs.readFile(written.pagePath)!!.contains(link), targetFs.readFile(written.pagePath))
    }

    @Test
    fun append_should_MatchGuardVerdict_ForEverySpikeFixture() = realTime {
        val fixtures = mapOf(
            "REAL_CRLF_TAB" to MergeFixtures.REAL_CRLF_TAB,
            "REAL_CRLF_CLEAN" to MergeFixtures.REAL_CRLF_CLEAN,
            "UNLABELED_FLAT" to MergeFixtures.UNLABELED_FLAT,
            "UNLABELED_NESTED" to MergeFixtures.UNLABELED_NESTED,
            "MIXED_LABELED" to MergeFixtures.MIXED_LABELED,
            "SPACE_INDENTED" to MergeFixtures.SPACE_INDENTED,
            "FENCE_WITH_DASH_LINES" to MergeFixtures.FENCE_WITH_DASH_LINES,
        )
        val f = ready()
        val path = "$workRoot/journals/2026_10_07.md"
        val verdicts = mutableMapOf<String, Boolean>()
        for ((name, text) in fixtures) {
            targetFs.writeFile(path, text)
            val passes = RoundTripGuard.probe(text, path, isJournal = true).isRight()
            verdicts[name] = passes

            val outcome = f.appender.append(target, "hello $name", "00000000-0000-4000-8000-0000000000${verdicts.size.toString().padStart(2, '0')}")

            if (passes) {
                assertIs<AppendOutcome.AppendedOffGraph>(outcome, name)
                assertTrue(targetFs.readFile(path)!!.contains("hello $name"), name)
            } else {
                assertEquals(AppendOutcome.Queued("not-round-trippable"), outcome, name)
                assertEquals(text, targetFs.readFile(path), "$name was written despite failing the guard")
            }
        }
        assertEquals(false, verdicts.getValue("FENCE_WITH_DASH_LINES"))
        assertEquals(true, verdicts.getValue("UNLABELED_FLAT"))
        assertNotNull(verdicts.entries.firstOrNull { it.value })
        assertFalse(verdicts.isEmpty())
    }

    @Test
    fun blockUuid_should_PassUuidThrough_And_HashOtherIds() {
        assertEquals(c1, OffGraphCapture.blockUuid(c1.uppercase()))
        val hashed = OffGraphCapture.blockUuid("not-a-uuid")
        assertEquals(hashed, OffGraphCapture.blockUuid("not-a-uuid"))
        assertTrue(hashed.length == 36)
    }

    private class Scripted(val outcome: AppendOutcome) : OffGraphContentRoute {
        override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?) = outcome
    }

    @Test
    fun drainAdapter_should_MapOutcomes_ToDrainResults() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        suspend fun adapt(outcome: AppendOutcome) =
            JournalInboxAppender(JournalAppender(m, PlatformFileSystem(), Scripted(outcome))).append(work, ShareContent("t"), c1)

        assertEquals(DrainAppendResult.AlreadyPresent, adapt(AppendOutcome.AlreadyPresent))
        assertEquals(DrainAppendResult.Appended, adapt(AppendOutcome.AppendedOffGraph(work, "/p")))
        assertEquals(DrainAppendResult.Retry("permission"), adapt(AppendOutcome.Deferred("permission")))
        assertEquals(DrainAppendResult.Retry("q"), adapt(AppendOutcome.Queued("q")))
        assertEquals(DrainAppendResult.Failed("not-round-trippable"), adapt(AppendOutcome.Deferred("not-round-trippable", permanent = true)))
        assertEquals(DrainAppendResult.Failed("boom"), adapt(AppendOutcome.Failed("boom")))
    }
}
