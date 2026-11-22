package dev.stapler.stelekit.ui.components

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableColumnLayoutTest {

    private class Columns(val mins: IntArray, val maxs: IntArray)

    private val columns = arbitrary {
        val n = Arb.int(1..8).bind()
        val mins = IntArray(n) { Arb.int(0..300).bind() }
        Columns(mins, IntArray(n) { mins[it] + Arb.int(0..400).bind() })
    }

    @Test
    fun fitsAtNaturalWidthWhenThereIsRoom() {
        val w = computeColumnWidths(intArrayOf(40, 30), intArrayOf(120, 90), available = 500, overflow = TableOverflow.WRAP)
        assertContentEquals(intArrayOf(120, 90), w)
    }

    @Test
    fun shrinksProportionallyToFillExactlyTheAvailableWidth() {
        val w = computeColumnWidths(intArrayOf(40, 40), intArrayOf(240, 140), available = 300, overflow = TableOverflow.WRAP)
        assertEquals(300, w.sum())
        assertTrue(w[0] > w[1], "the column with more content keeps more width: ${w.toList()}")
    }

    @Test
    fun fallsBackToMinimumsWhenEvenThoseDoNotFit() {
        val w = computeColumnWidths(intArrayOf(200, 200), intArrayOf(300, 300), available = 300, overflow = TableOverflow.WRAP)
        assertContentEquals(intArrayOf(200, 200), w)
    }

    @Test
    fun scrollModeKeepsNaturalWidthButCapsAnOutlier() {
        val w = computeColumnWidths(intArrayOf(10, 10), intArrayOf(50, 2000), available = 100, overflow = TableOverflow.SCROLL, maxColumnWidth = 480)
        assertContentEquals(intArrayOf(50, 480), w)
    }

    @Test
    fun wrapWidthsStayWithinEachColumnsMinAndMaxAndFitWhenPossible() = runTest {
        checkAll(columns, Arb.int(0..2500)) { c, available ->
            val w = computeColumnWidths(c.mins, c.maxs, available, TableOverflow.WRAP)
            for (i in w.indices) {
                assertTrue(w[i] >= c.mins[i] && w[i] <= c.maxs[i], "col $i width ${w[i]} outside [${c.mins[i]}, ${c.maxs[i]}]")
            }
            val sumMin = c.mins.sum()
            val sumMax = c.maxs.sum()
            when {
                sumMax <= available -> assertContentEquals(c.maxs, w)
                sumMin >= available -> assertContentEquals(c.mins, w)
                else -> assertEquals(available, w.sum(), "must fill the available width exactly")
            }
        }
    }

    @Test
    fun scrollWidthIsNaturalWidthClampedBetweenMinimumAndCap() = runTest {
        checkAll(columns, Arb.int(0..600)) { c, cap ->
            val w = computeColumnWidths(c.mins, c.maxs, 0, TableOverflow.SCROLL, cap)
            val expected = IntArray(w.size) { c.maxs[it].coerceAtMost(cap).coerceAtLeast(c.mins[it]) }
            assertContentEquals(expected, w)
        }
    }

    @Test
    fun widerAvailableSpaceNeverShrinksTheTable() = runTest {
        checkAll(columns, Arb.int(0..2000), Arb.int(0..500)) { c, available, more ->
            val narrow = computeColumnWidths(c.mins, c.maxs, available, TableOverflow.WRAP)
            val wide = computeColumnWidths(c.mins, c.maxs, available + more, TableOverflow.WRAP)
            assertTrue(wide.sum() >= narrow.sum())
        }
    }
}
