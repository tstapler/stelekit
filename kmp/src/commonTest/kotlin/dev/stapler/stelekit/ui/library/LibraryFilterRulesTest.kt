package dev.stapler.stelekit.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryFilterRulesTest {
    private val images = LibraryChip.Type(LibraryMediaClass.IMAGES)
    private val pdfs = LibraryChip.Type(LibraryMediaClass.PDFS)

    @Test
    fun annotated_dims_nonImageTypes_withReason_andLeavesImagesEnabled() {
        val s = LibraryFilterState(annotated = true)
        assertEquals(LibraryDisabledReason.ANNOTATE_NEEDS_PHOTOS, LibraryFilterRules.disabledReason(pdfs, s))
        assertNull(LibraryFilterRules.disabledReason(images, s))
    }

    @Test
    fun nonImageType_dims_annotatedAndTagChips() {
        val s = LibraryFilterState(types = setOf(LibraryMediaClass.PDFS))
        assertEquals(LibraryDisabledReason.TYPE_NOT_PHOTO, LibraryFilterRules.disabledReason(LibraryChip.Annotated, s))
        assertEquals(LibraryDisabledReason.TYPE_NOT_PHOTO, LibraryFilterRules.disabledReason(LibraryChip.Tag("site"), s))
    }

    @Test
    fun annotatedWithOrphaned_isAllowed() {
        val s = LibraryFilterRules.toggle(LibraryFilterState(annotated = true), LibraryChip.Orphaned)
        assertTrue(s.annotated && s.orphaned)
        assertNull(LibraryFilterRules.disabledReason(LibraryChip.Orphaned, LibraryFilterState(annotated = true)))
    }

    @Test
    fun tappingADimmedChip_changesNothing() {
        val s = LibraryFilterState(annotated = true)
        assertEquals(s, LibraryFilterRules.toggle(s, pdfs))
    }

    @Test
    fun typeChipsAreMultiSelect() {
        var s = LibraryFilterState()
        s = LibraryFilterRules.toggle(s, images)
        s = LibraryFilterRules.toggle(s, pdfs)
        assertEquals(setOf(LibraryMediaClass.IMAGES, LibraryMediaClass.PDFS), s.types)
    }

    @Test
    fun normalize_dropsConflictsFromRestoredState_andTrimsSearch() {
        val restored = LibraryFilterState(types = setOf(LibraryMediaClass.IMAGES, LibraryMediaClass.PDFS), annotated = true, search = "  kit  ")
        val n = LibraryFilterRules.normalize(restored)
        assertEquals(setOf(LibraryMediaClass.IMAGES), n.types)
        assertEquals("kit", n.search)
    }

    @Test
    fun entryPoints_followLD8() {
        assertEquals(LibraryFilterState(annotated = true), LibraryFilterRules.fromEntryPoint(LibraryFilter.Annotated, "ignored"))
        assertEquals(LibraryFilterState(search = "kitchen"), LibraryFilterRules.fromEntryPoint(null, "kitchen"))
        assertEquals(LibraryFilterState(), LibraryFilterRules.fromEntryPoint(null, null))
    }

    @Test
    fun tagSelectionIsCapped() {
        var s = LibraryFilterState()
        (1..8).forEach { s = LibraryFilterRules.toggle(s, LibraryChip.Tag("t$it")) }
        assertEquals(LibraryFilterRules.MAX_TAGS, s.tags.size)
    }

    @Test
    fun disabledReason_isNullForChipsAlreadyOn() {
        val s = LibraryFilterState(annotated = true)
        assertNull(LibraryFilterRules.disabledReason(LibraryChip.Annotated, s))
        assertNotNull(LibraryFilterRules.disabledReason(pdfs, s))
    }
}
