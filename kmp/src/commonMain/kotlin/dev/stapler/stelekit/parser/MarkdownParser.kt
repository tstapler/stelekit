package dev.stapler.stelekit.parser

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.model.ParsedPage
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.OutlinerParser
import dev.stapler.stelekit.parsing.ast.*

class MarkdownParser {

    private val logger = Logger("MarkdownParser")
    private val parser = OutlinerParser()

    fun parsePage(content: String, mode: dev.stapler.stelekit.parsing.ParseMode = dev.stapler.stelekit.parsing.ParseMode.FULL): ParsedPage {
        try {
            val document = parser.parse(content, mode)

            // Convert AST to ParsedPage model
            val parsedBlocks = document.children.map { convertBlock(it) }

            return ParsedPage(
                title = null,
                properties = emptyMap(), // Page props handled in GraphLoader via first block?
                blocks = parsedBlocks
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log the content that caused the error for debugging
            logger.error("Error parsing content (length=${content.length}): ${e.message}", e)
            logger.error("Content preview: ${content.take(200)}...")
            throw e
        }
    }

    private fun convertBlock(block: BlockNode): ParsedBlock {
        val level = when(block) {
            is BulletBlockNode -> block.level
            is ParagraphBlockNode -> 0
            is HeadingBlockNode -> 0
            is CodeFenceBlockNode -> 0
            is BlockquoteBlockNode -> 0
            is OrderedListItemBlockNode -> block.level
            is ThematicBreakBlockNode -> 0
            is TableBlockNode -> 0
            is RawHtmlBlockNode -> 0
        }

        val blockType = when (block) {
            is BulletBlockNode -> BlockType.Bullet
            is ParagraphBlockNode -> BlockType.Paragraph
            is HeadingBlockNode -> BlockType.Heading(level = block.level)
            is CodeFenceBlockNode -> BlockType.CodeFence(language = block.language.orEmpty())
            is BlockquoteBlockNode -> BlockType.Blockquote
            is OrderedListItemBlockNode -> BlockType.OrderedListItem(number = block.number)
            is ThematicBreakBlockNode -> BlockType.ThematicBreak
            is TableBlockNode -> BlockType.Table
            is RawHtmlBlockNode -> BlockType.RawHtml
        }
        
        // Serialize AST back to a raw string for storage; the UI re-parses on render.
        // See project_plans/render-all-markdown/decisions/ for the ADR on this two-pass design.

        val contentString = when (block) {
            is TableBlockNode -> {
                val header = block.headers.joinToString(" | ", "| ", " |")
                val separator = block.alignments.map { align ->
                    when (align) {
                        dev.stapler.stelekit.parsing.ast.TableAlignment.LEFT -> ":---"
                        dev.stapler.stelekit.parsing.ast.TableAlignment.RIGHT -> "---:"
                        dev.stapler.stelekit.parsing.ast.TableAlignment.CENTER -> ":---:"
                        null -> "---"
                    }
                }.joinToString(" | ", "| ", " |")
                val rows = block.rows.map { row -> row.joinToString(" | ", "| ", " |") }
                (listOf(header, separator) + rows).joinToString("\n")
            }
            is CodeFenceBlockNode -> {
                val lang = block.language.orEmpty()
                val fence = "```"
                "$fence$lang\n${block.rawContent}\n$fence"
            }
            is BlockquoteBlockNode -> {
                block.children.map { child -> "> ${reconstructContent(child.content)}" }.joinToString("\n")
            }
            else -> reconstructContent(block.content)
        }
        
        // Extract references from InlineNodes
        val references = extractReferences(block.content)
        
        val children = block.children.map { convertBlock(it) }
        
        // TimestampParser strips SCHEDULED/DEADLINE markers from content and extracts them as metadata.
        val timestampResult = TimestampParser.parse(contentString)
        
        return ParsedBlock(
            content = timestampResult.content, // Strip timestamps
            properties = block.properties,
            level = level,
            children = children,
            references = references,
            scheduled = timestampResult.scheduled,
            deadline = timestampResult.deadline,
            blockType = blockType
        )
    }
    
    private fun reconstructContent(nodes: List<InlineNode>): String {
        val sb = StringBuilder()
        nodes.forEach { node ->
            when(node) {
                is TextNode -> sb.append(node.content)
                is BoldNode -> {
                    sb.append("**")
                    sb.append(reconstructContent(node.children))
                    sb.append("**")
                }
                is ItalicNode -> {
                    sb.append("*")
                    sb.append(reconstructContent(node.children))
                    sb.append("*")
                }
                is StrikeNode -> {
                    sb.append("~~")
                    sb.append(reconstructContent(node.children))
                    sb.append("~~")
                }
                is CodeNode -> {
                    sb.append("`")
                    sb.append(node.content)
                    sb.append("`")
                }
                is WikiLinkNode -> {
                    sb.append("[[")
                    sb.append(node.target)
                    sb.append("]]")
                }
                is BlockRefNode -> {
                    sb.append("((")
                    sb.append(node.blockUuid)
                    sb.append("))")
                }
                is TagNode -> {
                    if (node.tag.contains(' ')) {
                        sb.append("#[[")
                        sb.append(node.tag)
                        sb.append("]]")
                    } else {
                        sb.append("#")
                        sb.append(node.tag)
                    }
                }
                is UrlLinkNode -> {
                    // Detect autolink form: text is a single TextNode equal to url
                    val isAutoLink = node.text.size == 1 &&
                        node.text[0] is TextNode &&
                        (node.text[0] as TextNode).content == node.url
                    if (isAutoLink) {
                        sb.append("<")
                        sb.append(node.url)
                        sb.append(">")
                    } else {
                        sb.append("[")
                        sb.append(reconstructContent(node.text))
                        sb.append("](")
                        sb.append(node.url)
                        sb.append(")")
                    }
                }
                is HighlightNode -> {
                    sb.append("==")
                    sb.append(reconstructContent(node.children))
                    sb.append("==")
                }
                is MdLinkNode -> {
                    sb.append("[")
                    sb.append(node.label)
                    sb.append("](")
                    sb.append(node.url)
                    if (node.title != null) sb.append(" \"${node.title}\"")
                    sb.append(")")
                }
                is ImageNode -> {
                    sb.append("![")
                    sb.append(node.alt)
                    sb.append("](")
                    sb.append(node.url)
                    sb.append(")")
                }
                is MacroNode -> {
                    sb.append("{{")
                    sb.append(node.name)
                    if (node.arguments.isNotEmpty()) {
                        sb.append(" ")
                        sb.append(node.arguments.joinToString(", "))
                    }
                    sb.append("}}")
                }
                is TaskMarkerNode -> {
                    sb.append(node.marker)
                    sb.append(" ")
                }
                is LatexInlineNode -> {
                    sb.append("$")
                    sb.append(node.formula)
                    sb.append("$")
                }
                is PriorityNode -> sb.append("[#${node.priority}]")
                is SubscriptNode -> {
                    sb.append("~{")
                    sb.append(reconstructContent(node.children))
                    sb.append("}")
                }
                is SuperscriptNode -> {
                    sb.append("^{")
                    sb.append(reconstructContent(node.children))
                    sb.append("}")
                }
                HardBreakNode -> sb.append("\n")
                SoftBreakNode -> sb.append("\n")
            }
        }
        return sb.toString()
    }
    
    private val wikiLinkRegex = Regex("""\[\[([^\]]+)]]""")

    private fun extractReferences(nodes: List<InlineNode>): List<String> {
        val refs = mutableListOf<String>()
        nodes.forEach { node ->
            when(node) {
                is WikiLinkNode -> refs.add(node.target)
                is BlockRefNode -> refs.add(node.blockUuid)
                is TagNode -> refs.add(node.tag) // Tags are references? Yes usually.
                is BoldNode -> refs.addAll(extractReferences(node.children))
                is ItalicNode -> refs.addAll(extractReferences(node.children))
                is StrikeNode -> refs.addAll(extractReferences(node.children))
                is HighlightNode -> refs.addAll(extractReferences(node.children))
                is UrlLinkNode -> refs.addAll(extractReferences(node.text))
                is MacroNode -> {
                    // e.g. {{embed [[Page]]}} — extract wiki links from arguments
                    node.arguments.forEach { arg ->
                        wikiLinkRegex.findAll(arg).forEach { refs.add(it.groupValues[1]) }
                    }
                }
                else -> {}
            }
        }
        return refs
    }
}
