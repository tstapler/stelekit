package dev.stapler.stelekit.parsing.ast

/**
 * Standard bullet block (e.g. "- content" or "* content" or "+ content").
 */
data class BulletBlockNode(
    override val content: List<InlineNode>,
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val level: Int
) : BlockNode()

/**
 * Paragraph block — no bullet marker, usually top-level prose or a bare line.
 */
data class ParagraphBlockNode(
    override val content: List<InlineNode>,
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap()
) : BlockNode()

/**
 * ATX heading block: `# H1` through `###### H6`.
 * CommonMark spec §4.2.
 *
 * In Logseq's outliner model headings can appear both as top-level page structure
 * and as decoration on bullet blocks (e.g. `- ## TODO My heading`).
 *
 * [level] is 1–6 (number of leading `#` characters).
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for headings
 * that decorate a bullet; top-level ATX headings default to 0.
 */
data class HeadingBlockNode(
    val level: Int,
    override val content: List<InlineNode>,
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode()

/**
 * Fenced code block: ` ``` ` or `~~~` with optional language identifier and options.
 * CommonMark spec §4.5.
 *
 * [language] is the info string immediately after the opening fence (e.g. "kotlin", "python").
 * [options] are additional words on the opening fence line (Logseq/org-mode extensions).
 * [rawContent] is the verbatim body of the block, newlines preserved.
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for fenced
 * code blocks that decorate a bullet; top-level fenced code blocks default to 0.
 */
data class CodeFenceBlockNode(
    val language: String?,
    val options: List<String> = emptyList(),
    val rawContent: String,
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode() {
    override val content: List<InlineNode> = emptyList()
}

/**
 * Block-level blockquote: one or more lines prefixed with `>`.
 * CommonMark spec §5.1.
 *
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for
 * blockquotes that decorate a bullet; top-level blockquotes default to 0.
 */
data class BlockquoteBlockNode(
    override val children: List<BlockNode>,
    override val content: List<InlineNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode()

/**
 * Ordered list item: `1.` / `1)` with optional nesting.
 * CommonMark spec §5.3.
 *
 * [number] is the numeric value of the list marker.
 * [level] mirrors BulletBlockNode's indentation level.
 */
data class OrderedListItemBlockNode(
    val number: Int,
    override val content: List<InlineNode>,
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val level: Int = 0
) : BlockNode()

/**
 * Thematic break: `---`, `***`, `___` (3+ matching characters, optional spaces).
 * CommonMark spec §4.1.
 *
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for
 * thematic breaks that decorate a bullet; top-level thematic breaks default to 0.
 */
data class ThematicBreakBlockNode(
    override val content: List<InlineNode> = emptyList(),
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode()

/**
 * GFM pipe table.
 * GFM spec §4.10.
 *
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for tables
 * that decorate a bullet; top-level tables default to 0.
 */
data class TableBlockNode(
    val headers: List<String>,
    val alignments: List<TableAlignment?>,
    val rows: List<List<String>>,
    override val content: List<InlineNode> = emptyList(),
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode()

enum class TableAlignment { LEFT, RIGHT, CENTER }

/**
 * Raw HTML block — passed through verbatim, rendered as a code block in Compose.
 * CommonMark spec §4.6.
 *
 * [indentLevel] is the outline nesting depth (mirrors [BulletBlockNode.level]) for raw
 * HTML blocks that decorate a bullet; top-level raw HTML blocks default to 0.
 */
data class RawHtmlBlockNode(
    val rawHtml: String,
    override val content: List<InlineNode> = emptyList(),
    override val children: List<BlockNode> = emptyList(),
    override val properties: Map<String, String> = emptyMap(),
    val indentLevel: Int = 0
) : BlockNode()
