package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The nine pages in project_plans/table-sheet-editor/implementation/logseq-compat/ embedded as string
 * constants (commonTest cannot read repo paths on every target). Each constant is the table block's
 * content as the parser stores it: the indented `- ` child bullet text with its indent removed, no
 * parent "Fixture NN" line. Fixture 09 is CRLF.
 */
class LogseqCompatFixtureTest {
    private val fixtures: List<Pair<String, String>> = listOf(
        "01-padded-cells.md" to "| Item   | Qty | Price |\n|--------|-----|-------|\n| Apple  | 3   | 1.50  |\n| Banana | 12  | 0.25  |\n| Cherry | 100 | 10.00 |",
        "02-compact-delimiter.md" to "|Item|Qty|Price|\n|-|-|-|\n|Apple|3|1.50|\n|Banana|12|0.25|",
        "03-alignment.md" to "| Left | Center | Right | Default |\n|:-----|:------:|------:|---------|\n| a    | b      | c     | d       |\n| 1    | 2      | 3     | 4       |",
        "04-br-variants.md" to "| Variant | Cell |\n|---|---|\n| lower | line one<br>line two |\n| self-closing | line one<br/>line two |\n| upper | line one<BR>line two |\n| spaced | line one<br />line two |",
        "05-formula-sum.md" to "| Item | Amount |\n|---|---|\n| A | 10 |\n| B | 20 |\n| C | 30 |\n| D | 40 |\n| Total | =SUM(B2:B5) |",
        "06-ragged-rows.md" to "| A | B | C |\n|---|---|---|\n| 1 | 2 |\n| 3 | 4 | 5 | 6 |\n| 7 |",
        "07-formula-star.md" to "| Item | Qty | Price | Tax | Total |\n|---|---|---|---|---|\n| Widget | 2 | 5 | 3 | =B2*C2*D2 |\n| Gadget | 1 | 7 | 2 | =B3*C3*D3 |",
        "08-escaped-pipes-empty-cells.md" to "| Expr | Note |\n|---|---|\n| a \\| b | has an escaped pipe |\n| | empty first cell |\n| literal \\\\| | backslash then pipe |\n| last | |",
        "09-crlf.md" to "| Item | Qty |\r\n|---|---|\r\n| Apple | 3 |\r\n| Banana | 12 |",
    )

    private fun parsed(s: String) = assertIs<TableParseResult.Parsed>(TableParser.parse(s), s).doc

    @Test
    fun fixtures_should_RoundTripUnchangedAndDifferOnlyInEditedRow_When_NineFixturePages() {
        assertEquals(9, fixtures.size)
        for ((name, content) in fixtures) {
            val doc = parsed(content)
            assertEquals(content, TableSerializer.serialize(doc), "$name: untouched round trip")

            val edited = TableSerializer.serialize(doc.setCell(CellAddress(2, 1), CellText("ZZ")))
            val before = content.split("\n")
            val after = edited.split("\n")
            assertEquals(before.size, after.size, name)
            val changed = before.indices.filter { before[it] != after[it] }
            assertEquals(listOf(doc.lineIndexOf(2) - 1), changed, "$name: only the edited row differs")
            assertTrue(after[changed.single()].contains("ZZ"), name)
        }
    }

    @Test
    fun fixtures_should_ParseExpectedShape_When_SpotChecked() {
        val byName = fixtures.toMap()
        val compact = parsed(byName.getValue("02-compact-delimiter.md"))
        assertEquals(3, compact.columnCount)
        val align = parsed(byName.getValue("03-alignment.md"))
        assertEquals(listOf(ColumnAlign.LEFT, ColumnAlign.CENTER, ColumnAlign.RIGHT, ColumnAlign.NONE), align.delimiter.aligns)
        val br = parsed(byName.getValue("04-br-variants.md"))
        assertEquals(4, br.body.size)
        assertTrue(br.body.all { it.cells[1].value.contains('\n') })
        val ragged = parsed(byName.getValue("06-ragged-rows.md"))
        assertEquals(listOf(2, 4, 1), ragged.body.map { it.cells.size })
        val esc = parsed(byName.getValue("08-escaped-pipes-empty-cells.md"))
        assertEquals("a | b", esc.row(2).cells[0].value)
        assertEquals("", esc.row(3).cells[0].value)
        val crlf = parsed(byName.getValue("09-crlf.md"))
        assertTrue(crlf.header.cr && crlf.body.first().cr && !crlf.body.last().cr)
    }
}
