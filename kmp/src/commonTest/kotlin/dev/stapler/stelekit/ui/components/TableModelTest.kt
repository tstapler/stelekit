package dev.stapler.stelekit.ui.components

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableModelTest {

    @Test
    fun delimiterRowMarkersMapToLeftCenterRightAndNone() {
        val data = parseTableContent("| A | B | C | D |\n| :--- | :---: | ---: | --- |\n| 1 | 2 | 3 | 4 |")
        assertEquals(
            listOf(TableColumnAlignment.LEFT, TableColumnAlignment.CENTER, TableColumnAlignment.RIGHT, null),
            data.explicitAlignments,
        )
    }

    @Test
    fun compactDelimiterRowWithoutSpacesStillParses() {
        val data = parseTableContent("|A|B|C|\n|:-|:-:|-:|\n|1|2|3|")
        assertEquals(
            listOf(TableColumnAlignment.LEFT, TableColumnAlignment.CENTER, TableColumnAlignment.RIGHT),
            data.explicitAlignments,
        )
    }

    @Test
    fun alignmentMarkersAreRecoveredForAnyDashCountAndPadding() = runTest {
        checkAll(Arb.int(1..12), Arb.boolean(), Arb.boolean(), Arb.int(0..3)) { dashes, left, right, pad ->
            val cell = " ".repeat(pad) + (if (left) ":" else "") + "-".repeat(dashes) + (if (right) ":" else "") + " ".repeat(pad)
            val expected = when {
                left && right -> TableColumnAlignment.CENTER
                right -> TableColumnAlignment.RIGHT
                left -> TableColumnAlignment.LEFT
                else -> null
            }
            assertEquals(expected, parseAlignmentCell(cell), "cell='$cell'")
        }
    }

    @Test
    fun shortRowsArePaddedSoEveryRowSharesOneGrid() {
        val data = parseTableContent("| A | B | C |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 |")
        assertEquals(3, data.columnCount)
        assertTrue(data.rows.all { it.size == 3 })
        assertEquals(listOf("1", "", ""), data.rows[0])
    }

    @Test
    fun escapedPipeStaysInsideItsCell() {
        assertEquals(listOf("a|b", "c"), splitTableRow("| a\\|b | c |"))
    }

    @Test
    fun numericCellsAreRecognisedWithUnitsMarkupAndSeparators() {
        listOf("6", "140 ft", "1,234.5", "-3", "+2.5", "12%", "\$4", "**249.4**", ".5", "1e3", "2.62 A").forEach {
            assertTrue(isNumericCell(it), "'$it' should be numeric")
        }
        listOf("", "Pixel track (RGBW)", "2026-11-21", "n/a", "1.2.3", "W each").forEach {
            assertFalse(isNumericCell(it), "'$it' should not be numeric")
        }
    }

    @Test
    fun unmarkedNumericColumnIsRightAlignedOnlyWhenPreferenceIsOn() {
        val data = parseTableContent("| Load | Qty |\n|---|---|\n| Pixel | 140 ft |\n| Uplight | 6 |\n| **DC total** |  |")
        assertEquals(listOf(false, true), data.numericColumns())
        assertEquals(listOf(TableColumnAlignment.LEFT, TableColumnAlignment.RIGHT), data.effectiveAlignments(numericRightAlign = true))
        assertEquals(listOf(TableColumnAlignment.LEFT, TableColumnAlignment.LEFT), data.effectiveAlignments(numericRightAlign = false))
    }

    @Test
    fun explicitMarkerBeatsNumericAutoAlignment() {
        val data = parseTableContent("| Qty |\n|:---|\n| 1 |\n| 22 |")
        assertEquals(listOf(TableColumnAlignment.LEFT), data.effectiveAlignments(numericRightAlign = true))
    }

    @Test
    fun explicitMarkersWinForAnyCellContent() = runTest {
        val markers = listOf(":---", ":---:", "---:", "---")
        val expected = listOf(TableColumnAlignment.LEFT, TableColumnAlignment.CENTER, TableColumnAlignment.RIGHT, null)
        checkAll(Arb.list(Arb.int(0..3), 1..5), Arb.list(Arb.int(0..999), 1..5)) { markerIdx, nums ->
            val row = markerIdx.indices.joinToString("|", "|", "|") { i -> nums[i % nums.size].let { if (it % 2 == 0) "$it" else "w$it" } }
            val header = markerIdx.joinToString("|", "|", "|") { "h" }
            val delim = markerIdx.joinToString("|", "|", "|") { markers[it] }
            val data = parseTableContent("$header\n$delim\n$row")
            markerIdx.forEachIndexed { col, idx ->
                val marker = expected[idx] ?: return@forEachIndexed
                assertEquals(marker, data.effectiveAlignments(true)[col])
                assertEquals(marker, data.effectiveAlignments(false)[col])
            }
        }
    }
}
