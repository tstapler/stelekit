package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TabularTextPropertyTest {
    // Heavy on the characters that need quoting; delimiters are passed explicitly so detection never interferes.
    private val chars = listOf("a", "b", " ", "\t", ",", "\"", "\n", "\r", "x", "=")
    private val cell = Arb.list(Arb.element(chars), 0..6).map { it.joinToString("") }
    private val grid = Arb.list(Arb.list(cell, 1..4), 1..5)
    private val delimiters = Arb.element(TabularText.TAB, TabularText.COMMA)

    private fun parsed(text: String, d: Char) = assertIs<TabularParse.Grid>(TabularText.parse(text, d)).rows

    @Test
    fun parseFormat_should_RoundTripGrid_When_GridsContainTabsQuotesNewlines() = runTest {
        checkAll(grid, delimiters) { g, d ->
            assertEquals(g, parsed(TabularText.format(g, d), d))
        }
    }

    @Test
    fun formatParse_should_NormalizeIdempotently_When_GeneratedText() = runTest {
        val text = Arb.list(Arb.element(chars), 0..40).map { it.joinToString("") }
        checkAll(text, delimiters) { x, d ->
            val once = TabularText.format(parsed(x, d), d)
            val twice = TabularText.format(parsed(once, d), d)
            assertEquals(once, twice)
        }
    }

    @Test
    fun parse_should_NeverThrow_When_ArbitraryText() = runTest {
        val text = Arb.list(Arb.element(chars), 0..60).map { it.joinToString("") }
        checkAll(text) { x -> TabularText.parse(x) }
    }
}
