@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.asset.AssetMediaType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 6.8: the "Sketch/Drawing" media class. SQL results are compared with the in-memory reference. */
class LibrarySketchFilterTest {
    private val fx = LibraryFixture()

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun seed() {
        fx.add(1, "plan.png", AssetMediaType.IMAGE, 1_000L)
        fx.add(2, "floor.svg", AssetMediaType.DRAWING, 1_001L)
        fx.add(3, "elevation.svg", AssetMediaType.DRAWING, 1_002L)
        fx.add(4, "notes.bin", AssetMediaType.FILE, 1_003L)
    }

    private fun query(vararg types: LibraryMediaClass) = LibraryQuery(LibraryFilterState(types = types.toSet()))

    @Test
    fun sketchChip_listsOnlyDrawings_andCountsThem() = runBlocking {
        seed()
        val q = query(LibraryMediaClass.SKETCHES)
        val ids = fx.library.getLibraryPage(q, null, 100).getOrNull()!!.map { it.fileName }
        assertEquals(listOf("elevation.svg", "floor.svg"), ids)
        assertEquals(2L, fx.library.countLibrary(q).getOrNull())
        assertEquals(LibraryReference.page(fx.records, q, null, 100).map { it.fileName }, ids)
    }

    @Test
    fun otherChip_nolongerSwallowsDrawings() = runBlocking {
        seed()
        val ids = fx.library.getLibraryPage(query(LibraryMediaClass.OTHER), null, 100).getOrNull()!!.map { it.fileName }
        assertEquals(listOf("notes.bin"), ids)
    }

    @Test
    fun sketchAndImageChips_areOrWithinTheGroup() = runBlocking {
        seed()
        val q = query(LibraryMediaClass.SKETCHES, LibraryMediaClass.IMAGES)
        assertEquals(3L, fx.library.countLibrary(q).getOrNull())
    }

    @Test
    fun emptySketchFilter_returnsNothing_soTheScreenShowsItsEmptyState() = runBlocking {
        fx.add(1, "plan.png", AssetMediaType.IMAGE, 1_000L)
        assertEquals(0L, fx.library.countLibrary(query(LibraryMediaClass.SKETCHES)).getOrNull())
    }

    @Test
    fun sketchCard_opensTheSketchScreenOnlyWhenTheFlagIsOn() {
        val item = LibraryItem(
            id = "a", filePath = "/g/f.svg", fileName = "f.svg", mediaType = "DRAWING", sizeBytes = 1, importedAtMs = 1,
            isOrphan = false, pageUuids = emptyList(), annotated = false, measurementCount = 0, lastAnnotatedAtMs = null, annotationUuid = null,
        )
        assertEquals(LibraryOpenTarget.Sketch("a"), LibraryOpenTarget.of(item, homeSketchEnabled = true))
        assertEquals(LibraryOpenTarget.Detail("a"), LibraryOpenTarget.of(item, homeSketchEnabled = false))
    }
}
