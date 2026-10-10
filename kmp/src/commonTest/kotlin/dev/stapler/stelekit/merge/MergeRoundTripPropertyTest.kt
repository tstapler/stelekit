package dev.stapler.stelekit.merge

import arrow.core.getOrElse
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serializer round trip: render with [MergeRenderer] -> parse with the production parser via
 * [MergeConverters] must give back the same [MergePage]. Generated content is the representable
 * subset (single-line, no leading `- `, no `::`); multi-line/fenced content is covered by the fixture tests.
 */
class MergeRoundTripPropertyTest {
    private val iterations = 500
    private val path = MergeFixtures.PATH

    private fun reparse(page: MergePage) =
        MergeConverters.parseMarkdown(MergeRenderer.renderNewPage(page), path, page.name, page.isJournal, page.journalDate).mergePage

    private fun paths(bs: List<MergeBlock>, prefix: List<Int> = emptyList()): List<List<Int>> =
        bs.flatMapIndexed { i, b -> listOf(prefix + i) + paths(b.children, prefix + i) }

    @Test
    fun renderThenParseEqualsOriginal() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            for (page in listOf(c.target, c.source, c.editedTarget)) assertEquals(page, reparse(page))
        }
    }

    @Test
    fun parseOfRenderedMergeResultIsTheMergeResult() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            val out = when (val o = mergePage(c.target, c.source, c.policy)) {
                is MergeOutcome.New -> o.page
                is MergeOutcome.Merged -> o.page
                MergeOutcome.Unchanged -> return@checkAll
            }
            assertEquals(out, reparse(out))
        }
    }

    @Test
    fun spliceInsertKeepsPositionalUuidsOfExistingBlocks() = runTest {
        checkAll(iterations, MergeArbs.cases(0.6)) { c ->
            val text = MergeRenderer.renderNewPage(c.target)
            val before = MergeConverters.parseMarkdown(text, path, "P", false).blocks.map { it.uuid }
            val incoming = MergeBlock("ins-1", "inserted", children = listOf(MergeBlock(null, "child")))
            // Roots append at [], and every existing block gets a last-child insert.
            for (parent in listOf(emptyList<Int>()) + paths(c.target.blocks)) {
                val spliced = MarkdownSplicer.splice(text, SpliceRequest(listOf(BlockInsertion(parent, incoming))))
                    .getOrElse { error("splice failed at $parent: $it") }
                val after = MergeConverters.parseMarkdown(spliced.text, path, "P", false).blocks.map { it.uuid }.toSet()
                assertTrue(after.containsAll(before), "positional uuids shifted by insert under $parent")
            }
        }
    }
}
