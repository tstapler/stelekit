package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.asset.AssetMediaType
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SQL page/count queries must agree with [LibraryReference] (ADR-008 Default 9): same ids, same order,
 * across every sort, filter combination and the dedicated search cases, paging by keyset with no repeats.
 */
class LibraryFilterSqlParityTest {
    private val fx = LibraryFixture()

    @AfterTest
    fun tearDown() = fx.close()

    private val names = listOf("kitchen", "sink", "IMG_0012", "site-visit", "bathroom", "étage", "ÉTAGE", "50%off", "a_b", "report", "100%", "Kit Sink")
    private val tagPool = listOf("site", "Site", "kitchen", "a_b", "50%", "visit plan", "é", "back\\slash")
    private val labelPool = listOf("sink", "window", "KITCHEN island", null, "50% drawn")
    private val searches = listOf(
        "", "x", "kit", "KIT", "kit sink", "img 0012", "IMG_0012", "site visit", "site-visit", "100%", "50%", "a_b", "a b",
        "étage", "Étage", "back\\", "win", "island", "report", "no such words", "  kit  ",
    )

    private suspend fun seed(count: Int, seed: Int) {
        val rnd = Random(seed)
        repeat(count) { n ->
            val type = when (rnd.nextInt(10)) {
                0 -> AssetMediaType.PDF; 1 -> AssetMediaType.AUDIO; 2 -> AssetMediaType.VIDEO
                3 -> AssetMediaType.DOCUMENT; 4 -> AssetMediaType.FILE; else -> AssetMediaType.IMAGE
            }
            val name = names.random(rnd) + (if (rnd.nextBoolean()) " ${names.random(rnd)}" else "") + "-$n." + type.name.lowercase()
            val annotation = if (type == AssetMediaType.IMAGE && rnd.nextInt(10) < 4) {
                AnnotationSpec(
                    tags = List(rnd.nextInt(4)) { tagPool.random(rnd) }.distinct(),
                    labels = List(rnd.nextInt(4)) { labelPool.random(rnd) },
                    pages = if (rnd.nextInt(10) == 0) 2 else 1,
                    annotatedAtMs = 5_000L + rnd.nextInt(6),
                )
            } else null
            // Few distinct timestamps so every sort has ties on its leading key components.
            fx.add(n, name, type, importedAtMs = 1_000L + rnd.nextInt(6), orphan = rnd.nextInt(10) < 3, annotation = annotation)
        }
    }

    private fun randomFilters(rnd: Random): LibraryFilterState {
        val types = LibraryMediaClass.entries.filter { rnd.nextInt(5) == 0 }.toSet()
        return LibraryFilterRules.normalize(
            LibraryFilterState(
                types = types,
                annotated = rnd.nextInt(4) == 0,
                orphaned = rnd.nextInt(4) == 0,
                tags = tagPool.filter { rnd.nextInt(8) == 0 }.toSet(),
                search = if (rnd.nextInt(3) == 0) "" else searches.random(rnd),
            ),
        )
    }

    private suspend fun readAll(query: LibraryQuery, pageSize: Int): List<LibraryItem> {
        val out = mutableListOf<LibraryItem>()
        var cursor: LibraryCursor? = null
        while (true) {
            val page = fx.library.getLibraryPage(query, cursor, pageSize).getOrNull()!!
            out += page
            if (page.size < pageSize) return out
            cursor = LibraryCursor.of(page.last(), query.sort)
        }
    }

    @Test
    fun randomQueries_returnTheSameIdsInTheSameOrderAsTheReference() = runBlocking {
        seed(220, seed = 7)
        val rnd = Random(99)
        var nonEmpty = 0
        repeat(70) {
            val filters = randomFilters(rnd)
            for (sort in LibrarySort.entries) {
                val query = LibraryQuery(filters, sort)
                val expected = LibraryReference.page(fx.records, query, null, Int.MAX_VALUE).map { it.id }
                val actual = readAll(query, pageSize = 7).map { it.id }
                assertEquals(expected, actual, "filters=$filters sort=$sort")
                assertEquals(expected.size.toLong(), fx.library.countLibrary(query).getOrNull(), "count for $filters")
                if (expected.isNotEmpty()) nonEmpty++
            }
        }
        assertTrue(nonEmpty > 50, "fixture too sparse to be meaningful: only $nonEmpty non-empty result sets")
    }

    @Test
    fun dedicatedSearchCases_agreeWithTheReference() = runBlocking {
        seed(220, seed = 11)
        for (text in searches) {
            for (sort in listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME)) {
                val query = LibraryQuery(LibraryFilterRules.normalize(LibraryFilterState(search = text)), sort)
                val expected = LibraryReference.page(fx.records, query, null, Int.MAX_VALUE).map { it.id }
                assertEquals(expected, readAll(query, 13).map { it.id }, "search='$text' sort=$sort")
            }
        }
    }

    @Test
    fun oneCharacterSearch_isNoSearch_andPercentAndBackslashAreLiteral() = runBlocking {
        seed(120, seed = 3)
        val all = LibraryReference.page(fx.records, LibraryQuery(), null, Int.MAX_VALUE).size
        assertEquals(all, readAll(LibraryQuery(LibraryFilterState(search = "k")), 50).size)
        val pct = readAll(LibraryQuery(LibraryFilterState(search = "100%")), 50)
        assertTrue(pct.all { "100%" in it.fileName || it.fileName.contains("100") }, "only literal 100% words match")
        assertTrue(pct.size < all)
    }

    @Test
    fun keysetPaging_listsEveryRowExactlyOnce_withTiesOnEveryLeadingKey() = runBlocking {
        seed(260, seed = 21)
        for (sort in LibrarySort.entries) {
            val ids = readAll(LibraryQuery(sort = sort), pageSize = 9).map { it.id }
            assertEquals(ids.toSet().size, ids.size, "a row repeated under $sort")
            assertEquals(fx.records.size, ids.size, "rows skipped under $sort")
        }
    }

    @Test
    fun multiPageImage_isOneItem_withTheSumOfMeasurements() = runBlocking {
        fx.add(1, "two-pages.png", AssetMediaType.IMAGE, 1_000L, annotation = AnnotationSpec(labels = listOf("a", "b"), pages = 2, annotatedAtMs = 9L))
        val items = readAll(LibraryQuery(), 10)
        assertEquals(1, items.size)
        assertEquals(4, items.single().measurementCount)
        assertTrue(items.single().annotated)
    }

    @Test
    fun unannotatedItemsSortLast_forTheTwoAnnotationSorts() = runBlocking {
        seed(150, seed = 5)
        for (sort in listOf(LibrarySort.RECENTLY_ANNOTATED, LibrarySort.MOST_MEASUREMENTS)) {
            val flags = readAll(LibraryQuery(sort = sort), 40).map { it.annotated }
            assertEquals(flags.sortedByDescending { it }, flags, "annotated items first under $sort")
        }
    }
}
