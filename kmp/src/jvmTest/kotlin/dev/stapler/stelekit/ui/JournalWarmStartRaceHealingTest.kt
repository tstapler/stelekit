package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Reproduces the "journal added externally doesn't load" bug: on a warm start (DB already
 * has journals from a prior session — the common case on repeat app opens), GraphLoader
 * fires onPhase1Complete() before it has scanned the journals directory (warm-start branch
 * in GraphLoader.loadGraphProgressive calls onPhase1Complete() immediately, then launches
 * the background reconcile that runs loadJournalsImmediate()). onPhase1Complete eagerly
 * launches journalService.ensureTodayJournal(), which — finding no DB row yet for today —
 * creates a content-less, filePath=null page for today. If the externally-synced file for
 * today is discovered and parsed only afterwards (as a separate row — same journalDate,
 * different name-casing — which is exactly the scenario ensureTodayJournal's own merge
 * logic documents), nothing re-invoked ensureTodayJournal() after the disk scan, so that
 * merge never ran this session and the content-less phantom — logged by
 * GraphLoader.loadFullPage as "Page has no file path and could not be found on disk" —
 * stuck around indefinitely.
 *
 * Fix: onFullyLoaded (which fires only after the journal directory has been scanned, on
 * every load path) also calls ensureTodayJournal(), healing any such duplicate within the
 * same session instead of waiting for next launch / the midnight boundary check.
 */
class JournalWarmStartRaceHealingTest {

    @Test
    fun onFullyLoaded_heals_duplicate_today_journal_that_appears_after_phase1() = runBlocking {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val hyphenName = today.toString()
        val underscoreName = hyphenName.replace('-', '_')
        val now = Clock.System.now()

        // The phantom row ensureTodayJournal() creates on Phase-1 completion: no content,
        // no file path. Pre-seeded here to stand in for "onPhase1Complete's eager call
        // already ran and created it" — the DB state right after Phase 1 in the real race.
        val phantom = Page(
            uuid = PageUuid("phantom-uuid"),
            name = underscoreName,
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = today,
            filePath = null,
        )
        // An older journal, present purely so getJournalPages() is non-empty at startup and
        // GraphLoader takes the warm-start branch — the only branch where the race exists.
        val olderJournal = Page(
            uuid = PageUuid("older-uuid"),
            name = "2020-01-01",
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = kotlinx.datetime.LocalDate(2020, 1, 1),
        )
        // The disk-backed row for today: what an externally-synced file resolves to once
        // parsed — real content, resolved file path. Injected mid-load below, simulating
        // the warm-reconcile's disk scan discovering it *after* Phase 1 already ran.
        val diskPage = Page(
            uuid = PageUuid("disk-uuid"),
            name = hyphenName,
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = today,
            filePath = "/tmp/graph/journals/$hyphenName.md",
        )

        val pageRepo = FakePageRepository(initialPages = listOf(phantom, olderJournal))
        val blockRepo = FakeBlockRepository()
        // Slows the warm-reconcile's directory scans (Dispatchers.Default worker thread —
        // does not block the test's runBlocking thread) so the test has a deterministic
        // window, after Phase 1 completes, to inject the "externally-synced file arrives
        // late" row before the reconcile job finishes and fires onFullyLoaded. Mirrors the
        // real-world window created by slow Android SAF directory listing.
        val fileSystem = object : FakeFileSystem() {
            override fun listFiles(path: String): List<String> {
                Thread.sleep(200)
                return emptyList()
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // Pre-mark this graph as already cached so StelekitViewModel.loadGraph() does NOT
        // clear the repositories before calling GraphLoader — matching a real warm start.
        val settings = InMemorySettings().apply { putString("cached_graph_path", "/tmp/graph") }
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(PlatformFileSystem()),
                platformSettings = settings,
                scope = scope,
            )
        )

        vm.setGraphPath("/tmp/graph")

        // Wait for Phase 1 (onPhase1Complete) to finish — only the phantom exists for today
        // at this point, exactly like the real race.
        withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
        // isLoading flips synchronously inside onPhase1Complete, before its fire-and-forget
        // `scope.launch { ensureTodayJournal() }` necessarily runs. Give that trivial
        // (no-disk-I/O) launch time to actually complete before injecting the duplicate, so
        // it is provably onFullyLoaded's call — not a lucky scheduling of the original one —
        // that heals it. The reconcile job is held back by fileSystem's artificial listFiles
        // delay for far longer than this.
        delay(300)

        // Now the externally-synced file "arrives" — simulating GraphLoader's background
        // disk scan discovering and parsing it as a separate row after Phase 1 already ran.
        pageRepo.savePage(diskPage)
        blockRepo.saveBlock(
            Block(
                uuid = BlockUuid("disk-block"),
                pageUuid = diskPage.uuid,
                content = "Synced from another device",
                position = "a0",
                createdAt = now,
                updatedAt = now,
            )
        )

        withTimeout(10_000) { vm.uiState.first { it.isFullyLoaded } }

        val healedJournals = withTimeout(10_000) {
            pageRepo.getJournalPages(10, 0)
                .first { result -> result.getOrNull()?.count { it.journalDate == today } == 1 }
                .getOrNull()
        }

        assertNotNull(healedJournals, "duplicate today-journal rows were never merged down to one")
        val survivor = healedJournals.single { it.journalDate == today }
        assertEquals(diskPage.uuid, survivor.uuid, "the content-less phantom should be deleted, not the disk-backed page")
        assertNotNull(survivor.filePath, "surviving page must keep its resolved file path")
        Unit
    }
}
