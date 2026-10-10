package dev.stapler.stelekit.merge

import arrow.core.Either
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownSplicerFixtureTest {
    private val rootBlock = MergeBlock("33333333-3333-3333-3333-333333333333", "new root", mapOf("src-id" to "g:S1"))
    private val childBlock = MergeBlock("44444444-4444-4444-4444-444444444444", "new child")

    private fun splice(text: String, vararg ins: BlockInsertion, props: Map<String, String> = emptyMap()): SpliceResult =
        (MarkdownSplicer.splice(text, SpliceRequest(ins.toList(), props)) as Either.Right).value

    private fun lines(s: String) = s.split("\n").map { it.trimEnd('\r') }

    /** Every original line appears in order; only `added` extra lines exist. */
    private fun assertInsertOnly(original: String, spliced: String, added: Int) {
        val o = lines(original)
        val n = lines(spliced)
        var j = 0
        for (l in n) if (j < o.size && l == o[j]) j++
        assertEquals(o.size, j, "original lines must survive in order")
        assertEquals(o.size + added, n.size)
        var i = 0
        for (c in spliced) if (i < original.length && c == original[i]) i++
        assertEquals(original.length, i, "every original byte must appear in order")
    }

    private fun parsedUuids(text: String) = MergeConverters.parseMarkdown(text, MergeFixtures.PATH, "Notes", false).blocks.map { it.uuid.value }

    @Test
    fun realFileKeepsEveryByteAndMatchingStyle() {
        val original = MergeFixtures.REAL_CRLF_TAB
        val out = splice(original, BlockInsertion(emptyList(), rootBlock))
        assertInsertOnly(original, out.text, added = 3) // "- new root", id::, src-id::
        assertTrue(out.text.startsWith(original))
        assertEquals("\r\n- new root\r\n  id:: 33333333-3333-3333-3333-333333333333\r\n  src-id:: g:S1", out.text.removePrefix(original))
        assertFalse(out.text.replace("\r\n", "").contains('\n'), "no bare LF introduced into a CRLF file")
        assertFalse(out.text.endsWith("\n"), "a file without trailing newline stays that way")
    }

    @Test
    fun childIsInsertedAsLastChildWithParentIndentPlusUnit() {
        val original = MergeFixtures.REAL_CRLF_TAB
        val out = splice(original, BlockInsertion(listOf(1), childBlock))
        val n = lines(out.text)
        val at = n.indexOf("\t- new child")
        assertTrue(at > 0)
        assertEquals("\t- Folded section", n.first { it.startsWith("\t- Fol") })
        // lands after the folded section's hidden grandchild, before the next root
        assertEquals("\t\t- Hidden grandchild", n[n.indexOf("\t- Folded section") + 2])
        assertEquals("\t- new child", n[n.indexOf("\t- Folded section") + 3])
        assertEquals("- Quote follows", n[at + 2])
        assertInsertOnly(original, out.text, added = 2)
    }

    @Test
    fun fencedAndQuotedDashLinesAreNotStructure() {
        val out = splice(MergeFixtures.REAL_CRLF_TAB, BlockInsertion(listOf(2), childBlock))
        val n = lines(out.text)
        assertEquals(n.indexOf("  #+END_QUOTE") + 1, n.indexOf("\t- new child"), "lands after the quote, not inside it")
    }

    @Test
    fun spaceIndentedFileStaysSpaceIndented() {
        val out = splice(MergeFixtures.SPACE_INDENTED, BlockInsertion(listOf(1), childBlock))
        assertFalse(out.text.contains('\t'))
        assertTrue(out.text.contains("- two\n  - new child\n    id:: 4444"))
    }

    @Test
    fun emptyFileWritesJustTheNewBlock() {
        val out = splice("", BlockInsertion(emptyList(), childBlock), props = mapOf("alias" to "a"))
        assertEquals("-\n  alias:: a\n- new child\n  id:: 44444444-4444-4444-4444-444444444444\n", out.text)
    }

    @Test
    fun preambleAliasLineIsNeverRewrittenAndEverythingMissingIsReportedSkipped() {
        val out = splice(
            MergeFixtures.REAL_CRLF_TAB,
            props = mapOf("alias" to "notes, jottings, extra", "type" to "project"),
        )
        // A bare `key:: v` preamble parses as an ordinary first block, so it is left untouched.
        assertEquals(MergeFixtures.REAL_CRLF_TAB, out.text)
        assertEquals(listOf("alias", "type"), out.skippedPropertyKeys)
        assertTrue(out.insertedPropertyKeys.isEmpty())
    }

    @Test
    fun propertyBlockGetsOnlyMissingKeysAndSkipsADifferingAlias() {
        val original = "-\n  alias:: a\n- x\n"
        val out = splice(original, props = mapOf("alias" to "a, b", "type" to "t"))
        assertEquals("-\n  alias:: a\n  type:: t\n- x\n", out.text)
        assertEquals(listOf("alias"), out.skippedPropertyKeys)
        assertEquals(listOf("type"), out.insertedPropertyKeys)
    }

    @Test
    fun equalPropertyIsNeitherInsertedNorSkipped() {
        val original = "-\n  alias:: a\n- x\n"
        val out = splice(original, props = mapOf("alias" to "a"))
        assertEquals(original, out.text)
        assertTrue(out.skippedPropertyKeys.isEmpty())
    }

    @Test
    fun fileWithoutPagePropertiesGetsAPropertyBlockWithoutShiftingPositionalUuids() {
        val before = parsedUuids(MergeFixtures.UNLABELED_FLAT)
        val out = splice(MergeFixtures.UNLABELED_FLAT, props = mapOf("type" to "x"))
        assertEquals("-\n  type:: x\n" + MergeFixtures.UNLABELED_FLAT, out.text)
        assertEquals(before, parsedUuids(out.text))
    }

    @Test
    fun unknownParentIsATypedError() {
        val r = MarkdownSplicer.splice(MergeFixtures.UNLABELED_FLAT, SpliceRequest(listOf(BlockInsertion(listOf(7), childBlock))))
        assertEquals(SpliceError.ParentNotFound(listOf(7)), (r as Either.Left).value)
    }

    // --- R3: positional uuids of unlabeled blocks must not shift --------------------------------

    private fun assertPositionalUuidsStable(original: String) {
        val before = parsedUuids(original)
        val unlabeledChild = MergeBlock(null, "appended")
        for (req in listOf(
            BlockInsertion(emptyList(), unlabeledChild),
            BlockInsertion(emptyList(), rootBlock),
            BlockInsertion(listOf(0), childBlock),
        )) {
            val out = splice(original, req)
            val after = parsedUuids(out.text)
            assertEquals(before.size + 1, after.size)
            assertEquals(before, after.filterIndexed { _, u -> u in before.toSet() }.take(before.size), "pre-existing uuids unchanged: ${req.parentPath}")
            assertIs<Either.Right<*>>(RoundTripGuard.splice(original, SpliceRequest(listOf(req)), MergeFixtures.PATH, false))
        }
    }

    @Test fun unlabeledFlat() = assertPositionalUuidsStable(MergeFixtures.UNLABELED_FLAT)

    @Test fun unlabeledNested() = assertPositionalUuidsStable(MergeFixtures.UNLABELED_NESTED)

    @Test fun mixedLabeled() = assertPositionalUuidsStable(MergeFixtures.MIXED_LABELED)

    @Test
    fun insertedLabeledBlockKeepsItsUuidOnReparse() {
        val out = splice(MergeFixtures.UNLABELED_NESTED, BlockInsertion(listOf(1), childBlock))
        assertTrue("44444444-4444-4444-4444-444444444444" in parsedUuids(out.text))
    }

    // --- property: random outline files ------------------------------------------------------

    private data class Spec(val depths: List<Int>, val labeled: List<Boolean>, val tabs: Boolean, val crlf: Boolean, val finalNewline: Boolean)

    private fun build(spec: Spec): Triple<String, List<List<Int>>, Int> {
        val eol = if (spec.crlf) "\r\n" else "\n"
        val out = mutableListOf<String>()
        val paths = mutableListOf<List<Int>>()
        val counts = mutableListOf<Int>() // children seen per depth level
        var prev = -1
        spec.depths.forEachIndexed { i, raw ->
            val d = minOf(raw, prev + 1)
            while (counts.size > d + 1) counts.removeAt(counts.lastIndex)
            while (counts.size < d + 1) counts += -1
            counts[d] = counts[d] + 1
            val path = counts.take(d + 1).toList()
            paths += path
            val pad = if (spec.tabs) "\t".repeat(d) else "  ".repeat(d)
            out += "$pad- block $i"
            if (spec.labeled[i % spec.labeled.size]) out += "$pad  id:: 00000000-0000-0000-0000-${i.toString().padStart(12, '0')}"
            prev = d
        }
        val text = out.joinToString(eol) + if (spec.finalNewline) eol else ""
        return Triple(text, paths, out.size)
    }

    @Test
    fun spliceIsInsertOnlyAndGuardAcceptsAcrossRandomOutlines() = runTest {
        val arb = Arb.list(Arb.int(0..3), 1..12)
        checkAll(PropTestConfig(iterations = 300), arb, Arb.list(Arb.boolean(), 1..4), Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.int(0..11)) { depths, lab, tabs, crlf, nl, pick ->
            val (text, paths, _) = build(Spec(depths, lab, tabs, crlf, nl))
            val before = parsedUuids(text)
            val target = paths[pick % paths.size]
            val req = SpliceRequest(listOf(BlockInsertion(emptyList(), MergeBlock(null, "new root")), BlockInsertion(target, MergeBlock(null, "new child"))))
            val guarded = RoundTripGuard.splice(text, req, MergeFixtures.PATH, false)
            assertEquals(null, guarded.leftOrNull()?.message, text.replace("\r", "\\r").replace("\n", "\\n"))
            val out = assertIs<Either.Right<SpliceResult>>(guarded).value
            val after = parsedUuids(out.text)
            assertEquals(before.size + 2, after.size)
            assertEquals(before.toSet(), before.toSet().intersect(after.toSet()), "no pre-existing uuid shifted")
            if (crlf && text.contains('\n')) assertFalse(Regex("(?<!\r)\n").containsMatchIn(out.text), "no bare LF in a CRLF file")
            assertNull(RoundTripGuard.probe(text, MergeFixtures.PATH, false).leftOrNull())
        }
    }

}
