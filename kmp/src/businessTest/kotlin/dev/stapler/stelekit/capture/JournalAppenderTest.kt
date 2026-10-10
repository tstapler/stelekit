package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class JournalAppenderTest {
    private val active = GraphId("aaaaaaaaaaaaaaaa")
    private val other = GraphId("bbbbbbbbbbbbbbbb")
    private val captureId = "00000000-0000-4000-8000-000000000001"

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val managers = mutableListOf<GraphManager>()
    private val fileSystem = PlatformFileSystem()

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(activeId: GraphId? = active): GraphManager {
        val graphs = listOf(active, other).map {
            val path = Files.createTempDirectory("journal-appender-${it.value}").toString()
            fileSystem.registerGraphRoot(path)
            GraphInfo(id = it, path = path, displayName = it.value, addedAt = 0L)
        }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = activeId, graphs = graphs)))
        return GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = fileSystem,
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
    }

    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    private suspend fun readyAppender(): Pair<JournalAppender, GraphManager> {
        val m = newManager()
        m.awaitPendingMigration()
        return JournalAppender(m, fileSystem) to m
    }

    private suspend fun GraphManager.contentCount(pageUuid: PageUuid, text: String): Int =
        getActiveRepositorySet()!!.blockRepository.getBlocksForPage(pageUuid).first().getOrNull().orEmpty()
            .count { it.content == text }

    @Test
    fun append_should_AddOneBlockWithCaptureIdUuid_When_ActiveGraph() = realTime {
        val (appender, m) = readyAppender()

        val outcome = assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.ActiveGraph, "hello", captureId))

        assertTrue(outcome.saved.page.isJournal)
        assertEquals(BlockUuid(captureId), outcome.saved.block?.uuid)
        assertEquals(1, m.contentCount(outcome.saved.page.uuid, "hello"))
        assertEquals(active, outcome.graphId)
    }

    @Test
    fun append_should_ReturnAlreadyPresent_And_KeepOneBlock_When_CaptureIdReplayed() = realTime {
        val (appender, m) = readyAppender()
        val first = assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.ActiveGraph, "hello", captureId))

        val second = appender.append(CaptureTarget.ActiveGraph, "hello", captureId)

        assertEquals(AppendOutcome.AlreadyPresent, second)
        assertEquals(1, m.contentCount(first.saved.page.uuid, "hello"))
    }

    @Test
    fun append_should_AppendTwice_When_NoCaptureId() = realTime {
        val (appender, m) = readyAppender()
        val first = assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.ActiveGraph, "hello"))
        assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.ActiveGraph, "hello"))

        assertEquals(2, m.contentCount(first.saved.page.uuid, "hello"))
    }

    @Test
    fun append_should_ReturnNoActiveGraphFailure_When_NothingActive() = realTime {
        val m = newManager(activeId = null)
        m.awaitPendingMigration()

        val failed = assertIs<AppendOutcome.Failed>(JournalAppender(m, fileSystem).append(CaptureTarget.ActiveGraph, "x"))

        assertEquals(CaptureResult.NoActiveGraph, failed.cause)
    }

    @Test
    fun append_should_ReturnNotSupportedYet_When_NamedGraphIsNotActiveAndNoRoute() = realTime {
        val (appender, _) = readyAppender()

        val failed = assertIs<AppendOutcome.Failed>(appender.append(CaptureTarget.NamedGraph(other), "x", captureId))

        assertEquals(JournalAppender.NOT_SUPPORTED_YET, failed.error)
    }

    @Test
    fun append_should_DelegateToRoute_When_NamedGraphIsNotActive() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        val seen = mutableListOf<Triple<GraphId, String, String?>>()
        val appender = JournalAppender(m, fileSystem, OffGraphAppendRoute { id, text, cid ->
            seen += Triple(id, text, cid)
            AppendOutcome.Queued("target busy")
        })

        val outcome = appender.append(CaptureTarget.NamedGraph(other), "x", captureId)

        assertEquals(AppendOutcome.Queued("target busy"), outcome)
        assertEquals(listOf<Triple<GraphId, String, String?>>(Triple(other, "x", captureId)), seen)
    }

    @Test
    fun append_should_UseActivePath_When_NamedGraphIsTheActiveGraph() = realTime {
        val (appender, _) = readyAppender()

        assertIs<AppendOutcome.Appended>(appender.append(CaptureTarget.NamedGraph(active), "x", captureId))
    }

    @Test
    fun append_should_WriteTheFile_When_RetryFollowsAFailedPageFlush() = realTime {
        val m = newManager()
        m.awaitPendingMigration()
        // A file system whose graph root is not registered refuses the page flush after the DB write landed.
        val failed = JournalAppender(m, PlatformFileSystem()).append(CaptureTarget.ActiveGraph, "partial", captureId)
        assertIs<AppendOutcome.Failed>(failed)

        val retry = JournalAppender(m, fileSystem).append(CaptureTarget.ActiveGraph, "partial", captureId)

        // AlreadyPresent here would let the inbox drop the item while the file never got the block.
        val graphPath = m.getActiveGraphInfo()!!.path
        val inFiles = Files.walk(java.nio.file.Path.of(graphPath)).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }
                .anyMatch { Files.readString(it).contains("partial") }
        }
        assertTrue(inFiles, "retry reported $retry but no journal file contains the block")
    }

    @Test
    fun activePathKeepsEveryBlockOfTodaysJournalWhenItsRowIsAnUnloadedStub() = realTime {
        val (appender, manager) = readyAppender()
        val graphPath = manager.getActiveGraphInfo()!!.path
        val today = kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
        val name = today.toString().replace('-', '_')
        val file = "$graphPath/journals/$name.md"
        java.io.File(file).also { it.parentFile.mkdirs() }.writeText("- keep me 1\n- keep me 2\n- keep me 3\n")
        val now = kotlin.time.Clock.System.now()
        manager.getActiveRepositorySet()!!.writeActor!!.savePage(
            dev.stapler.stelekit.model.Page(
                uuid = PageUuid(dev.stapler.stelekit.util.UuidGenerator.generateV7()), name = name, filePath = file,
                createdAt = now, updatedAt = now, isJournal = true, journalDate = today, isContentLoaded = false,
            ),
        )

        val outcome = appender.append(CaptureTarget.ActiveGraph, "hello", captureId)

        assertIs<AppendOutcome.Appended>(outcome)
        assertEquals(
            listOf("keep me 1", "keep me 2", "keep me 3", "hello"),
            java.io.File(file).readLines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") },
        )
    }
}
