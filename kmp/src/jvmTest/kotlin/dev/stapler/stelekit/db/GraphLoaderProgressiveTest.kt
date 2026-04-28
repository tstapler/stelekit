package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GraphLoaderProgressiveTest {

    private val fileSystem = object : FileSystem {
        val files = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = files.keys.filter { it.startsWith(path) }.map { it.substringAfterLast("/") }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val pageRepository = InMemoryPageRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

    // ── Warm-start fast path ──────────────────────────────────────────────────

    /**
     * When the page repository already has cached journals (same graph, second launch),
     * [GraphLoader.loadGraphProgressive] must call [onPhase1Complete] without reading any
     * file content from the filesystem — the DB is the authoritative fast path.
     */
    @Test
    fun `warm start fires onPhase1Complete before filesystem content is read`() = runBlocking {
        val fsReadCount = AtomicInteger(0)
        val trackingFs = object : FileSystem {
            val files = mutableMapOf(
                "/graph/journals/2026_04_13.md" to "- Journal entry"
            )
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                fsReadCount.incrementAndGet()
                return files[path]
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String) =
                files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = files.containsKey(path)
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 1000L
        }

        val warmPageRepo = InMemoryPageRepository()
        val warmBlockRepo = InMemoryBlockRepository()
        val now = Clock.System.now()

        // Pre-populate: simulate journals already in DB from a prior session
        warmPageRepo.savePage(
            Page(
                uuid = "journal-warm-1",
                name = "2026-04-13",
                filePath = "/graph/journals/2026_04_13.md",
                createdAt = now, updatedAt = now,
                isJournal = true,
                journalDate = LocalDate(2026, 4, 13)
            )
        )

        val loader = GraphLoader(trackingFs, warmPageRepo, warmBlockRepo)

        var phase1Called = false
        val fsReadsAtPhase1 = AtomicInteger(-1)

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = {
                    phase1Called = true
                    fsReadsAtPhase1.set(fsReadCount.get())
                },
                onFullyLoaded = {}
            )
        }

        assertTrue(phase1Called, "onPhase1Complete must be called on warm start")
        assertEquals(0, fsReadsAtPhase1.get(),
            "No file content must be read before onPhase1Complete fires on warm start")

        loader.cancelBackgroundWork()
    }

    /**
     * On a cold start (empty repository), [loadGraphProgressive] must read from the
     * filesystem to discover journals and call both [onPhase1Complete] and [onFullyLoaded].
     */
    @Test
    fun `cold start reads filesystem and calls both callbacks`() = runBlocking {
        val fsReadCount = AtomicInteger(0)
        val coldFs = object : FileSystem {
            val files = mutableMapOf(
                "/graph/journals/2026_04_13.md" to "- Cold start entry"
            )
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                fsReadCount.incrementAndGet()
                return files[path]
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String) =
                files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = files.containsKey(path)
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 1000L
        }

        val coldPageRepo = InMemoryPageRepository()
        val coldBlockRepo = InMemoryBlockRepository()
        val loader = GraphLoader(coldFs, coldPageRepo, coldBlockRepo)

        var phase1Called = false
        var fullyLoadedCalled = false

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = { phase1Called = true },
                onFullyLoaded = { fullyLoadedCalled = true }
            )
        }

        assertTrue(phase1Called, "onPhase1Complete must be called on cold start")
        assertTrue(fullyLoadedCalled, "onFullyLoaded must be called on cold start")
        assertTrue(fsReadCount.get() > 0, "Cold start must read file content from filesystem")
    }

    /**
     * [cancelBackgroundWork] must cancel the warm-reconcile job so [onFullyLoaded] is
     * never called after cancellation — the job that tracks background indexing in
     * [backgroundIndexJob] must be assigned before [loadGraphProgressive] returns.
     *
     * Strategy: block in [readFile] (called during background reconcile but NOT during
     * the main warm-path setup or [sanitizeDirectory], which only calls readFile when a
     * file needs renaming). The main path returns fast; we cancel before readFile unblocks.
     */
    @Test
    fun `cancelBackgroundWork on warm start prevents onFullyLoaded from being called`() = runBlocking {
        // Signal: background job has entered readFile
        val jobStartedLatch = CountDownLatch(1)
        // Gate: keeps the background job blocked until we release it
        val blockingLatch = CountDownLatch(1)

        val blockingFs = object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                // Signal that the background job has started file I/O
                jobStartedLatch.countDown()
                // Block until released — simulates slow SAF IPC call
                blockingLatch.await(5, TimeUnit.SECONDS)
                return "- content"
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String): List<String> =
                if (path.endsWith("/journals")) listOf("2026-04-13.md") else emptyList()
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = false
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 1000L
        }

        val cancelPageRepo = InMemoryPageRepository()
        val cancelBlockRepo = InMemoryBlockRepository()
        val now = Clock.System.now()

        cancelPageRepo.savePage(
            Page(
                uuid = "journal-cancel-1",
                name = "2026-04-13",
                filePath = "/graph/journals/2026-04-13.md",
                createdAt = now, updatedAt = now,
                isJournal = true,
                journalDate = LocalDate(2026, 4, 13)
            )
        )

        val loader = GraphLoader(blockingFs, cancelPageRepo, cancelBlockRepo)
        val fullyLoadedCalled = AtomicBoolean(false)

        // Warm path launches background job and returns immediately — well within 10s
        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = {},
                onFullyLoaded = { fullyLoadedCalled.set(true) }
            )
        }

        // Wait for the background job to enter readFile (filesystem I/O started)
        val started = jobStartedLatch.await(5, TimeUnit.SECONDS)
        assertTrue(started, "Background job should have started readFile within 5s")

        // Cancel the background job (simulates onTrimMemory)
        loader.cancelBackgroundWork()

        // Release the blocked thread — it will return but the coroutine is cancelled
        blockingLatch.countDown()

        // Give the coroutine time to observe cancellation and unwind
        Thread.sleep(500)

        assertFalse(fullyLoadedCalled.get(),
            "onFullyLoaded must NOT be called after cancelBackgroundWork() on warm start")
    }

    // ── sanitizeDirectory hot-path tests ─────────────────────────────────────

    /**
     * On a warm start, [loadGraphProgressive] must not call [listFiles] before
     * [onPhase1Complete] fires. Sanitize logic is deferred to the background job so the
     * warm-start fast path incurs zero SAF calls on the critical path.
     */
    @Test
    fun `warm start does not call listFiles before onPhase1Complete`() = runBlocking {
        val listFilesCount = AtomicInteger(0)
        val trackingFs = object : FileSystem {
            val files = mutableMapOf(
                "/graph/journals/2026_04_13.md" to "- Journal"
            )
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String) = files[path]
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String): List<String> {
                listFilesCount.incrementAndGet()
                return files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
            }
            override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
                listFilesCount.incrementAndGet()
                return listFiles(path).map { it to 1000L }
            }
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = files.containsKey(path)
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 1000L
        }

        val warmPageRepo = InMemoryPageRepository()
        val warmBlockRepo = InMemoryBlockRepository()
        val now = Clock.System.now()

        warmPageRepo.savePage(
            Page(
                uuid = "j-warm-1",
                name = "2026-04-13",
                filePath = "/graph/journals/2026_04_13.md",
                createdAt = now, updatedAt = now,
                isJournal = true,
                journalDate = LocalDate(2026, 4, 13)
            )
        )

        val loader = GraphLoader(trackingFs, warmPageRepo, warmBlockRepo)
        val listFilesCountAtPhase1 = AtomicInteger(-1)

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = { listFilesCountAtPhase1.set(listFilesCount.get()) },
                onFullyLoaded = {}
            )
        }

        assertEquals(0, listFilesCountAtPhase1.get(),
            "listFiles must not be called before onPhase1Complete on warm start; " +
            "sanitize must stay in the background job")
        loader.cancelBackgroundWork()
    }

    /**
     * On a cold start (empty DB), [loadGraphProgressive] must not call [listFiles] before
     * [onPhase1Complete]. Sanitize is deferred to Phase 2 so Phase 1 journal loading is
     * not preceded by extra directory scans.
     */
    @Test
    fun `cold start does not call listFiles before the Phase 1 journals are loaded`() = runBlocking {
        // sanitizeDirectory calls listFiles; scanDirectory (loadJournalsImmediate) calls
        // listFilesWithModTimes. Separate counters so the assertion precisely targets the
        // forbidden sanitize path without conflating it with the required scan.
        val sanitizeCallsBeforePhase1 = AtomicInteger(0)
        val phase1Fired = AtomicBoolean(false)

        val coldFs = object : FileSystem {
            val files = mutableMapOf(
                "/graph/journals/2026_04_13.md" to "- Cold entry"
            )
            private fun fileNamesIn(path: String) =
                files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }

            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String) = files[path]
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String): List<String> {
                // sanitizeDirectory uses listFiles — must be 0 before Phase 1
                if (!phase1Fired.get()) sanitizeCallsBeforePhase1.incrementAndGet()
                return fileNamesIn(path)
            }
            override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
                // scanDirectory uses listFilesWithModTimes — 1 call (loadJournalsImmediate) is allowed
                return fileNamesIn(path).map { it to 1000L }
            }
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = files.containsKey(path)
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 1000L
        }

        val loader = GraphLoader(coldFs, InMemoryPageRepository(), InMemoryBlockRepository())

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = { phase1Fired.set(true) },
                onFullyLoaded = {}
            )
        }

        assertEquals(0, sanitizeCallsBeforePhase1.get(),
            "sanitizeDirectory (listFiles) must not run before Phase 1 on cold start; " +
            "got ${sanitizeCallsBeforePhase1.get()} call(s)")
    }

    @Test
    fun `test progressive loading phases`() = runTest {
        // Setup 15 journals (immediate count is 10, so 5 should be background loaded)
        val journalsDir = "/graph/journals"
        for (i in 1..15) {
            val day = i.toString().padStart(2, '0')
            val content = "- Block $i"
            fileSystem.files["$journalsDir/2026_01_$day.md"] = content
        }

        var phase1Called = false
        var fullyLoadedCalled = false
        
        graphLoader.loadGraphProgressive(
            graphPath = "/graph",
            immediateJournalCount = 10,
            onProgress = {},
            onPhase1Complete = { 
                phase1Called = true
                // Verify immediate journals (10 most recent) are loaded FULLY
                // Recent: 15..06
                // Background: 05..01
            },
            onFullyLoaded = {
                fullyLoadedCalled = true
            }
        )
        
        // Wait for coroutines
        testScheduler.advanceUntilIdle()
        
        assertTrue(phase1Called, "Phase 1 callback should be called")
        assertTrue(fullyLoadedCalled, "Fully loaded callback should be called")
        
        // Check a recent journal (Phase 1)
        val recentPage = pageRepository.getPageByName("2026_01_15").first().getOrNull()
        assertTrue(recentPage != null, "Recent journal should be loaded")
        val recentBlocks = blockRepository.getBlocksForPage(recentPage!!.uuid).first().getOrNull()!!
        assertTrue(recentBlocks.isNotEmpty())
        assertTrue(recentBlocks[0].isLoaded, "Phase 1 blocks should be fully loaded")
        
        // Check an older journal (Phase 2 - Background)
        // 2026_01_01 should be in background
        val oldPage = pageRepository.getPageByName("2026_01_01").first().getOrNull()
        assertTrue(oldPage != null, "Background journal should be loaded")
        
        val oldBlocks = blockRepository.getBlocksForPage(oldPage!!.uuid).first().getOrNull()!!
        assertTrue(oldBlocks.isNotEmpty())
        // Note: GraphLoader.loadRemainingJournals currently uses METADATA_ONLY?
        // Let's check the code.
        // Yes: `parseAndSavePage(filePath, content, ParseMode.METADATA_ONLY)`
        assertFalse(oldBlocks[0].isLoaded, "Phase 2 background blocks should be METADATA_ONLY")
        
        // Test lazy loading
        graphLoader.loadFullPage(oldPage.uuid)
        testScheduler.advanceUntilIdle()
        
        val reloadedBlocks = blockRepository.getBlocksForPage(oldPage.uuid).first().getOrNull()!!
        assertTrue(reloadedBlocks[0].isLoaded, "Block should be fully loaded after loadFullPage")
    }
}
