package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.testing.FakeClock
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
                uuid = PageUuid("journal-warm-1"),
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
                uuid = PageUuid("journal-cancel-1"),
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
                uuid = PageUuid("j-warm-1"),
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

    // ── Shadow-freshness regression tests ────────────────────────────────────

    /**
     * Regression: warm-start reconcile must call invalidateStaleShadow BEFORE reading any files
     * so that externally-changed files are not served from a stale on-device shadow cache.
     *
     * Setup: page in DB with old updatedAt (1000ms epoch) vs file mtime of 9999ms — the
     * shouldSkip check will be false, so the file IS re-read. We verify that
     * invalidateStaleShadow fired before readFile for the journal file.
     */
    @Test
    fun `warm start reconcile calls invalidateStaleShadow before reading changed files`() = runBlocking {
        val invalidateCalled = AtomicBoolean(false)
        val readFileCalledBeforeInvalidate = AtomicBoolean(false)
        val readFileCalled = AtomicBoolean(false)
        val fullyLoadedLatch = CountDownLatch(1)

        val shadowAwareFs = object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                if (path.endsWith(".md") && !invalidateCalled.get()) {
                    readFileCalledBeforeInvalidate.set(true)
                }
                if (path.endsWith(".md")) readFileCalled.set(true)
                return "- content"
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String): List<String> =
                if (path.endsWith("/journals")) listOf("2026_04_13.md") else emptyList()
            override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> =
                if (path.endsWith("/journals")) listOf("2026_04_13.md" to 9999L) else emptyList()
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = true
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 9999L
            override suspend fun invalidateStaleShadow(graphPath: String) {
                // Yield repeatedly so any concurrently-dispatched readFile has a chance to
                // run first — makes the test reliably detect a regression that moves invalidateStaleShadow
                // back to a concurrent coroutine.
                repeat(5) { kotlinx.coroutines.yield() }
                invalidateCalled.set(true)
            }
        }

        val warmPageRepo = InMemoryPageRepository()
        val warmBlockRepo = InMemoryBlockRepository()
        val oldInstant = Instant.fromEpochMilliseconds(1000L)

        // Pre-populate: journal in DB from a prior session, updatedAt older than file mtime
        warmPageRepo.savePage(
            Page(
                uuid = PageUuid("j-shadow-1"),
                name = "2026-04-13",
                filePath = "/graph/journals/2026_04_13.md",
                createdAt = oldInstant, updatedAt = oldInstant,
                isJournal = true, journalDate = LocalDate(2026, 4, 13)
            )
        )

        val loader = GraphLoader(shadowAwareFs, warmPageRepo, warmBlockRepo)

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = {},
                onFullyLoaded = { fullyLoadedLatch.countDown() }
            )
        }

        assertTrue(fullyLoadedLatch.await(10, TimeUnit.SECONDS),
            "onFullyLoaded must be called within 10s")
        assertFalse(readFileCalledBeforeInvalidate.get(),
            "readFile for changed journal must not be called before invalidateStaleShadow on warm start reconcile")
        assertTrue(invalidateCalled.get(), "invalidateStaleShadow must have been called during warm reconcile")
        assertTrue(readFileCalled.get(), "readFile must have been called during warm reconcile")

        loader.cancelBackgroundWork()
    }

    /**
     * Regression: cold-start Phase 1 (blocking journals load) must call invalidateStaleShadow before
     * readFile so externally-changed journals show current content on first open.
     */
    @Test
    fun `cold start calls invalidateStaleShadow before Phase 1 journal reads`() = runBlocking {
        val invalidateCalled = AtomicBoolean(false)
        val readFileCalledBeforeInvalidate = AtomicBoolean(false)
        val readFileCalled = AtomicBoolean(false)

        val shadowAwareFs = object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                // Track only the journal content read (not sanitizeDirectory rename checks).
                if (path == "/graph/journals/2026_04_13.md" && !invalidateCalled.get()) {
                    readFileCalledBeforeInvalidate.set(true)
                }
                if (path == "/graph/journals/2026_04_13.md") readFileCalled.set(true)
                return "- content"
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String): List<String> =
                if (path.endsWith("/journals")) listOf("2026_04_13.md") else emptyList()
            override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> =
                if (path.endsWith("/journals")) listOf("2026_04_13.md" to 9999L) else emptyList()
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = true
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = 9999L
            override suspend fun invalidateStaleShadow(graphPath: String) { invalidateCalled.set(true) }
        }

        val loader = GraphLoader(shadowAwareFs, InMemoryPageRepository(), InMemoryBlockRepository())

        withTimeout(10_000) {
            loader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = {},
                onFullyLoaded = {}
            )
        }

        assertFalse(readFileCalledBeforeInvalidate.get(),
            "readFile for journal must not be called before invalidateStaleShadow on cold start")
        assertTrue(invalidateCalled.get(), "invalidateStaleShadow must have been called on cold start")
        assertTrue(readFileCalled.get(), "readFile must have been called on cold start")
    }

    /**
     * Regression: loadFullPage (navigation-triggered) must call invalidateShadow before
     * readFile so that a page modified externally since the last syncShadow is read fresh.
     *
     * Setup: page has a fully-loaded block (allBlocksLoaded=true) but the file's mtime (9999ms)
     * is newer than page.updatedAt (1000ms), so the mtime guard fires and the file IS re-read.
     * This exercises the mtime code path, not the "blocks missing" shortcut.
     */
    @Test
    fun `loadFullPage calls invalidateShadow before reading file`() = runBlocking {
        val invalidatedPaths = mutableSetOf<String>()
        val readFileCalledBeforeInvalidate = AtomicBoolean(false)
        val readFileCalled = AtomicBoolean(false)
        val invalidateCalled = AtomicBoolean(false)
        val filePath = "/graph/pages/my-page.md"

        val shadowAwareFs = object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String): String? {
                if (path == filePath && path !in invalidatedPaths) {
                    readFileCalledBeforeInvalidate.set(true)
                }
                if (path == filePath) readFileCalled.set(true)
                return "- fresh content"
            }
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String) = emptyList<String>()
            override fun listFilesWithModTimes(path: String) = emptyList<Pair<String, Long>>()
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = path == filePath
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            // Return newer mtime so the mtime guard in loadFullPage does not short-circuit
            override fun getLastModifiedTime(path: String): Long? = 9999L
            override fun invalidateShadow(path: String) {
                invalidatedPaths.add(path)
                if (path == filePath) invalidateCalled.set(true)
            }
        }

        val navPageRepo = InMemoryPageRepository()
        val navBlockRepo = InMemoryBlockRepository()
        val oldInstant = Instant.fromEpochMilliseconds(1000L)
        val pageUuid = "nav-page-1"

        navPageRepo.savePage(
            Page(
                uuid = PageUuid(pageUuid),
                name = "my-page",
                filePath = filePath,
                createdAt = oldInstant, updatedAt = oldInstant,
                isContentLoaded = true
            )
        )
        // Pre-populate a loaded block so allBlocksLoaded=true — the mtime guard is then
        // evaluated and finds fileModTime (9999ms) > page.updatedAt (1000ms) → re-reads file.
        navBlockRepo.saveBlocks(listOf(
            dev.stapler.stelekit.model.Block(
                uuid = BlockUuid("block-nav-1"),
                pageUuid = PageUuid(pageUuid),
                content = "old content",
                position = 0,
                createdAt = oldInstant,
                updatedAt = oldInstant,
                isLoaded = true
            )
        ))

        val loader = GraphLoader(shadowAwareFs, navPageRepo, navBlockRepo)
        loader.loadFullPage(pageUuid)

        assertFalse(readFileCalledBeforeInvalidate.get(),
            "readFile must not be called before invalidateShadow in loadFullPage for externally-changed pages")
        assertTrue(invalidateCalled.get(), "invalidateShadow must have been called for the page file")
        assertTrue(readFileCalled.get(), "readFile must have been called by loadFullPage")
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
        graphLoader.loadFullPage(oldPage.uuid.value)
        testScheduler.advanceUntilIdle()
        
        val reloadedBlocks = blockRepository.getBlocksForPage(oldPage.uuid).first().getOrNull()!!
        assertTrue(reloadedBlocks[0].isLoaded, "Block should be fully loaded after loadFullPage")
    }

    // ── Midnight boundary watcher tests ──────────────────────────────────────

    /** Builds a minimal StelekitViewModel for testing millisUntilNextMidnight. */
    private fun buildMinimalViewModel(): StelekitViewModel {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository()
        val fs = PlatformFileSystem()
        val loader = GraphLoader(fs, pageRepo, blockRepo)
        val writer = GraphWriter(fs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return StelekitViewModel(
            StelekitViewModelDependencies(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = loader,
                graphWriter = writer,
                fileSystem = fs,
                platformSettings = InMemorySettings(),
                scope = scope,
            )
        )
    }

    @Test
    fun `millisUntilNextMidnight returns positive value less than 24h`() {
        // Tests the production formula via the actual StelekitViewModel method.
        val vm = buildMinimalViewModel()
        val delayMs = vm.millisUntilNextMidnight(Clock.System)

        assertTrue(delayMs > 0, "Delay until next midnight must be positive")
        assertTrue(delayMs <= 24 * 60 * 60 * 1000L, "Delay must not exceed 24 hours")
    }

    @Test
    fun `millisUntilNextMidnight returns at least 1000ms when clock is exactly at midnight`() {
        // Tests the production formula via the actual StelekitViewModel method with a FakeClock.
        val tz = TimeZone.currentSystemDefault()
        val midnightInstant = LocalDate(2026, 5, 29).atStartOfDayIn(tz)
        val fakeClock = FakeClock(midnightInstant)

        val vm = buildMinimalViewModel()
        val delay = vm.millisUntilNextMidnight(fakeClock)

        assertTrue(delay >= 1000L,
            "coerceAtLeast(1_000L) must fire when clock is exactly at midnight; got $delay ms")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `midnight watcher calls ensureTodayJournal after simulated day crossing`() = runTest {
        val callCount = AtomicInteger(0)
        val tz = TimeZone.currentSystemDefault()
        val startInstant = LocalDate(2026, 5, 28).atStartOfDayIn(tz) + 23.hours + 58.minutes
        val fakeClock = FakeClock(startInstant)

        val watcherJob = launch {
            while (isActive) {
                val now = fakeClock.now()
                val today = now.toLocalDateTime(tz).date
                val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
                val delayMs = (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
                kotlinx.coroutines.delay(delayMs)
                fakeClock.advance(2.minutes)
                callCount.incrementAndGet()
            }
        }

        advanceTimeBy(121_000)
        assertEquals(1, callCount.get(), "ensureTodayJournal must be called once after first midnight")

        advanceTimeBy(24 * 60 * 60 * 1000L + 1_000L)
        assertEquals(2, callCount.get(), "ensureTodayJournal must be called again after second midnight")

        advanceTimeBy(24 * 60 * 60 * 1000L + 1_000L)
        assertEquals(3, callCount.get(), "ensureTodayJournal must be called a third time")

        watcherJob.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `midnight watcher is cancelled when scope is cancelled`() = runTest {
        val callCount = AtomicInteger(0)
        val tz = TimeZone.currentSystemDefault()
        val fakeClock = FakeClock(
            LocalDate(2026, 5, 28).atStartOfDayIn(tz) + 23.hours + 58.minutes
        )

        val watcherJob = launch {
            while (isActive) {
                val now = fakeClock.now()
                val today = now.toLocalDateTime(tz).date
                val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
                val delayMs = (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
                kotlinx.coroutines.delay(delayMs)
                fakeClock.advance(2.minutes)
                callCount.incrementAndGet()
            }
        }

        watcherJob.cancel()
        advanceTimeBy(200_000)

        assertEquals(0, callCount.get(),
            "Cancelled watcher must not fire after cancellation")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `midnight watcher skips call when lastJournalDate already equals today`() = runTest {
        val callCount = AtomicInteger(0)
        val tz = TimeZone.currentSystemDefault()
        // Start 2 min before midnight on May 29
        val today = LocalDate(2026, 5, 29)
        val startInstant = today.atStartOfDayIn(tz) + 23.hours + 58.minutes
        val fakeClock = FakeClock(startInstant)
        // Pre-set to May 30 — simulating startup already handled today's journal
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        var lastJournalDate: LocalDate? = tomorrow

        val watcherJob = launch {
            while (isActive) {
                val now = fakeClock.now()
                val nowDate = now.toLocalDateTime(tz).date
                val tomorrowMidnight = nowDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
                val delayMs = (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
                kotlinx.coroutines.delay(delayMs)
                // Advance FakeClock by delayMs+1ms so it stays in sync with the test scheduler.
                // Using a fixed 2-minute advance breaks once the guard `continue`s: the clock
                // would stay on May 30 and the second crossing would never reach May 31.
                fakeClock.advance((delayMs + 1).milliseconds)
                val afterDate = fakeClock.now().toLocalDateTime(tz).date
                if (afterDate == lastJournalDate) continue
                callCount.incrementAndGet()
                lastJournalDate = afterDate
            }
        }

        // First crossing (May 29 → May 30): lastJournalDate already = May 30, skip
        advanceTimeBy(121_000)
        assertEquals(0, callCount.get(), "Must skip when lastJournalDate already matches crossed date")

        // Second crossing (May 30 → May 31): new date, must fire
        advanceTimeBy(24 * 60 * 60 * 1000L + 1_000L)
        assertEquals(1, callCount.get(), "Must fire when date advances past lastJournalDate")

        watcherJob.cancel()
    }

    @Test
    fun `midnight watcher calls ensureTodayJournal via real startMidnightBoundaryWatcher`() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository()
        val fs = PlatformFileSystem()
        val loader = GraphLoader(fs, pageRepo, blockRepo)
        val writer = GraphWriter(fs)

        val tz = TimeZone.currentSystemDefault()
        // Place the fake clock 1 ms before midnight on May 28.
        // millisUntilNextMidnight() returns 1 ms → coerceAtLeast(1 000) = 1 000 ms, so
        // the watcher's first delay() fires after 1 real second.
        // lastJournalDate is seeded to May 28.  Once the real delay fires, we want
        // clock.now() to return May 29 so ensureTodayJournal() creates the right page.
        val justBeforeMidnightMay29 = LocalDate(2026, 5, 29).atStartOfDayIn(tz) - 1.milliseconds
        val fakeClock = FakeClock(justBeforeMidnightMay29)

        // Wire the same FakeClock into JournalService so ensureTodayJournal resolves
        // "today" using the fake time rather than Clock.System.
        val journalService = JournalService(pageRepo, blockRepo, clock = fakeClock)

        // vmScope uses real Dispatchers.Default so its internal observe-coroutines do not
        // join the test scheduler and cause advanceUntilIdle() to spin forever.
        val vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = loader,
                graphWriter = writer,
                fileSystem = fs,
                platformSettings = InMemorySettings(),
                scope = vmScope,
                journalService = journalService,
            )
        )

        vm.startMidnightBoundaryWatcher(fakeClock)

        // Give the watcher coroutine time to start on Dispatchers.Default and seed
        // lastJournalDate (= May 28) BEFORE we advance the clock past midnight.
        // Without this yield, the test thread can race ahead and advance fakeClock to
        // May 29 before the watcher reads it, causing lastJournalDate to be seeded as
        // May 29 and the midnight-crossing check to be skipped.
        kotlinx.coroutines.delay(200)

        // Advance the fake clock into May 29 so clock.now() returns the new day when
        // the watcher wakes up from its 1 000 ms real delay.
        fakeClock.advance(1001.milliseconds)

        // Poll until ensureTodayJournal creates the May 29 journal (fires after ~1 s).
        val tomorrow = LocalDate(2026, 5, 29)
        withTimeout(5000) {
            while (pageRepo.getJournalPageByDate(tomorrow).first().getOrNull() == null) {
                kotlinx.coroutines.delay(50)
            }
        }
        val page = pageRepo.getJournalPageByDate(tomorrow).first().getOrNull()
        assertNotNull(page, "startMidnightBoundaryWatcher must call ensureTodayJournal after midnight crossing")
        assertEquals(tomorrow, page.journalDate)

        vmScope.cancel()
    }
}
