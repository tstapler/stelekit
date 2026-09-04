package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [hitTestNearestBlockRow] — the pure row-resolution math backing the
 * click-and-drag lasso-select gesture (a drag started on a block's bullet, resolved by
 * [BlockList] into calls to `onEnterSelectionMode`/`onShiftClick`).
 */
class BlockListLassoSelectTest {

    private val bounds = mapOf(
        "first" to (0f to 100f),
        "second" to (100f to 200f),
        "third" to (200f to 300f),
    )

    @Test
    fun emptyBounds_returnsNull() {
        assertNull(hitTestNearestBlockRow(50f, emptyMap()))
    }

    @Test
    fun pointerInsideARow_resolvesThatRow() {
        assertEquals("second", hitTestNearestBlockRow(150f, bounds))
    }

    @Test
    fun pointerAboveFirstRow_clampsToFirstRow() {
        // Overshooting above the top of the list still resolves to the nearest (topmost) row,
        // rather than nothing — a lasso drag that flicks past the top of the list keeps
        // extending the selection to the first block instead of losing the gesture.
        assertEquals("first", hitTestNearestBlockRow(-500f, bounds))
    }

    @Test
    fun pointerBelowLastRow_clampsToLastRow() {
        assertEquals("third", hitTestNearestBlockRow(1000f, bounds))
    }

    @Test
    fun pointerExactlyOnBoundary_resolvesNearerCenter() {
        // y=100 is equidistant only in absolute terms from the touching edges, but nearest by
        // center distance: first's center=50 (distance 50), second's center=150 (distance 50) —
        // a tie resolves to whichever minByOrNull encounters first (map iteration order).
        val hit = hitTestNearestBlockRow(100f, bounds)
        assertEquals("first", hit, "tie between equidistant rows resolves to the first-iterated entry")
    }

    @Test
    fun reverseDirection_tracksPointerBackAcrossRows() {
        // Simulates a drag that moves forward then reverses — each call is independent and
        // pointer-position-only, so reversing direction naturally re-resolves to the row the
        // pointer is now over, with no leftover state from the earlier direction.
        assertEquals("third", hitTestNearestBlockRow(250f, bounds))
        assertEquals("second", hitTestNearestBlockRow(150f, bounds))
        assertEquals("first", hitTestNearestBlockRow(20f, bounds))
    }
}
