package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.*

class HtmlExporter : PageExporter {
    override val formatId = "html"
    override val displayName = "HTML"

    override fun export(
        page: Page,
        blocks: List<Block>,
        resolvedRefs: Map<String, String>
    ): String = buildString {
        append("<article>")
        append("<h1>").append(HtmlUtils.escape(page.name)).append("</h1>")

        // Page properties (excluding "id")
        val filteredProps = page.properties.filterKeys { it != "id" }
        if (filteredProps.isNotEmpty()) {
            append("<dl>")
            for ((key, value) in filteredProps) {
                append("<dt>").append(HtmlUtils.escape(key)).append("</dt>")
                append("<dd>").append(HtmlUtils.escape(value)).append("</dd>")
            }
            append("</dl>")
        }

        val sorted = BlockSorter.sort(blocks)
        var previousLevel = -1
        // Track depth of open <ul> tags
        val openUlCount = IntArray(1) { 0 }

        for (block in sorted) {
            // Skip property-only blocks (blank content + non-empty properties)
            if (block.content.isBlank() && block.properties.isNotEmpty()) continue

            val currentLevel = block.level
            val isCodeFence = block.content.contains('\n')

            if (currentLevel == 0) {
                // Close any open lists before emitting top-level block
                repeat(openUlCount[0]) {
                    append("</li></ul>")
                }
                openUlCount[0] = 0
                previousLevel = 0

                if (isCodeFence) {
                    append("<pre><code>").append(HtmlUtils.escape(block.content)).append("</code></pre>")
                } else {
                    val taskClass = resolveTaskClass(block)
                    if (taskClass != null) {
                        append("<p class=\"$taskClass\">")
                    } else {
                        append("<p>")
                    }
                    append(renderInlineHtml(block.content, resolvedRefs))
                    append("</p>")
                }
            } else {
                // level > 0: list item
                when {
                    previousLevel < 0 || previousLevel == 0 -> {
                        // Transitioning from top-level (or start) into a list
                        repeat(currentLevel) {
                            append("<ul>")
                            openUlCount[0]++
                        }
                        emitListItem(this, block, isCodeFence, resolvedRefs)
                    }
                    currentLevel > previousLevel -> {
                        // Going deeper: close previous <li> then open new <ul> levels
                        val levelsDown = currentLevel - previousLevel
                        repeat(levelsDown) {
                            append("<ul>")
                            openUlCount[0]++
                        }
                        emitListItem(this, block, isCodeFence, resolvedRefs)
                    }
                    currentLevel < previousLevel -> {
                        // Going shallower: close levels
                        val levelsDiff = previousLevel - currentLevel
                        repeat(levelsDiff) {
                            append("</li></ul>")
                            openUlCount[0]--
                        }
                        append("</li>")
                        emitListItem(this, block, isCodeFence, resolvedRefs)
                    }
                    else -> {
                        // Same level: close previous item, open new one
                        append("</li>")
                        emitListItem(this, block, isCodeFence, resolvedRefs)
                    }
                }
                previousLevel = currentLevel
            }
        }

        // Close any remaining open lists
        repeat(openUlCount[0]) {
            append("</li></ul>")
        }

        append("</article>")
    }

    private fun emitListItem(
        sb: StringBuilder,
        block: Block,
        isCodeFence: Boolean,
        resolvedRefs: Map<String, String>
    ) {
        val taskClass = resolveTaskClass(block)
        if (taskClass != null) {
            sb.append("<li class=\"$taskClass\">")
        } else {
            sb.append("<li>")
        }
        if (isCodeFence) {
            sb.append("<pre><code>").append(HtmlUtils.escape(block.content)).append("</code></pre>")
        } else {
            sb.append(renderInlineHtml(block.content, resolvedRefs))
        }
        // Note: </li> is closed later when the next block determines nesting
    }

    /**
     * Returns "todo" or "done" if the block's inline content starts with a
     * TaskMarkerNode of the corresponding type, otherwise null.
     */
    private fun resolveTaskClass(block: Block): String? {
        if (block.content.isBlank()) return null
        val nodes = InlineParser(block.content).parse()
        val first = nodes.firstOrNull() ?: return null
        return when {
            first is TaskMarkerNode && first.marker == "TODO" -> "todo"
            first is TaskMarkerNode && first.marker == "DONE" -> "done"
            else -> null
        }
    }

    internal fun renderInlineHtml(content: String, resolvedRefs: Map<String, String>): String {
        if (content.isBlank()) return ""
        val nodes = InlineParser(content).parse()
        return buildString { appendNodes(this, nodes, resolvedRefs) }
    }

    private fun appendNodes(
        sb: StringBuilder,
        nodes: List<InlineNode>,
        resolvedRefs: Map<String, String>
    ) {
        for (node in nodes) {
            appendNode(sb, node, resolvedRefs)
        }
    }

    private fun appendNode(
        sb: StringBuilder,
        node: InlineNode,
        resolvedRefs: Map<String, String>
    ) {
        when (node) {
            is TextNode -> sb.append(HtmlUtils.escape(node.content))
            is BoldNode -> {
                sb.append("<strong>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</strong>")
            }
            is ItalicNode -> {
                sb.append("<em>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</em>")
            }
            is StrikeNode -> {
                sb.append("<s>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</s>")
            }
            is CodeNode -> {
                sb.append("<code>").append(HtmlUtils.escape(node.content)).append("</code>")
            }
            is HighlightNode -> {
                sb.append("<mark>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</mark>")
            }
            is WikiLinkNode -> {
                sb.append("<a href=\"#")
                    .append(HtmlUtils.escapeAttr(node.target))
                    .append("\">")
                    .append(HtmlUtils.escape(node.alias ?: node.target))
                    .append("</a>")
            }
            is BlockRefNode -> {
                val resolved = resolvedRefs[node.blockUuid]
                if (resolved != null) {
                    sb.append("<blockquote data-block-ref=\"")
                        .append(HtmlUtils.escapeAttr(node.blockUuid))
                        .append("\">")
                        .append(HtmlUtils.escape(resolved))
                        .append("</blockquote>")
                } else {
                    sb.append("<span class=\"unresolved-ref\">[block ref]</span>")
                }
            }
            is TagNode -> {
                sb.append("<span class=\"tag\">#").append(HtmlUtils.escape(node.tag)).append("</span>")
            }
            is UrlLinkNode -> {
                sb.append("<a href=\"").append(HtmlUtils.escapeAttr(node.url)).append("\">")
                appendNodes(sb, node.text, resolvedRefs)
                sb.append("</a>")
            }
            is MdLinkNode -> {
                sb.append("<a href=\"")
                    .append(HtmlUtils.escapeAttr(node.url))
                    .append("\">")
                    .append(HtmlUtils.escape(node.label))
                    .append("</a>")
            }
            is ImageNode -> {
                sb.append("<img alt=\"")
                    .append(HtmlUtils.escapeAttr(node.alt))
                    .append("\" src=\"")
                    .append(HtmlUtils.escapeAttr(node.url))
                    .append("\">")
            }
            is MacroNode -> {
                sb.append("<em>[")
                    .append(HtmlUtils.escape(node.name))
                    .append(": ")
                    .append(HtmlUtils.escape(node.arguments.joinToString()))
                    .append("]</em>")
            }
            is TaskMarkerNode -> {
                when (node.marker) {
                    "TODO" -> sb.append("<input type=\"checkbox\" disabled> ")
                    "DONE" -> sb.append("<input type=\"checkbox\" checked disabled> ")
                    else -> sb.append("<span class=\"task-marker ${node.marker.lowercase()}\">")
                        .append(node.marker)
                        .append("</span> ")
                }
            }
            is LatexInlineNode -> {
                sb.append("<code class=\"math\">").append(HtmlUtils.escape(node.formula)).append("</code>")
            }
            is HardBreakNode -> sb.append("<br>")
            is SoftBreakNode -> sb.append(" ")
            is SubscriptNode -> {
                sb.append("<sub>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</sub>")
            }
            is SuperscriptNode -> {
                sb.append("<sup>")
                appendNodes(sb, node.children, resolvedRefs)
                sb.append("</sup>")
            }
            is PriorityNode -> {
                sb.append("<span class=\"priority\">[#").append(node.priority).append("]</span>")
            }
        }
    }
}
