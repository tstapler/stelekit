// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 1.5b: the merge unit's reload is chunked, observable, and contains any non-cancellation failure. */
class GitSyncMergeUnitContainmentTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val sink = LogManager.addSink(object : LogSink {
        override fun write(entry: LogEntry, formatted: String) { logLines += formatted }
    })
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        sink.close()
    }

    private class MergingRepo(private val changed: List<String>) : StubGitRepository() {
        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(hasRemoteChanges = true, remoteCommitCount = 1).right()
        override suspend fun merge(config: GitConfig) = MergeResult(false, emptyList(), changed, 1).right()
        override suspend fun push(config: GitConfig) = Unit.right()
    }

    private fun pages(n: Int) = (1..n).map { "/repo/pages/p$it.md" }

    private fun service(paths: List<String>, fs: StubFileSystem): GitSyncService =
        buildGitSyncTestService(
            gitRepository = MergingRepo(paths),
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            isOnline = { true },
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
        )

    @Test
    fun reload_should_ChunkAtMost200AndSetReloadIncomplete_When_BeginGitMergeThrows() = runBlocking<Unit> {
        val seen = AtomicInteger()
        val fs = object : StubFileSystem() {
            // An Error is not an Exception, so reloadFiles' per-file guard lets it through, as a closed driver would.
            override fun invalidateShadow(path: String) {
                if (seen.incrementAndGet() == 250) throw IllegalStateException("driver closed").let { LinkageError("closed", it) }
            }
        }
        val svc = service(pages(450), fs)

        val result = svc.sync("g")

        assertTrue(result.isRight(), "the failure stays inside the unit, got $result")
        assertTrue(svc.reloadIncomplete.value)
        assertEquals(250, seen.get(), "chunk 1 (200) completed, chunk 2 stopped at its 50th file")
        assertTrue(logLines.any { "reload progress done=200/450" in it }, logLines.toString())
        assertFalse(logLines.any { "reload progress done=400/450" in it })
        assertTrue(logLines.any { "reload aborted reason=LinkageError" in it }, logLines.toString())
        assertEquals(emptyList(), uncaught.toList())
        svc.shutdown()
    }

    @Test
    fun reload_should_LogProgressPerChunkAndStayComplete_When_ThousandPathsReload() = runBlocking<Unit> {
        val svc = service(pages(1000), StubFileSystem())

        svc.sync("g")

        val progress = logLines.mapNotNull { Regex("reload progress done=(\\d+)/1000").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(listOf(200, 400, 600, 800, 1000), progress)
        assertFalse(svc.reloadIncomplete.value)
        svc.shutdown()
    }
}
