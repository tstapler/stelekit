package dev.stapler.stelekit.ui.library

import arrow.core.Either
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.LibraryRepository
import dev.stapler.stelekit.repository.LibrarySql
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors LargeGraphWarmStartCrashTest for the Library: 8,030 assets (the LargeGraphWarmStartCrashTest scale), bounded reads only. */
class LargeLibraryBatchingTest {
    private val fx = LibraryFixture()

    @AfterTest
    fun tearDown() = fx.close()

    private class Spy(private val inner: LibraryRepository) : LibraryRepository {
        val pageLimits = mutableListOf<Int>()
        var counts = 0
        override suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>> {
            pageLimits += limit
            return inner.getLibraryPage(query, cursor, limit)
        }
        override suspend fun countLibrary(query: LibraryQuery): Either<DomainError, Long> { counts++; return inner.countLibrary(query) }
        override suspend fun getDistinctAnnotationTags(limit: Int) = inner.getDistinctAnnotationTags(limit)
    }

    @Test
    fun scrollingToTheEndOf8030Assets_issuesOneBoundedQueryPerPage() = runBlocking {
        repeat(ASSETS) { n ->
            // Some annotated images carry a second annotation row (a second page of the same file).
            val annotation = when {
                n % 2 == 0 && n < 60 -> AnnotationSpec(pages = 2, annotatedAtMs = n.toLong())
                n % 8 == 0 -> AnnotationSpec(annotatedAtMs = n.toLong())
                else -> null
            }
            fx.add(n, "img-$n.png", AssetMediaType.IMAGE, importedAtMs = 1_000L + n % 500, annotation = annotation)
        }
        val spy = Spy(fx.library)
        val vm = LibraryViewModel(spy)
        awaitState(vm) { !it.isLoading && it.totalCount != null }
        while (vm.state.value.hasMore) {
            val before = vm.state.value.items.size
            vm.loadMore()
            awaitState(vm) { !it.isLoadingMore && it.items.size > before || !it.hasMore }
        }
        val items = vm.state.value.items
        vm.close()

        assertEquals(ASSETS, items.size)
        assertEquals(ASSETS, items.map { it.id }.toSet().size, "a row repeated across pages")
        assertTrue(spy.pageLimits.all { it <= 100 }, "an unbounded read: ${spy.pageLimits.maxOrNull()}")
        assertEquals(ASSETS / 100 + 1, spy.pageLimits.size, "exactly one query per page")
        assertEquals(1, spy.counts, "one debounced count, not one per page")
        val expectedAnnotated = (0 until ASSETS).count { it % 2 == 0 && it < 60 || it % 8 == 0 }
        assertEquals(expectedAnnotated, items.count { it.annotated })
    }

    @Test
    fun firstPageOfEverySortAndTheSearchPathStaysFastOn8030Rows() = runBlocking {
        repeat(ASSETS) { n ->
            val annotation = if (n % 4 == 0) AnnotationSpec(tags = listOf("site"), labels = listOf("window"), annotatedAtMs = n.toLong()) else null
            fx.add(n, "img-$n.png", AssetMediaType.IMAGE, importedAtMs = 1_000L + n % 500, annotation = annotation)
        }
        val timings = mutableListOf<String>()
        for (sort in LibrarySort.entries) {
            for ((label, filters) in listOf("all" to LibraryFilterState(), "search" to LibraryFilterState(search = "img win"), "annotated" to LibraryFilterState(annotated = true))) {
                val start = System.nanoTime()
                val page = fx.library.getLibraryPage(LibraryQuery(filters, sort), null, 100).getOrNull()!!
                val ms = (System.nanoTime() - start) / 1_000_000
                timings += "$sort/$label=${ms}ms(${page.size})"
                assertTrue(ms < SLOW_MS, "$sort/$label took ${ms}ms on $ASSETS rows")
            }
        }
        println("LIBRARY_QUERY_TIMINGS " + timings.joinToString(" "))
    }

    @Test
    fun theCountIsCappedAtTenThousandPlusOne() = runBlocking {
        repeat(30) { n -> fx.add(n, "x$n.png", AssetMediaType.IMAGE, 1_000L + n) }
        assertEquals(30L, fx.library.countLibrary(LibraryQuery()).getOrNull())
        assertTrue(LibrarySql.count(LibraryQuery()).sql.contains("LIMIT ${LibrarySql.COUNT_CAP + 1}"))
    }

    private suspend fun awaitState(vm: LibraryViewModel, cond: (LibraryState) -> Boolean) {
        withTimeout(60_000) { vm.state.first(cond) }
    }

    private companion object {
        const val ASSETS = 8_030
        const val SLOW_MS = 3_000L
    }
}
