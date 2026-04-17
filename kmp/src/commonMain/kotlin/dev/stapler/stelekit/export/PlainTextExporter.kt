package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.*

class PlainTextExporter : PageExporter {
    override val formatId = "plain-text"
    override val displayName = "Plain Text"

    override fun export(page: Page, blocks: List<Block>, resolvedRefs: Map<String, String>): String {
        val sortedBlocks = BlockSorter.sort(blocks)
        return buildString {
            // Page title with underline
            append(page.name)
            append("\n")
            append("=".repeat(page.name.length))
            append("\n\n")

            // Block tree
            sortedBlocks.forEach { block ->
                // Skip property-only blocks
                if (block.content.isBlank() && block.properties.isNotEmpty()) return@forEach

                val indent = "  ".repeat(block.level)
                append(indent)
                append(renderPlainText(block.content, resolvedRefs))
                append("\n")

                // Separate top-level blocks with an additional blank line
                if (block.level == 0) {
                    append("\n")
                }
            }
        }
    }

    private fun renderPlainText(content: String, resolvedRefs: Map<String, String>): String {
        val nodes = InlineParser(content).parse()
        return buildString {
            nodes.forEach { node -> appendNode(node, resolvedRefs) }
        }
    }

    private fun StringBuilder.appendNode(node: InlineNode, resolvedRefs: Map<String, String>) {
        when (node) {
            is TextNode -> append(node.content)
            is BoldNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is ItalicNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is StrikeNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is HighlightNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is CodeNode -> append(node.content)
            is WikiLinkNode -> append(node.alias ?: node.target)
            is BlockRefNode -> append(resolvedRefs[node.blockUuid] ?: "[block ref]")
            is TagNode -> append(node.tag)
            is UrlLinkNode -> node.text.forEach { appendNode(it, resolvedRefs) }
            is MdLinkNode -> append(node.label)
            is ImageNode -> append("[Image: ${node.alt}]")
            is MacroNode -> {
                append("[${node.name}")
                if (node.arguments.isNotEmpty()) {
                    append(": ")
                    append(node.arguments.joinToString())
                }
                append("]")
            }
            is TaskMarkerNode -> append("${node.marker} ")
            is LatexInlineNode -> append(node.formula)
            is HardBreakNode -> append("\n")
            is SoftBreakNode -> append("\n")
            is SubscriptNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is SuperscriptNode -> node.children.forEach { appendNode(it, resolvedRefs) }
            is PriorityNode -> append("[#${node.priority}]")
        }
    }
}
