package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossBlockSelectionTest {
    private val order = listOf("a", "b", "c")
    private val texts = mapOf("a" to "alpha", "b" to "beta", "c" to "gamma")

    private fun sel(au: String, ao: Int, fu: String, fo: Int) =
        CrossBlockSelection(BlockTextPosition(au, ao), BlockTextPosition(fu, fo))

    @Test
    fun spansBlocksWithPartialEndsAndFullMiddle() {
        val s = sel("a", 2, "c", 3)
        assertEquals(2 until 5, s.rangeFor("a", 5, order))
        assertEquals(0 until 4, s.rangeFor("b", 4, order))
        assertEquals(0 until 3, s.rangeFor("c", 5, order))
        assertEquals("pha\nbeta\ngam", s.selectedText(order, texts))
    }

    @Test
    fun reverseDragSelectsSameText() {
        assertEquals("pha\nbeta\ngam", sel("c", 3, "a", 2).selectedText(order, texts))
    }

    @Test
    fun blocksOutsideSelectionAreNull() {
        val s = sel("a", 1, "b", 2)
        assertNull(s.rangeFor("c", 5, order))
        assertEquals("lpha\nbe", s.selectedText(order, texts))
    }

    @Test
    fun withinSingleBlock() {
        assertEquals("lph", sel("a", 1, "a", 4).selectedText(order, texts))
    }
}
