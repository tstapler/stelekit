package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorToolbarCursorResolutionTest {

    @Test
    fun prefersLiveSelectionOverStaleEditingCursorIndex() {
        // Regression: a prior structural op (merge/split/indent) left editingCursorIndex at 0,
        // but the user has since moved the caret elsewhere without another structural op firing.
        assertEquals(7, resolveLinkPickerCursorIndex(editingCursorIndex = 0, liveSelectionStart = 7))
    }

    @Test
    fun fallsBackToEditingCursorIndex_whenNoLiveSelection() {
        assertEquals(3, resolveLinkPickerCursorIndex(editingCursorIndex = 3, liveSelectionStart = null))
    }

    @Test
    fun returnsNull_whenNeitherIsAvailable() {
        assertEquals(null, resolveLinkPickerCursorIndex(editingCursorIndex = null, liveSelectionStart = null))
    }
}
