@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.assets

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.testAsset
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetBrowserViewModelTest {

    private class CapturingClipboard : ClipboardProvider {
        val written = mutableListOf<String>()
        override fun writeText(text: String) { written += text }
        override fun writeHtml(html: String, plainFallback: String) { written += plainFallback }
    }

    // The VM loads on Dispatchers.Default, so state is observed with a real-time timeout.
    private fun AssetBrowserViewModel.await(predicate: (AssetBrowserUiState) -> Boolean): AssetBrowserUiState =
        runBlocking { withTimeout(5_000) { uiState.first(predicate) } }

    private fun repoWith(vararg assets: AssetEntry) =
        InMemoryAssetRepository().also { repo -> runBlocking { assets.forEach { repo.saveAsset(it) } } }

    @Test
    fun initialLoad_populatesAssetsFromRepository() {
        val vm = AssetBrowserViewModel(repoWith(testAsset("a", importedAtMs = 1), testAsset("b", importedAtMs = 2)))
        val state = vm.await { !it.isLoading && it.assets.size == 2 }
        assertEquals(listOf("b", "a"), state.assets.map { it.uuid.value })
        assertTrue(!state.hasMore)
    }

    @Test
    fun setFilter_images_resetsCursorsAndReloads() {
        val vm = AssetBrowserViewModel(
            repoWith(
                testAsset("img", importedAtMs = 1),
                testAsset("pdf", "/graph/assets/pdfs/x.pdf", AssetMediaType.PDF, "pdfs", importedAtMs = 2),
            )
        )
        vm.await { it.assets.size == 2 }

        vm.setFilter(AssetFilter.IMAGES)

        val state = vm.await { it.selectedFilter == AssetFilter.IMAGES && !it.isLoading && it.assets.isNotEmpty() }
        assertEquals(listOf("img"), state.assets.map { it.uuid.value })
        assertEquals(1L, state.cursorMs)
    }

    @Test
    fun setSort_byName_reloadsAndUsesNameCursor() {
        val vm = AssetBrowserViewModel(repoWith(testAsset("b", "/graph/assets/images/b.png"), testAsset("a", "/graph/assets/images/a.png")))
        vm.await { it.assets.size == 2 }

        vm.setSort(AssetSortOrder.BY_NAME)

        val state = vm.await { it.sortOrder == AssetSortOrder.BY_NAME && it.assets.size == 2 }
        assertEquals(listOf("a", "b"), state.assets.map { it.uuid.value })
        assertEquals("/graph/assets/images/b.png", state.cursorName)
        assertNull(state.cursorMs)
    }

    @Test
    fun setSearch_afterDebounce_onlyMatchingAssetsAppear() {
        val vm = AssetBrowserViewModel(
            repoWith(
                testAsset("s", "/graph/assets/images/sunrise.png"),
                testAsset("m", "/graph/assets/images/moon.png"),
            )
        )
        vm.await { it.assets.size == 2 }

        vm.setSearch("sunrise")

        // list is cleared immediately; results return only after the 300ms debounce fires
        assertEquals("sunrise", vm.uiState.value.searchQuery)
        val state = vm.await { it.assets.size == 1 }
        assertEquals(listOf("s"), state.assets.map { it.uuid.value })
    }

    @Test
    fun loadMore_appendsRemainingPageAndAdvancesCursor() {
        val assets = (1..55).map { testAsset("u%02d".format(it), importedAtMs = it.toLong()) }
        val vm = AssetBrowserViewModel(repoWith(*assets.toTypedArray()))
        val first = vm.await { it.assets.size == 50 }
        assertTrue(first.hasMore)
        assertEquals(6L, first.cursorMs)

        vm.loadMore()

        val state = vm.await { it.assets.size == 55 && !it.isLoadingMore }
        assertTrue(!state.hasMore)
        assertEquals(1L, state.cursorMs)
        assertEquals(55, state.assets.map { it.uuid.value }.toSet().size)
    }

    @Test
    fun deleteAsset_removesFromStateAndRepository() {
        val repo = repoWith(testAsset("a", importedAtMs = 1), testAsset("b", importedAtMs = 2))
        val vm = AssetBrowserViewModel(repo)
        val target = vm.await { it.assets.size == 2 }.assets.first { it.uuid.value == "a" }

        vm.deleteAsset(target)

        val state = vm.await { it.assets.none { a -> a.uuid.value == "a" } }
        assertEquals(listOf("b"), state.assets.map { it.uuid.value })
        assertEquals(1L, runBlocking { repo.countAssets().getOrNull() })
    }

    @Test
    fun copyMarkdownLink_imageUsesImageSyntax() {
        val vm = AssetBrowserViewModel(repoWith())
        val clipboard = CapturingClipboard()
        vm.copyMarkdownLink(testAsset("i", "/graph/assets/images/pic.png"), clipboard)
        assertEquals(listOf("![pic.png](../assets/images/pic.png)"), clipboard.written)
    }

    @Test
    fun copyMarkdownLink_nonImageUsesLinkSyntax() {
        val vm = AssetBrowserViewModel(repoWith())
        val clipboard = CapturingClipboard()
        vm.copyMarkdownLink(testAsset("p", "/graph/assets/pdfs/doc.pdf", AssetMediaType.PDF, "pdfs"), clipboard)
        assertEquals(listOf("[doc.pdf](../assets/pdfs/doc.pdf)"), clipboard.written)
    }

    @Test
    fun actionMenu_showThenDismiss() {
        val vm = AssetBrowserViewModel(repoWith())
        val asset = testAsset("m")
        vm.showActionMenu(asset)
        assertEquals(asset, vm.uiState.value.actionMenuAsset)
        vm.dismissActionMenu()
        assertNull(vm.uiState.value.actionMenuAsset)
    }
}
