package dev.stapler.stelekit.ui.library

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryViewModelStateTest {
    private val fx = LibraryFixture()
    private val toClose = mutableListOf<LibraryViewModel>()

    @AfterTest
    fun tearDown() {
        toClose.forEach { it.close() }
        fx.close()
    }

    private class MapSettings : Settings {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private fun vm(repo: LibraryRepository = fx.library, settings: Settings? = null, filters: LibraryFilterState = LibraryFilterState(), pageSize: Int = 100) =
        LibraryViewModel(repo, settings, filters, pageSize = pageSize, searchDebounceMs = 20).also { toClose += it }

    private suspend fun await(v: LibraryViewModel, cond: (LibraryState) -> Boolean) {
        withTimeout(10_000) { v.state.first(cond) }
    }

    private suspend fun seed() {
        fx.add(1, "kitchen.png", AssetMediaType.IMAGE, 1_000L, annotation = AnnotationSpec(tags = listOf("site"), labels = listOf("w")))
        fx.add(2, "sink.png", AssetMediaType.IMAGE, 1_001L)
        fx.add(3, "plan.pdf", AssetMediaType.PDF, 1_002L)
    }

    @Test
    fun finishedIndexWalk_reloadsTheList_andRunningWalkFlagsIndexing() = runBlocking {
        val progress = kotlinx.coroutines.flow.MutableStateFlow<AssetIndexProgress>(AssetIndexProgress.Indexing(0, 3))
        val v = LibraryViewModel(fx.library, null, pageSize = 100, searchDebounceMs = 20, indexProgress = progress).also { toClose += it }
        await(v) { !it.isLoading && it.indexing }
        assertTrue(v.state.value.items.isEmpty())

        seed()
        progress.value = AssetIndexProgress.Finished(3)

        await(v) { !it.indexing && it.items.size == 3 }
        assertEquals(3, v.state.value.items.size)
    }

    @Test
    fun initialLoad_showsAllItems_countAndTags() = runBlocking {
        seed()
        val v = vm()
        await(v) { !it.isLoading && it.totalCount == 3L && it.availableTags.isNotEmpty() }
        assertEquals(3, v.state.value.items.size)
        assertEquals(listOf("site"), v.state.value.availableTags)
    }

    @Test
    fun togglingAnnotated_reloadsOnlyAnnotated_andClearRestoresEverything() = runBlocking {
        seed()
        val v = vm()
        await(v) { !it.isLoading && it.totalCount == 3L }
        v.toggleChip(LibraryChip.Annotated)
        await(v) { !it.isLoading && it.totalCount == 1L }
        assertEquals(listOf("asset-000001"), v.state.value.items.map { it.id })
        v.clearFilters()
        await(v) { !it.isLoading && it.totalCount == 3L }
        assertTrue(v.state.value.filters.isDefault)
    }

    @Test
    fun aDimmedChip_isIgnored_withoutReloading() = runBlocking {
        seed()
        val v = vm(filters = LibraryFilterState(annotated = true))
        await(v) { !it.isLoading && it.totalCount == 1L }
        v.toggleChip(LibraryChip.Type(LibraryMediaClass.PDFS))
        assertEquals(setOf<LibraryMediaClass>(), v.state.value.filters.types)
    }

    @Test
    fun sort_isPersistedAsThePreference_andRestored() = runBlocking {
        seed()
        val settings = MapSettings()
        val v = vm(settings = settings)
        v.setSort(LibrarySort.NAME)
        await(v) { !it.isLoading && it.items.map { i -> i.fileName } == listOf("kitchen.png", "plan.pdf", "sink.png") }
        assertEquals("NAME", settings.map[LibraryViewModel.SORT_KEY])
        assertEquals(LibrarySort.NAME, vm(settings = settings).state.value.sort)
    }

    @Test
    fun search_isDebounced_oneCharacterIsAHintNotASearch() = runBlocking {
        seed()
        val v = vm()
        await(v) { !it.isLoading && it.totalCount == 3L }
        v.setSearch("k")
        assertTrue(v.state.value.searchHint)
        await(v) { !it.isLoading && it.filters.search == "k" }
        assertEquals(3, v.state.value.items.size, "a 1-character query must not filter")
        v.setSearch("kit")
        await(v) { !it.isLoading && it.totalCount == 1L }
        assertFalse(v.state.value.searchHint)
    }

    @Test
    fun entryPoint_annotatedClearsOtherFilters_plainOpenKeepsSessionState() = runBlocking {
        seed()
        val v = vm(filters = LibraryFilterState(search = "sink"))
        await(v) { !it.isLoading && it.totalCount == 1L }
        v.applyEntryPoint(null, null)
        assertEquals("sink", v.state.value.filters.search)
        v.applyEntryPoint(LibraryFilter.Annotated, "ignored")
        assertEquals(LibraryFilterState(annotated = true), v.state.value.filters)
    }

    @Test
    fun aFailingFirstPage_surfacesTheError_andRetrySucceeds() = runBlocking {
        seed()
        var fail = true
        val flaky = object : LibraryRepository by fx.library {
            override suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>> =
                if (fail) DomainError.DatabaseError.ReadFailed("boom").left() else fx.library.getLibraryPage(query, cursor, limit)
        }
        val v = vm(flaky)
        await(v) { !it.isLoading && it.error != null }
        assertEquals(true, v.state.value.items.isEmpty())
        fail = false
        v.reload()
        await(v) { !it.isLoading && it.error == null && it.items.size == 3 }
    }

    @Test
    fun aFailingPageTwo_keepsPageOne_andOffersRetry() = runBlocking {
        repeat(5) { fx.add(it, "f$it.png", AssetMediaType.IMAGE, 1_000L + it) }
        var failPageTwo = true
        val flaky = object : LibraryRepository by fx.library {
            override suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>> =
                if (cursor != null && failPageTwo) DomainError.DatabaseError.ReadFailed("page2").left() else fx.library.getLibraryPage(query, cursor, limit)
        }
        val v = vm(flaky, pageSize = 3)
        await(v) { !it.isLoading && it.items.size == 3 && it.hasMore }
        v.loadMore()
        await(v) { !it.isLoadingMore && it.loadMoreError != null }
        assertEquals(3, v.state.value.items.size)
        assertNotNull(v.state.value.loadMoreError)
        failPageTwo = false
        v.loadMore()
        await(v) { !it.isLoadingMore && it.items.size == 5 }
        assertFalse(v.state.value.hasMore)
    }

    @Test
    fun annotateItem_navigatesOnSuccess_andSetsActionErrorOnFailure() = runBlocking {
        seed()
        val v = vm()
        await(v) { !it.isLoading && it.totalCount == 3L }
        val item = v.state.value.items.first()
        var opened: String? = null

        v.annotateItem(item, { Either.Right("ann-1") }) { opened = it }
        withTimeout(10_000) { while (opened == null) kotlinx.coroutines.yield() }
        assertEquals("ann-1", opened)

        val failure = dev.stapler.stelekit.ui.annotate.OpenForAnnotationError.FileMissing("/x")
        v.annotateItem(item, { Either.Left(failure) }) { opened = "wrong" }
        await(v) { it.actionError != null }
        assertEquals(failure.message, v.state.value.actionError)
        assertEquals("ann-1", opened)
    }
}
