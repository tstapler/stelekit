package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for the recent-pages logic in StelekitViewModel:
 * - Per-graph isolation (different graph paths store separate recents)
 * - Display capped at 10 even when more are visited
 * - Deduplication: revisiting a page moves it to the top
 * - Persistence: stale UUIDs (not in repo) are filtered out
 *
 * We pre-seed `platformSettings` with "lastGraphPath" so the VM is initialized with the
 * correct graph path WITHOUT triggering loadGraph (which would clear the fake repo).
 * onboardingCompleted defaults to false, so loadGraph is skipped in init.
 */
class RecentPagesTest {

    private val now = Clock.System.now()

    private fun makePage(uuid: String, name: String): Page = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now
    )

    /**
     * Build a ViewModel with [graphPath] pre-set via settings so the key is correct,
     * without triggering loadGraph (onboardingCompleted stays false).
     * Pages in [pageRepo] remain intact after construction.
     */
    private fun makeViewModel(
        pageRepo: FakePageRepository,
        settings: PlatformSettings,
        graphPath: String
    ): StelekitViewModel {
        settings.putString("lastGraphPath", graphPath)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val blockRepo = FakeBlockRepository()
        val searchRepo = InMemorySearchRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        @Suppress("DEPRECATION")
        val graphWriter = GraphWriter(PlatformFileSystem(), pageRepository = pageRepo)
        var viewModelRef: StelekitViewModel? = null
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = graphWriter,
            pageRepository = pageRepo,
            graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" }
        )
        return StelekitViewModel(
            PlatformFileSystem(),
            pageRepo,
            blockRepo,
            searchRepo,
            graphLoader,
            graphWriter,
            settings,
            scope,
            blockStateManager = bsm
        ).also { viewModelRef = it }
    }

    // -------------------------------------------------------------------------
    // Test 1: per-graph isolation — settings keys are namespaced per graph
    // -------------------------------------------------------------------------
    @Test
    fun recentPages_are_isolated_per_graph() = runBlocking {
        val pageA = makePage("uuid-a", "Page A")
        val pageB = makePage("uuid-b", "Page B")
        val key1 = "recent_pages_/tmp/rp-graph1"
        val key2 = "recent_pages_/tmp/rp-graph2"

        val settings = PlatformSettings()
        settings.putString(key1, "")
        settings.putString(key2, "")

        // VM1 for graph1 — visit page A
        val pageRepo1 = FakePageRepository(listOf(pageA))
        val vm1 = makeViewModel(pageRepo1, settings, "/tmp/rp-graph1")
        vm1.navigateTo(Screen.PageView(pageA))

        // VM2 for graph2 — visit page B (separate ViewModel, different graph path)
        val settings2 = PlatformSettings()
        settings2.putString(key2, "")
        val pageRepo2 = FakePageRepository(listOf(pageB))
        val vm2 = makeViewModel(pageRepo2, settings2, "/tmp/rp-graph2")
        vm2.navigateTo(Screen.PageView(pageB))

        // Graph1 settings key should contain A but not B
        val graph1Recents = settings.getString(key1, "").split(",").filter { it.isNotEmpty() }
        assertTrue("uuid-a" in graph1Recents, "graph1 key should contain pageA uuid")
        assertTrue("uuid-b" !in graph1Recents, "graph1 key must NOT contain pageB uuid")

        // Graph2 settings key should contain B but not A
        val graph2Recents = settings2.getString(key2, "").split(",").filter { it.isNotEmpty() }
        assertTrue("uuid-b" in graph2Recents, "graph2 key should contain pageB uuid")
        assertTrue("uuid-a" !in graph2Recents, "graph2 key must NOT contain pageA uuid")
    }

    // -------------------------------------------------------------------------
    // Test 2: display capped at 10
    // -------------------------------------------------------------------------
    @Test
    fun recentPages_display_is_capped_at_10() = runBlocking {
        val pages = (1..15).map { i -> makePage("uuid-$i", "Page $i") }
        val settings = PlatformSettings()
        settings.putString("recent_pages_/tmp/rp-cap", "")

        val pageRepo = FakePageRepository(pages)
        val vm = makeViewModel(pageRepo, settings, "/tmp/rp-cap")

        pages.forEach { page -> vm.navigateTo(Screen.PageView(page)) }

        assertEquals(10, vm.uiState.value.recentPages.size,
            "recentPages must be capped at 10 for display")
    }

    // -------------------------------------------------------------------------
    // Test 3: deduplication and ordering
    // -------------------------------------------------------------------------
    @Test
    fun recentPages_deduplication_moves_page_to_top() = runBlocking {
        val pageA = makePage("uuid-a", "Page A")
        val pageB = makePage("uuid-b", "Page B")
        val pageC = makePage("uuid-c", "Page C")
        val settings = PlatformSettings()
        settings.putString("recent_pages_/tmp/rp-dedup", "")

        val pageRepo = FakePageRepository(listOf(pageA, pageB, pageC))
        val vm = makeViewModel(pageRepo, settings, "/tmp/rp-dedup")

        vm.navigateTo(Screen.PageView(pageA))
        vm.navigateTo(Screen.PageView(pageB))
        vm.navigateTo(Screen.PageView(pageC))
        vm.navigateTo(Screen.PageView(pageA)) // revisit A

        val recentNames = vm.uiState.value.recentPages.map { it.name }
        assertEquals(listOf("Page A", "Page C", "Page B"), recentNames,
            "A should be at top after revisit, order should be [A, C, B]")
    }

    // -------------------------------------------------------------------------
    // Test 4: persistence — stale UUIDs filtered out from display
    // -------------------------------------------------------------------------
    @Test
    fun recentPages_filters_out_stale_uuids() = runBlocking {
        val validPage = makePage("uuid-valid", "Valid Page")
        val staleUuid = "uuid-stale"
        val key = "recent_pages_/tmp/rp-stale"

        val settings = PlatformSettings()
        // Pre-populate settings with a stale UUID followed by a valid UUID
        settings.putString(key, "$staleUuid,${validPage.uuid}")

        // Only the valid page exists in the repository
        val pageRepo = FakePageRepository(listOf(validPage))
        val vm = makeViewModel(pageRepo, settings, "/tmp/rp-stale")

        val recentNames = vm.uiState.value.recentPages.map { it.name }
        assertEquals(listOf("Valid Page"), recentNames,
            "Only pages that exist in the repo should appear in recentPages")
    }
}
