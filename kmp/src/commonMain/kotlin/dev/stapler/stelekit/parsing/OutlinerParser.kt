package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*

enum class ParseMode {
    FULL,           // Parse structure and inline content
    METADATA_ONLY   // Parse structure only, keep content as raw string
}

/**
 * Main facade for the KMP Outliner Parser.
 * Orchestrates Block Parsing and Inline Parsing.
 */
class OutlinerParser {

    fun parse(source: CharSequence, mode: ParseMode = ParseMode.FULL): DocumentNode {
        // 1. Parse Structure (Blocks, Indentation, Properties)
        val blockParser = BlockParser(source)
        val document = blockParser.parse()

        // If metadata only, return early with raw content
        if (mode == ParseMode.METADATA_ONLY) {
            return document
        }

        // 2. Parse Inline Content
        // We need to traverse the tree and parse the 'content' of each block
        // currently stored as a single TextNode.
        val processedChildren = document.children.map { processBlock(it) }

        return DocumentNode(processedChildren)
    }

    private fun processBlock(block: BlockNode): BlockNode {
        // 1. Parse Inline Content
        // Extract raw text from the placeholder TextNode
        val rawContent = (block.content.firstOrNull() as? TextNode)?.content ?: ""
        
        val inlineParser = InlineParser(rawContent)
        val parsedContent = inlineParser.parse()

        // 2. Recursively process children
        val processedChildren = block.children.map { processBlock(it) }

        return when (block) {
            is BulletBlockNode -> block.copy(content = parsedContent, children = processedChildren)
            is ParagraphBlockNode -> block.copy(content = parsedContent, children = processedChildren)
            is HeadingBlockNode -> block.copy(content = parsedContent, children = processedChildren)
            is OrderedListItemBlockNode -> block.copy(content = parsedContent, children = processedChildren)
            is BlockquoteBlockNode -> block.copy(children = processedChildren)
            is CodeFenceBlockNode -> block  // raw fenced code — no inline parsing
            is ThematicBreakBlockNode -> block
            is TableBlockNode -> block
            is RawHtmlBlockNode -> block
        }
    }
}
