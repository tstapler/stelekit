package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TableRoundTripPropertyTest {
    private val iterations = 500

    private class Generated(val source: String)

    private fun gen(seed: Long): Generated {
        val rnd = Random(seed)
        val rawCells = listOf(
            "", "a", "x y", "a\\|b", "a\\\\|b", "l1<br>l2", "<br/>", "<BR>", "<br />", "=B2*2", "1.5", "z",
        )
        val lead = rnd.nextBoolean()
        val trail = rnd.nextBoolean()
        val pad = if (rnd.nextBoolean()) " " else ""
        val cols = if (!lead && !trail) rnd.nextInt(2, 5) else rnd.nextInt(1, 5)
        val crlf = rnd.nextInt(4) == 0
        val eol = if (crlf) "\r\n" else "\n"
        fun line(cells: List<String>): String {
            val body = cells.joinToString("|") { if (it.isEmpty()) pad else pad + it + pad }
            return (if (lead) "|" else "") + body + (if (trail) "|" else "")
        }
        fun randomRow(width: Int) = List(width) { rawCells[rnd.nextInt(rawCells.size)] }
        val aligns = listOf("-", ":-", "-:", ":-:", "---", ":---", "---:", ":---:")
        val delim = line(List(cols) { aligns[rnd.nextInt(aligns.size)] })
        val lines = ArrayList<String>()
        if (rnd.nextBoolean()) lines.add("intro")
        if (rnd.nextBoolean()) lines.add("id:: x")
        val prefixCount = lines.size
        lines.add(line(randomRow(cols)))
        lines.add(delim)
        repeat(rnd.nextInt(0, 5)) {
            val width = if (rnd.nextInt(3) == 0) maxOf(if (!lead && !trail) 2 else 1, cols + rnd.nextInt(-1, 3)) else cols
            lines.add(line(randomRow(width)))
        }
        val tableEnd = lines.size
        if (rnd.nextBoolean()) lines.add("trailing text")
        val joined = lines.mapIndexed { i, l -> if (crlf && i >= prefixCount && i < tableEnd) l + "\r" else l }
            .joinToString("\n") + if (rnd.nextBoolean()) eol else ""
        return Generated(joined)
    }

    private val texts = listOf("", "p", "a|b", "p\\|q", "end\\", "l1\nl2", "=SUM(A1:A2)", "9", "sp ace")

    private fun parsed(s: String): TableDoc = assertIs<TableParseResult.Parsed>(TableParser.parse(s), s).doc

    @Test
    fun serializeParse_should_BeIdentity_When_GeneratedTables() = runTest {
        checkAll(iterations, Arb.long()) { seed ->
            val s = gen(seed).source
            assertEquals(s, TableSerializer.serialize(parsed(s)), "seed=$seed")
        }
    }

    @Test
    fun parseSerializeEdit_should_RoundTrip_When_AnySingleEdit() = runTest {
        checkAll(iterations, Arb.long()) { seed ->
            val rnd = Random(seed xor 0x5DEECE66DL)
            val doc = parsed(gen(seed).source)
            val addr = CellAddress(rnd.nextInt(1, doc.rowCount + 1), rnd.nextInt(1, doc.columnCount + 3))
            val edited = doc.setCell(addr, CellText(texts[rnd.nextInt(texts.size)]))
            assertEquals(edited, parsed(TableSerializer.serialize(edited)), "seed=$seed addr=$addr")
        }
    }

    @Test
    fun untouchedCells_should_KeepSourceTextAndLineIndex_When_AnySingleEdit() = runTest {
        checkAll(iterations, Arb.long()) { seed ->
            val rnd = Random(seed xor 0x2545F491L)
            val src = gen(seed).source
            val doc = parsed(src)
            val srcLines = src.split("\n")
            for (r in 1..doc.rowCount) {
                assertEquals(doc.row(r).source, srcLines[doc.lineIndexOf(r) - 1].removeSuffix("\r"), "seed=$seed row=$r")
            }
            val addr = CellAddress(rnd.nextInt(1, doc.rowCount + 1), rnd.nextInt(1, doc.columnCount + 1))
            val edited = doc.setCell(addr, CellText("zz${rnd.nextInt(100)}"))
            val out = TableSerializer.serialize(edited)
            val again = parsed(out)
            val outLines = out.split("\n")
            assertEquals(doc.prefix, again.prefix, "seed=$seed")
            assertEquals(doc.suffix, again.suffix, "seed=$seed")
            for (r in 1..doc.rowCount) {
                assertEquals(doc.lineIndexOf(r), again.lineIndexOf(r))
                if (r != addr.row) {
                    assertEquals(doc.row(r).source, again.row(r).source, "untouched row seed=$seed row=$r")
                } else {
                    doc.row(r).rawCells.forEachIndexed { j, raw ->
                        if (j != addr.col - 1) assertEquals(raw, again.row(r).rawCells[j], "seed=$seed cell=$j")
                    }
                }
                assertEquals(again.row(r).source, outLines[again.lineIndexOf(r) - 1].removeSuffix("\r"))
            }
        }
    }
}
