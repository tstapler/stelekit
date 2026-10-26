// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A launch that starts on the repo root of a graph whose notes live in `<root>/logseq` must not wipe
 * the cached pages for a folder that holds none; a real switch to a folder with content still does.
 */
class StelekitViewModelStartupPathTest {
    private val root = "/graphs/uuid"
    private val notes = "$root/logseq"
    private val cacheKey = "cached_graph_path"

    private class DirectoriesFileSystem(private val dirs: Set<String>) : FakeFileSystem() {
        override fun directoryExists(path: String): Boolean = path in dirs
        override fun createDirectory(path: String): Boolean = false
    }

    private val now = Clock.System.now()
    private fun page() = Page(uuid = PageUuid("p1"), name = "Existing", createdAt = now, updatedAt = now)

    private class Harness(val vm: StelekitViewModel, val pages: FakePageRepository, val settings: InMemorySettings, val scope: CoroutineScope)

    private fun harness(
        dirs: Set<String>,
        cachedPath: String,
        pages: FakePageRepository = FakePageRepository(listOf(page())),
    ): Harness {
        val fileSystem = DirectoriesFileSystem(dirs)
        val settings = InMemorySettings().also { it.putString(cacheKey, cachedPath) }
        val blocks = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pages,
                blockRepository = blocks,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pages, blocks),
                graphWriter = GraphWriter(PlatformFileSystem()),
                platformSettings = settings,
                scope = scope,
            )
        )
        return Harness(vm, pages, settings, scope)
    }

    private fun pageCount(h: Harness) = runBlocking { h.pages.countPages().first().getOrNull() }

    private fun stalePageExists(h: Harness) =
        runBlocking { h.pages.getPageByUuid(PageUuid("p1")).first().getOrNull() != null }

    @Test
    fun loadGraph_should_KeepCache_When_FolderHasNoPagesOrJournals() = runBlocking {
        val h = harness(dirs = setOf(root, notes, "$notes/pages"), cachedPath = notes)
        try {
            h.vm.loadGraph(root)
            withTimeout(10_000) { h.vm.uiState.first { !it.isLoading } }

            assertEquals(1L, pageCount(h), "cached pages must survive a load of the bare repo root")
            assertEquals(notes, h.settings.getString(cacheKey, ""), "cache marker must still name the real notes folder")
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun loadGraph_should_ClearCache_When_SwitchingToFolderWithContent() = runBlocking {
        val h = harness(dirs = setOf(root, notes, "$notes/pages"), cachedPath = root)
        try {
            h.vm.loadGraph(notes)
            // Not "zero pages": loading creates today's journal right after the wipe.
            withTimeout(10_000) {
                while (h.settings.getString(cacheKey, "") != notes || stalePageExists(h)) delay(10)
            }

            assertFalse(stalePageExists(h), "a real graph switch still wipes the stale cache")
            assertEquals(notes, h.settings.getString(cacheKey, ""))
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun loadGraph_should_ReportProgress_When_TheCacheWipeIsSlow() = runBlocking {
        val slowPages = object : FakePageRepository(listOf(page())) {
            override suspend fun clear() {
                delay(600)
                super.clear()
            }
        }
        val h = harness(dirs = setOf(root, notes, "$notes/pages"), cachedPath = root, pages = slowPages)
        try {
            h.vm.wipeWatchdogIntervalMs = 100
            h.vm.loadGraph(notes)

            val status = withTimeout(10_000) { h.vm.uiState.first { it.statusMessage.startsWith("Still clearing") } }
            assertTrue(status.statusMessage.contains("s)"), status.statusMessage)
        } finally {
            h.scope.cancel()
        }
    }
}
