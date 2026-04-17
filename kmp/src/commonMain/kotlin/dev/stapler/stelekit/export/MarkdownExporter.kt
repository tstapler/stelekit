package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.*

class MarkdownExporter : PageExporter {
    override val formatId = "markdown"
    override val displayName = "Markdown"

    override fun export(page: Page, blocks: List<Block>, resolvedRefs: Map<String, String>): String {
        val sortedBlocks = BlockSorter.sort(blocks)
        return buildString {
            // YAML frontmatter: page properties excluding 'id'
            val frontmatterProps = page.properties.filterKeys { it != "id" }
            if (frontmatterProps.isNotEmpty()) {
                append("---\n")
                append("title: ${page.name}\n")
                frontmatterProps.forEach { (key, value) ->
                    append("$key: ${yamlEscape(value)}\n")
                }
                append("---\n\n")
            }

            // H1 heading
            append("# ${page.name}\n\n")

            // Block tree
            writeBlocks(sortedBlocks, this, resolvedRefs)
        }
    }

    private fun writeBlocks(blocks: List<Block>, sb: StringBuilder, resolvedRefs: Map<String, String>) {
        blocks.forEach { block ->
            // Skip property-only blocks
            if (block.content.isBlank() && block.properties.isNotEmpty()) return@forEach

            val inlineContent = renderInlineMarkdown(block.content, resolvedRefs)

            if (block.level == 0) {
                sb.append(inlineContent)
                sb.append("\n\n")
            } else {
                val indent = "  ".repeat(block.level - 1)
                sb.append("$indent- $inlineContent\n")
            }
        }
    }

    private fun renderInlineMarkdown(content: String, resolvedRefs: Map<String, String>): String {
        val nodes = InlineParser(content).parse()
        return buildString {
            nodes.forEach { node -> appendNode(node, resolvedRefs) }
        }
    }

    private fun StringBuilder.appendNode(node: InlineNode, resolvedRefs: Map<String, String>) {
        when (node) {
            is TextNode -> append(escapeMarkdown(node.content))
            is WikiLinkNode -> {
                if (node.alias != null) {
                    append("[${node.alias}](${node.target})")
                } else {
                    append("[[${node.target}]]")
                }
            }
            is BlockRefNode -> append(resolvedRefs[node.blockUuid] ?: "[block ref]")
            is HighlightNode -> {
                append("**")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("**")
            }
            is TaskMarkerNode -> {
                when (node.marker) {
                    "TODO" -> append("[ ] ")
                    "DONE" -> append("[x] ")
                    else -> append("**${node.marker}** ")
                }
            }
            is LatexInlineNode -> append("\$${node.formula}\$")
            is TagNode -> append("#${node.tag}")
            is BoldNode -> {
                append("**")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("**")
            }
            is ItalicNode -> {
                append("*")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("*")
            }
            is StrikeNode -> {
                append("~~")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("~~")
            }
            is CodeNode -> append("`${node.content}`")
            is UrlLinkNode -> {
                val isAutoLink = node.text.size == 1 &&
                    node.text[0] is TextNode &&
                    (node.text[0] as TextNode).content == node.url
                if (isAutoLink) {
                    append("<${node.url}>")
                } else {
                    append("[")
                    node.text.forEach { appendNode(it, resolvedRefs) }
                    append("](${node.url})")
                }
            }
            is MdLinkNode -> {
                append("[${node.label}](${node.url})")
            }
            is ImageNode -> append("![${node.alt}](${node.url})")
            is MacroNode -> {
                append("{{${node.name}")
                if (node.arguments.isNotEmpty()) {
                    append(" ")
                    append(node.arguments.joinToString(", "))
                }
                append("}}")
            }
            is HardBreakNode -> append("\n")
            is SoftBreakNode -> append("\n")
            is SubscriptNode -> {
                append("~{")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("}")
            }
            is SuperscriptNode -> {
                append("^{")
                node.children.forEach { appendNode(it, resolvedRefs) }
                append("}")
            }
            is PriorityNode -> append("[#${node.priority}]")
        }
    }

    private fun escapeMarkdown(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("`", "\\`")
    }

    private fun yamlEscape(value: String): String {
        val needsQuoting = value.any { it in listOf(':', '[', '{', '#', '&') }
        return if (needsQuoting) {
            "\"${value.replace("\"", "\\\"")}\""
        } else {
            value
        }
    }
}
