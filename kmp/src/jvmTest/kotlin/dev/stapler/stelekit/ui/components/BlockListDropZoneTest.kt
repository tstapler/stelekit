package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [computeDropTarget] (docs/ux/block-reorder-permutations.md §8 punch-list
 * item 7) — the pure zone-boundary math extracted out of BlockList's composable scope.
 */
class BlockListDropZoneTest {

    private val minChildZonePx = 12f
    private val outOfBoundsMarginPx = 320f

    @Test
    fun emptyBounds_returnsNoTarget() {
        val result = computeDropTarget(
            pointerY = 50f,
            blockBounds = emptyMap(),
            draggedUuids = emptySet(),
            blockedTargetUuids = emptySet(),
            minChildZonePx = minChildZonePx,
            outOfBoundsMarginPx = outOfBoundsMarginPx,
        )
        assertNull(result.targetUuid)
        assertNull(result.zone)
        assertFalse(result.isBlocked)
    }

    // Single row, top=0f/bottom=100f (height 100 -> 20% child zone = 20px, well above the
    // 12px floor): expect ABOVE for y in [0,40), CHILD for [40,60], BELOW for (60,100].
    private val singleRowBounds = mapOf("row" to (0f to 100f))

    @Test
    fun pointerInTopBand_resolvesAbove() {
        val result = computeDropTarget(10f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals("row", result.targetUuid)
        assertEquals(DropZone.ABOVE, result.zone)
    }

    @Test
    fun pointerAtCenter_resolvesChild() {
        val result = computeDropTarget(50f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals(DropZone.CHILD, result.zone)
    }

    @Test
    fun pointerJustInsideChildZoneBoundary_resolvesChild() {
        // childZoneStart = 40, childZoneEnd = 60 for a 100px row (20% = 20px zone, centered).
        val above = computeDropTarget(40f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals(DropZone.CHILD, above.zone, "boundary itself belongs to CHILD (pointerY < start is exclusive)")
        val below = computeDropTarget(60f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals(DropZone.CHILD, below.zone, "boundary itself belongs to CHILD (pointerY > end is exclusive)")
    }

    @Test
    fun pointerInBottomBand_resolvesBelow() {
        val result = computeDropTarget(90f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals(DropZone.BELOW, result.zone)
    }

    // A very short row (8px tall) — nominal 20% child zone (1.6px) is floored to 12px, but
    // capped at the row's own height (8px) so it never exceeds the row.
    @Test
    fun shortRow_childZoneFlooredButCappedAtRowHeight() {
        val shortRowBounds = mapOf("row" to (0f to 8f))
        // Entire row should resolve to CHILD since the floored zone (12px) is capped to 8px,
        // i.e. the whole row height.
        val top = computeDropTarget(0f, shortRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        val mid = computeDropTarget(4f, shortRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        val bottom = computeDropTarget(8f, shortRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals(DropZone.CHILD, top.zone)
        assertEquals(DropZone.CHILD, mid.zone)
        assertEquals(DropZone.CHILD, bottom.zone)
    }

    @Test
    fun draggedUuids_areExcludedFromCandidates() {
        val bounds = mapOf("dragged" to (0f to 100f), "other" to (100f to 200f))
        val result = computeDropTarget(50f, bounds, setOf("dragged"), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals("other", result.targetUuid, "the only non-dragged row must be picked even though pointer is nearer 'dragged'")
    }

    @Test
    fun nearestCandidateByCenterDistance_isPicked() {
        val bounds = mapOf(
            "top" to (0f to 100f),      // center 50, distance from 190 = 140
            "bottom" to (100f to 300f), // center 200, distance from 190 = 10
        )
        val result = computeDropTarget(190f, bounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertEquals("bottom", result.targetUuid, "190 is closer to bottom's center (200) than top's (50)")
    }

    @Test
    fun pointerWithinOutOfBoundsMargin_stillResolves() {
        // listTop=0, listBottom=100; pointer at -100 is within the 320px margin above.
        val result = computeDropTarget(-100f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertTrue(result.targetUuid != null, "within margin should still resolve to the nearest row")
    }

    @Test
    fun pointerBeyondOutOfBoundsMargin_cancelsDrop() {
        // listBottom=100; pointer at 100 + 320 + 1 exceeds the margin below.
        val result = computeDropTarget(421f, singleRowBounds, emptySet(), emptySet(), minChildZonePx, outOfBoundsMarginPx)
        assertNull(result.targetUuid)
        assertNull(result.zone)
        assertFalse(result.isBlocked)
    }

    @Test
    fun targetInsideBlockedSet_isFlaggedBlocked() {
        val bounds = mapOf("descendant" to (0f to 100f))
        val result = computeDropTarget(50f, bounds, emptySet(), setOf("descendant"), minChildZonePx, outOfBoundsMarginPx)
        assertEquals("descendant", result.targetUuid, "nearest candidate is still returned even when blocked")
        assertTrue(result.isBlocked, "target inside the dragged subtree must be flagged blocked")
    }

    @Test
    fun targetNotInBlockedSet_isNotFlaggedBlocked() {
        val bounds = mapOf("safe" to (0f to 100f))
        val result = computeDropTarget(50f, bounds, emptySet(), setOf("someone-else"), minChildZonePx, outOfBoundsMarginPx)
        assertFalse(result.isBlocked)
    }
}
