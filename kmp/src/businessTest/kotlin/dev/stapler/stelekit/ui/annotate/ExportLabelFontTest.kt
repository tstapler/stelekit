// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportLabelFontTest {
    private fun cells(text: String, cell: Float = 1f): Set<Pair<Int, Int>> {
        val lit = mutableSetOf<Pair<Int, Int>>()
        ExportLabelFont.layout(text, 0f, 0f, cell) { l, t, r, b ->
            for (x in l.toInt() until r.toInt()) for (y in t.toInt() until b.toInt()) lit += x to y
        }
        return lit
    }

    @Test
    fun glyph_should_TreatLowercaseAndSuperscriptsAsTheirBaseCharacter() {
        assertContentEquals(ExportLabelFont.glyph('M'), ExportLabelFont.glyph('m'))
        assertContentEquals(ExportLabelFont.glyph('2'), ExportLabelFont.glyph('²'))
        assertContentEquals(ExportLabelFont.glyph('3'), ExportLabelFont.glyph('³'))
    }

    @Test
    fun glyph_should_DrawABox_When_CharacterIsUnsupported() {
        assertContentEquals(intArrayOf(0x7F, 0x41, 0x41, 0x41, 0x7F), ExportLabelFont.glyph('中'))
    }

    @Test
    fun glyph_should_HaveFiveColumnsForEveryPrintableAscii() {
        for (code in 0x20..0x7E) assertEquals(5, ExportLabelFont.glyph(code.toChar()).size)
        assertEquals(5, ExportLabelFont.glyph('°').size)
    }

    @Test
    fun layout_should_DrawAnIdentifiablePixelPattern_ForTheDigitOne() {
        // '1' is columns 00 42 7F 40 00: the stem is column 2, all seven rows.
        val lit = cells("1")
        for (row in 0 until 7) assertTrue((2 to row) in lit, "stem row $row")
        assertTrue((1 to 1) in lit && (1 to 0) !in lit)
        assertTrue(lit.none { it.first == 0 || it.first == 4 })
    }

    @Test
    fun layout_should_AdvanceOneCellColumnSixPerCharacter_And_ScaleWithCell() {
        val one = cells("1")
        assertEquals(one.map { it.first + 6 to it.second }.toSet(), cells("11").filter { it.first >= 6 }.toSet())
        assertEquals(one.size * 9, cells("1", 3f).size)
    }

    @Test
    fun textWidth_should_ExcludeTheTrailingGap() {
        assertEquals(0f, ExportLabelFont.textWidth("", 2f))
        assertEquals(10f, ExportLabelFont.textWidth("a", 2f))
        assertEquals(22f, ExportLabelFont.textWidth("ab", 2f))
        assertEquals(14f, ExportLabelFont.textHeight(2f))
    }

    @Test
    fun layout_should_KeepEveryRectInsideTheTextBounds() {
        val text = "3.20 M °"
        val width = ExportLabelFont.textWidth(text, 2f)
        ExportLabelFont.layout(text, 10f, 20f, 2f) { l, t, r, b ->
            assertTrue(l >= 10f && r <= 10f + width && t >= 20f && b <= 20f + ExportLabelFont.textHeight(2f))
        }
    }
}
