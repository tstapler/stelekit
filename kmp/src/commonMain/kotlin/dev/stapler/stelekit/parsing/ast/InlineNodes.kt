package dev.stapler.stelekit.parsing.ast

// ── Plain text ────────────────────────────────────────────────────────────────

data class TextNode(val content: String) : InlineNode()

// ── Emphasis ──────────────────────────────────────────────────────────────────

/** `**text**` or `__text__` */
data class BoldNode(val children: List<InlineNode>) : InlineNode()

/** `*text*` or `_text_` (with left-flanking delimiter rules) */
data class ItalicNode(val children: List<InlineNode>) : InlineNode()

/** `~~text~~` */
data class StrikeNode(val children: List<InlineNode>) : InlineNode()

/** `==text==` (GFM highlight / Logseq highlight) */
data class HighlightNode(val children: List<InlineNode>) : InlineNode()

// ── Code ──────────────────────────────────────────────────────────────────────

/** `` `code` `` */
data class CodeNode(val content: String) : InlineNode()

// ── Links & References ────────────────────────────────────────────────────────

/**
 * Logseq wiki-link: `[[Page Name]]`.
 * [alias] is set when the link uses display-text syntax: `[[Page Name|display text]]`.
 */
data class WikiLinkNode(
    val target: String,
    val alias: String? = null
) : InlineNode()

/** Logseq block reference: `((block-uuid))` */
data class BlockRefNode(val blockUuid: String) : InlineNode()

/** Standard Markdown link: `[label](url)` or `[label](url "title")` */
data class MdLinkNode(
    val label: String,
    val url: String,
    val title: String? = null
) : InlineNode()

/** Inline image: `![alt](url)` */
data class ImageNode(
    val alt: String,
    val url: String
) : InlineNode()

/**
 * Auto-link or bare URL.
 * [text] preserves the original nodes so rich labels (e.g. `[text](url)` form) round-trip cleanly.
 */
data class UrlLinkNode(
    val url: String,
    val text: List<InlineNode>
) : InlineNode()

// ── Logseq-specific ───────────────────────────────────────────────────────────

/** Hashtag: `#tag-name` */
data class TagNode(val tag: String) : InlineNode()

/** Logseq/Org task priority: `[#A]`, `[#B]`, `[#C]` */
data class PriorityNode(val priority: String) : InlineNode()

/** Subscript: `~{text}` (Logseq/Org extension) */
data class SubscriptNode(val children: List<InlineNode>) : InlineNode()

/** Superscript: `^{text}` (Logseq/Org extension) */
data class SuperscriptNode(val children: List<InlineNode>) : InlineNode()

/**
 * Task status marker at the start of a block's inline content.
 * Valid markers: TODO, DONE, NOW, LATER, WAITING, CANCELLED, DOING, WAIT, STARTED, IN-PROGRESS.
 */
data class TaskMarkerNode(val marker: String) : InlineNode()

/**
 * Logseq macro: `{{name arg1, arg2}}`.
 * [name] is the macro identifier (e.g. "embed", "query", "renderer").
 * [arguments] is the raw argument string split on the first space from [name].
 *
 * Reference extraction must recurse into [arguments] to find embedded `[[page]]` links
 * (e.g. `{{embed [[My Page]]}}` contributes "My Page" to the block's reference set).
 */
data class MacroNode(
    val name: String,
    val arguments: List<String>
) : InlineNode()

// ── Math ─────────────────────────────────────────────────────────────────────

/** Inline LaTeX: `$formula$` or `\(formula\)` */
data class LatexInlineNode(val formula: String) : InlineNode()

// ── Typography ────────────────────────────────────────────────────────────────

/** Hard line break: two trailing spaces or `\` before a newline. CommonMark spec §6.7. */
data object HardBreakNode : InlineNode()

/** Soft line break: a single newline inside a paragraph. CommonMark spec §6.8. */
data object SoftBreakNode : InlineNode()
