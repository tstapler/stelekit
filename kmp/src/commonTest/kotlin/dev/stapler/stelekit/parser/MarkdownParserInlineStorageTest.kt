// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.parser

import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.ParsedBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MarkdownParser] stores each block's content as source text (the UI re-parses it on render), so
 * every inline form must survive the AST round trip unchanged or a save would rewrite the user's file.
 */
class MarkdownParserInlineStorageTest {
    private val parser = MarkdownParser()

    private fun onlyBlock(markdown: String): ParsedBlock = parser.parsePage(markdown).blocks.single()

    private fun assertInlineRoundTrips(inline: String) {
        assertEquals(inline, onlyBlock("- $inline").content, "inline [$inline] changed on the way through the parser")
    }

    @Test
    fun emphasisAndCodeRoundTrip() {
        listOf("**bold**", "*italic*", "~~gone~~", "`code`", "==marked==", "plain **b** and `c` mixed").forEach(::assertInlineRoundTrips)
    }

    @Test
    fun linksAndImagesRoundTrip() {
        listOf(
            "[label](https://example.com)",
            "[label](https://example.com \"a title\")",
            "![alt text](https://example.com/i.png)",
            "<https://example.com/auto>",
        ).forEach(::assertInlineRoundTrips)
    }

    @Test
    fun logseqReferencesRoundTrip() {
        listOf("see [[Some Page]]", "tagged #topic and #[[multi word tag]]", "ref ((123e4567-e89b-12d3-a456-426614174000))")
            .forEach(::assertInlineRoundTrips)
    }

    @Test
    fun mathAndScriptsRoundTrip() {
        listOf("inline \$x^2\$ math", "H~{2}O", "x^{2}").forEach(::assertInlineRoundTrips)
    }

    @Test
    fun macrosTaskMarkersAndPrioritiesRoundTrip() {
        listOf("{{embed [[Page]]}}", "TODO write tests", "DONE shipped", "[#A] urgent").forEach(::assertInlineRoundTrips)
    }

    @Test
    fun referencesAreExtractedFromContent() {
        val block = onlyBlock("- links [[Alpha]] and #beta and ((123e4567-e89b-12d3-a456-426614174000))")

        assertTrue("Alpha" in block.references, block.references.toString())
        assertTrue("beta" in block.references, block.references.toString())
        assertTrue("123e4567-e89b-12d3-a456-426614174000" in block.references, block.references.toString())
    }

    @Test
    fun aBlockWhoseWholeContentIsAQueryMacroBecomesAQueryBlock() {
        val block = onlyBlock("- {{query (and [[A]] [[B]])}}")

        assertEquals(BlockType.Query("(and [[A]] [[B]])"), block.blockType)
    }

    @Test
    fun aQueryMacroMixedWithTextStaysAnOrdinaryBullet() {
        assertEquals(BlockType.Bullet, onlyBlock("- see {{query (todo)}} here").blockType)
    }

    @Test
    fun otherMacrosAreNotQueries() {
        assertEquals(BlockType.Bullet, onlyBlock("- {{embed [[A]]}}").blockType)
    }

    @Test
    fun nestedBulletsKeepLevelsAndParentChildStructure() {
        val blocks = parser.parsePage("- parent\n  - child\n    - grandchild\n- sibling").blocks

        assertEquals(listOf("parent", "sibling"), blocks.map { it.content })
        assertEquals("child", blocks[0].children.single().content)
        assertEquals("grandchild", blocks[0].children.single().children.single().content)
        assertEquals(listOf(0, 1, 2), listOf(blocks[0], blocks[0].children[0], blocks[0].children[0].children[0]).map { it.level })
    }

    @Test
    fun headingBlockCarriesItsLevel() {
        assertEquals(BlockType.Heading(level = 2), onlyBlock("- ## Section").blockType)
    }
}
