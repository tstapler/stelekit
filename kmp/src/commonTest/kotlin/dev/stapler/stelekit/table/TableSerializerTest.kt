package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TableSerializerTest {
    private fun parsed(s: String): TableDoc = assertIs<TableParseResult.Parsed>(TableParser.parse(s)).doc

    @Test
    fun serialize_should_EqualInput_When_NoEditPaddedCompactRaggedTable() {
        val s = "|  a  | b |\n|:-|-:|\n| 1 | 2 | 3 |\n"
        assertEquals(s, TableSerializer.serialize(parsed(s)))
    }

    @Test
    fun setCell_should_RewriteOnlyLine3_When_SetCellAddress2_1To9() {
        val s = "|  a  | b |\n|:-|-:|\n| 1 | 2 | 3 |\n"
        val out = TableSerializer.serialize(parsed(s).setCell(CellAddress(2, 1), CellText("9")))
        assertEquals("|  a  | b |\n|:-|-:|\n| 9 | 2 | 3 |\n", out)
    }

    @Test
    fun serialize_should_KeepRawCellsByteForByte_When_OneCellOfDirtyRowEdited() {
        val s = "| h | i | j | k |\n|---|---|---|---|\n| x<br/>y | p<BR>q | a\\\\|b | 1 |"
        val doc = parsed(s)
        assertEquals(4, doc.row(2).cells.size, "literal \\\\| is a backslash plus an escaped pipe, one cell")
        val out = TableSerializer.serialize(doc.setCell(CellAddress(2, 4), CellText("2")))
        assertEquals("| h | i | j | k |\n|---|---|---|---|\n| x<br/>y | p<BR>q | a\\\\|b | 2 |", out)
    }

    @Test
    fun serialize_should_EscapePipeAndNewlineAndReparseSame_When_CellTextHasThem() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |")
        val edited = doc.setCell(CellAddress(2, 1), CellText("a|b")).setCell(CellAddress(2, 2), CellText("l1\nl2"))
        val out = TableSerializer.serialize(edited)
        assertTrue(out.contains("a\\|b") && out.contains("l1<br>l2"), out)
        assertEquals(edited, parsed(out))
    }

    @Test
    fun serialize_should_GuardTrailingBackslash_When_CellEndsWithBackslash() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |").setCell(CellAddress(2, 2), CellText("x\\"))
        assertEquals(doc, parsed(TableSerializer.serialize(doc)))
    }

    @Test
    fun serialize_should_NeverEmitComputedValue_When_FormulaCellPresent() {
        val s = "| a | b |\n|---|---|\n| 3 | =B2*2 |\n"
        val doc = parsed(s)
        assertEquals(s, TableSerializer.serialize(doc))
        val out = TableSerializer.serialize(doc.setCell(CellAddress(2, 1), CellText("4")))
        assertTrue(out.contains("=B2*2"))
        assertFalse(out.contains("| 6 |"))
        assertEquals(3, out.split("\n").filter { it.isNotEmpty() }.size, "no property line added")
    }

    @Test
    fun serialize_should_RegenerateOnlyChangedDelimiterCells_When_AlignmentChanged() {
        val doc = parsed("| a | b |\n|:------|---|\n| 1 | 2 |")
        val out = TableSerializer.serialize(doc.copy(delimiter = doc.delimiter.withAligns(listOf(ColumnAlign.LEFT, ColumnAlign.RIGHT))))
        assertEquals("| a | b |\n| :------ | --: |\n| 1 | 2 |", out)
    }

    @Test
    fun serialize_should_RestoreCarriageReturns_When_RowEditedInCrlfDoc() {
        val doc = parsed("| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n")
        val out = TableSerializer.serialize(doc.setCell(CellAddress(2, 1), CellText("9")))
        assertEquals("| a | b |\r\n|---|---|\r\n| 9 | 2 |\r\n", out)
    }

    @Test
    fun serialize_should_KeepEdgePipes_When_EmptyEdgeCellInCompactNoOuterPipeTable() {
        val doc = parsed("a|b\n-|-\n1|2").setCell(CellAddress(2, 1), CellText(""))
        assertEquals(doc, parsed(TableSerializer.serialize(doc)))
    }

    @Test
    fun serialize_should_ReescapeTrailingBackslash_When_CompactRowRerenders() {
        val doc = parsed("|h1|h2|\n|--|--|\n|a|b\\ |")
        assertEquals("b\\", doc.cell(CellAddress(2, 2))!!.value)
        val edited = doc.setCell(CellAddress(2, 1), CellText("z"))
        val out = TableSerializer.serialize(edited)
        assertEquals(edited, parsed(out), out)
        assertEquals(2, parsed(out).row(2).cells.size, out)
    }

    @Test
    fun roundTrip_should_HoldForBackslashCells_When_AnyStyleAndAnyEdit() {
        val pool = listOf("end\\", "a\\\\", "\\", "x", "p|q", "")
        val rnd = kotlin.random.Random(6)
        repeat(1500) {
            val compact = rnd.nextBoolean()
            val pad = if (compact) "" else " "
            fun line(c: List<String>) = "|" + c.joinToString("|") { pad + TableCellCodec.escape(it) + pad } + "|"
            val cols = rnd.nextInt(1, 4)
            val text = (listOf(line(List(cols) { "h$it" }), "|" + List(cols) { "--" }.joinToString("|") + "|") +
                List(rnd.nextInt(1, 4)) { line(List(cols) { pool[rnd.nextInt(pool.size)] }) }).joinToString("\n")
            val doc = parsed(text)
            val target = CellAddress(rnd.nextInt(2, doc.rowCount + 1), rnd.nextInt(1, cols + 1))
            val edited = doc.setCell(target, CellText(pool[rnd.nextInt(pool.size)] + if (rnd.nextBoolean()) "" else "v"))
            assertEquals(edited, parsed(TableSerializer.serialize(edited)), "text=$text target=$target")
        }
    }
}
