@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.query.QueryFilter
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.time.Clock

/**
 * Regression guard for live query blocks across `GraphManager.switchGraph()`: the executor must be
 * re-derived from the reactive `activeRepositorySet` (it is a per-RepositorySet value), and a
 * collector bound to the previous graph must neither crash nor leak its data into the new graph.
 */
class QueryExecutorGraphSwitchTest {
    private var originalDevDataDir: String? = null
    private lateinit var tempDataDir: java.io.File

    @BeforeTest
    fun isolateDataDir() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        tempDataDir = createTempDirectory("stelekit_query_switch_test_").toFile()
        System.setProperty("stelekit.devDataDir", tempDataDir.absolutePath)
    }

    @AfterTest
    fun restoreDataDir() {
        originalDevDataDir?.let { System.setProperty("stelekit.devDataDir", it) }
            ?: System.clearProperty("stelekit.devDataDir")
        tempDataDir.deleteRecursively()
    }

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubFileSystem : FileSystem {
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
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    private suspend fun seed(repos: RepositorySet, blockUuid: String, content: String) {
        val now = Clock.System.now()
        repos.pageRepository.savePage(Page(uuid = PageUuid("page-$blockUuid"), name = "P-$blockUuid", createdAt = now, updatedAt = now))
        repos.blockRepository.saveBlocks(
            listOf(
                Block(
                    uuid = BlockUuid(blockUuid), pageUuid = PageUuid("page-$blockUuid"), content = content,
                    position = "a0", createdAt = now, updatedAt = now,
                )
            )
        )
    }

    @Test
    fun `executor is re-derived after switchGraph and never shows the previous graph's data`() = runBlocking {
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )
        val graphBId = graphManager.addGraph("/test/graphB")
        val repoA = graphManager.openGraph("/test/graphA")
        seed(repoA, "a-block", "NOW task in graph A")

        val query = QueryFilter.Task(setOf("NOW"))
        val executorA = repoA.queryExecutor
        assertEquals(
            listOf("a-block"),
            (executorA.executeQuery(query).first() as Either.Right).value.map { it.uuid.value },
        )

        var collectorCrashed = false
        val seenByA = CopyOnWriteArrayList<List<String>>()
        val collector = launch(Dispatchers.Default) {
            try {
                executorA.executeQuery(query).collect { either -> either.onRight { seenByA += it.map { b -> b.uuid.value } } }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) collectorCrashed = true
            }
        }

        graphManager.switchGraph(graphBId)
        val repoB = withTimeout(30_000) {
            var current = graphManager.activeRepositorySet.value
            while (current == null || current === repoA) {
                delay(50)
                current = graphManager.activeRepositorySet.value
            }
            current
        }
        assertNotNull(repoB)
        assertNotSame(repoA.queryExecutor, repoB.queryExecutor, "each graph needs its own executor")

        seed(repoB, "b-block", "NOW task in graph B")
        val fromB = (repoB.queryExecutor.executeQuery(query).first() as Either.Right).value.map { it.uuid.value }
        assertEquals(listOf("b-block"), fromB, "new graph's executor must only see graph B data")

        collector.cancelAndJoin()
        assertFalse(collectorCrashed, "collector bound to the closed graph must not crash")
        assertFalse(seenByA.any { "b-block" in it }, "graph A's collector must never see graph B's data")

        graphManager.shutdown()
    }
}
