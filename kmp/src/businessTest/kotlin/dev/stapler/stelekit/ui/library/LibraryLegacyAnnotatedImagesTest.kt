@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pre-0.108 user: `image_annotations` rows exist (some outside assets/), `asset_index` is empty. */
class LibraryLegacyAnnotatedImagesTest {
    private val fx = LibraryFixture()

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun annotate(n: Int, path: String, at: Long) {
        fx.annotations.saveImageAnnotation(
            ImageAnnotation(
                uuid = "ann-$n", blockUuid = "blk-$n", pageUuid = "page-$n", graphPath = "/g",
                filePath = path, importedAtMs = at, tags = listOf("site"),
            ),
        )
    }

    private suspend fun page(query: LibraryQuery = LibraryQuery(), limit: Int = 100) =
        fx.library.getLibraryPage(query, null, limit).getOrNull()!!

    @Test
    fun annotationOnlyImages_listEvenWithAnEmptyAssetIndex_includingOutsideAssets() = runBlocking {
        annotate(1, "/g/assets/a.png", 1_000L)
        annotate(2, "/elsewhere/photos/b.jpg", 2_000L)

        val items = page()

        assertEquals(listOf("/elsewhere/photos/b.jpg", "/g/assets/a.png"), items.map { it.filePath })
        assertTrue(items.all { it.annotated && it.isImage })
        assertEquals(2L, fx.library.countLibrary(LibraryQuery()).getOrNull())
        assertEquals("b.jpg", items.first().fileName)
    }

    @Test
    fun legacyItems_openTheAnnotationEditor() = runBlocking {
        annotate(1, "/g/assets/a.png", 1_000L)

        val item = page().single()

        assertEquals(LibraryOpenTarget.Editor("ann-1"), LibraryOpenTarget.of(item, homeSketchEnabled = false))
    }

    @Test
    fun legacyItems_paginateWithKeysetCursorsInEverySort() = runBlocking {
        repeat(7) { annotate(it, "/g/assets/img$it.png", 1_000L + it) }
        for (sort in LibrarySort.entries) {
            val q = LibraryQuery(sort = sort)
            val seen = mutableListOf<String>()
            var cursor: LibraryCursor? = null
            while (true) {
                val batch = fx.library.getLibraryPage(q, cursor, 3).getOrNull()!!
                if (batch.isEmpty()) break
                seen += batch.map { it.id }
                cursor = LibraryCursor.of(batch.last(), sort)
            }
            assertEquals(7, seen.size, "$sort")
            assertEquals(7, seen.toSet().size, "$sort: no duplicates")
        }
    }

    @Test
    fun oneFileWithManyAnnotationRows_isOneItem_andIndexedFilesAreNotDuplicated() = runBlocking {
        annotate(1, "/g/assets/a.png", 1_000L)
        annotate(2, "/g/assets/a.png", 1_500L)
        annotate(3, "/g/assets/b.png", 2_000L)
        assertEquals(2, page().size)

        fx.assets.saveAsset(
            AssetEntry(
                uuid = AssetUuid("asset-b"), filePath = "/g/assets/b.png", relativePath = "../assets/b.png", mediaType = AssetMediaType.IMAGE,
                subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
                pageUuids = emptyList(), sizeBytes = 1L, importedAtMs = 3_000L, mlProcessed = false, mlAttemptedAt = null,
                mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
            ),
        )

        val items = page()
        assertEquals(listOf("/g/assets/b.png", "/g/assets/a.png"), items.map { it.filePath })
        assertEquals("asset-b", items.first().id)
        assertEquals(2L, fx.library.countLibrary(LibraryQuery()).getOrNull())
    }

    @Test
    fun filtersApplyToLegacyItems() = runBlocking {
        annotate(1, "/g/assets/kitchen.png", 1_000L)
        val search = LibraryFilterRules.normalize(LibraryFilterState(search = "kitchen"))
        assertEquals(1, page(LibraryQuery(search)).size)
        val pdfs = LibraryFilterRules.normalize(LibraryFilterState(types = setOf(LibraryMediaClass.PDFS)))
        assertEquals(0, page(LibraryQuery(pdfs)).size)
    }
}
