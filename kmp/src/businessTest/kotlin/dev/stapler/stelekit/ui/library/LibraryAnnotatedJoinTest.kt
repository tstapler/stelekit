@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.asset.AssetMediaType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The annotation state comes back from the same query that returned the page (ADR-008): no N+1. */
class LibraryAnnotatedJoinTest {
    private val fx = LibraryFixture()

    @AfterTest
    fun tearDown() = fx.close()

    @Test
    fun aPageOf100With7Annotated_marksExactlyThoseSeven() = runBlocking {
        repeat(100) { n ->
            val annotation = if (n % 14 == 0 && n < 98) AnnotationSpec(tags = listOf("site"), labels = listOf("w", "h"), annotatedAtMs = 50L + n) else null
            fx.add(n, "p$n.png", AssetMediaType.IMAGE, importedAtMs = 1_000L + n, annotation = annotation)
        }
        val page = fx.library.getLibraryPage(LibraryQuery(), null, 100).getOrNull()!!
        assertEquals(100, page.size)
        val annotated = page.filter { it.annotated }
        assertEquals(7, annotated.size)
        assertTrue(annotated.all { it.measurementCount == 2 && it.annotationUuid != null && it.lastAnnotatedAtMs != null })
        assertTrue(page.filterNot { it.annotated }.all { it.measurementCount == 0 && it.annotationUuid == null })
    }

    @Test
    fun annotatedFilter_listsOnlyThoseSeven_andCountsThem() = runBlocking {
        repeat(100) { n ->
            fx.add(n, "p$n.png", AssetMediaType.IMAGE, 1_000L + n, annotation = if (n % 14 == 0 && n < 98) AnnotationSpec() else null)
        }
        val query = LibraryQuery(LibraryFilterState(annotated = true))
        assertEquals(7, fx.library.getLibraryPage(query, null, 100).getOrNull()!!.size)
        assertEquals(7L, fx.library.countLibrary(query).getOrNull())
    }

    @Test
    fun annotatedOrphan_isListedUnderAnnotatedPlusOrphaned() = runBlocking {
        fx.add(1, "lost.png", AssetMediaType.IMAGE, 1_000L, orphan = true, annotation = AnnotationSpec(labels = listOf("a")))
        fx.add(2, "kept.png", AssetMediaType.IMAGE, 1_001L, annotation = AnnotationSpec())
        fx.add(3, "plain.png", AssetMediaType.IMAGE, 1_002L, orphan = true)
        val both = LibraryQuery(LibraryFilterState(annotated = true, orphaned = true))
        assertEquals(listOf("asset-000001"), fx.library.getLibraryPage(both, null, 10).getOrNull()!!.map { it.id })
    }

    @Test
    fun annotationWithoutAnAssetRow_isNotInTheAssetBackedList() = runBlocking {
        // Recorded limitation (ADR-008 UNION ALL branch): such rows are only reachable once the asset index has them.
        fx.annotations.saveImageAnnotation(
            dev.stapler.stelekit.model.ImageAnnotation(
                uuid = "ann-x", blockUuid = "blk-x", pageUuid = "page-x", graphPath = "/g", filePath = "/g/assets/images/unindexed.png",
            ),
        )
        assertTrue(fx.library.getLibraryPage(LibraryQuery(), null, 10).getOrNull()!!.isEmpty())
    }

    @Test
    fun tagProjection_isBoundedAndOrderedByUsage() = runBlocking {
        repeat(5) { fx.add(it, "a$it.png", AssetMediaType.IMAGE, 1_000L + it, annotation = AnnotationSpec(tags = listOf("common", "rare$it"))) }
        val tags = fx.library.getDistinctAnnotationTags(limit = 3).getOrNull()!!
        assertEquals(3, tags.size)
        assertEquals("common", tags.first())
        assertFalse(fx.library.getDistinctAnnotationTags(200).getOrNull()!!.isEmpty())
    }
}
