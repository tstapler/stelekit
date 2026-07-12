package dev.stapler.stelekit.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.*
import dev.stapler.stelekit.sections.WikiLinkRenderDecision

/**
 * Markdown patterns retained for the edit-mode styler (applyMarkdownStylingForEditor)
 * and any callers that still do pattern-level inspection (e.g. BlockItem.kt).
 */
object MarkdownPatterns {
    val boldPattern = Regex("""(\*\*|__)(.+?)\1""")
    val italicPattern = Regex("""(?<!\*)(\*|_)(?!\1)(.+?)\1""")
    val codePattern = Regex("""`([^`]+)`""")
    val strikethroughPattern = Regex("""~~(.+?)~~""")
    val linkPattern = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
    val imagePattern = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")
    val wikiLinkPattern = Regex("""\[\[([^\]]+)\]\]""")
    val blockRefPattern = Regex("""\(\(([^)]+)\)\)""")
    val tagPattern = Regex("""#([^\s#.,!\[\]()]+)""")
    val urlPattern = Regex("""https?://[^\s<>"]+""")
}

const val WIKI_LINK_TAG = "WIKI_LINK"
const val BLOCK_REF_TAG = "BLOCK_REF"
const val TAG_TAG = "TAG"
const val PAGE_SUGGESTION_TAG = "PAGE_SUGGESTION"

/**
 * Annotation tag used for `[[PageName]]` wikilinks whose target page is absent
 * from the local DB (FR-14 cross-section backlinks).  The annotation value is
 * always [WikiLinkRenderDecision.UNAVAILABLE_TOOLTIP]; no section metadata is
 * stored here.
 */
const val CROSS_SECTION_UNAVAILABLE_TAG = "CROSS_SECTION_UNAVAILABLE"

// ── Rendering state ────────────────────────────────────────────────────────────

/**
 * Mutable rendering context passed through the recursive AST walk.
 */
private class RenderContext(
    val original: String,
    val resolvedRefs: Map<String, String>,
    val linkColor: Color,
    val blockRefBg: Color,
    val codeBackground: Color,
    val suggestionSpans: List<AhoCorasickMatcher.MatchSpan>,
    val suggestionColor: Color,
    /** Page names present in the local DB — used for FR-14 cross-section link rendering. */
    val localPageNames: Set<String> = emptySet(),
    /** When true, wikilinks absent from [localPageNames] render as unavailable. */
    val hasSectionFilter: Boolean = false,
)

// ── AST → AnnotatedString ──────────────────────────────────────────────────────

/**
 * Top-level (depth 0) render entry point. [nodesWithSpans] carries each node's exact
 * source span from [dev.stapler.stelekit.parsing.InlineParser.parseWithSpans] — using
 * the real span instead of re-deriving it via `indexOf` avoids mislocating a TextNode
 * whenever its content is duplicated inside a preceding node's raw markup
 * (e.g. `[[abc]]abc`, where a naive `indexOf("abc")` finds the copy inside the brackets).
 */
private fun AnnotatedString.Builder.renderTopLevel(
    nodesWithSpans: List<Pair<InlineNode, IntRange>>,
    ctx: RenderContext,
) {
    // Merge consecutive adjacent TextNodes into runs so that multi-word
    // page names (e.g. "Meeting Notes") are found across word/space token boundaries.
    var runOrigStart = -1
    val runContent = StringBuilder()

    fun flushRun() {
        if (runOrigStart < 0 || runContent.isEmpty()) return
        renderPlainText(runContent.toString(), runOrigStart, ctx)
        runOrigStart = -1
        runContent.clear()
    }

    for ((node, span) in nodesWithSpans) {
        if (node is TextNode && node.content.isNotEmpty()) {
            val origStart = span.first
            if (runOrigStart >= 0 && runOrigStart + runContent.length == origStart) {
                runContent.append(node.content)
            } else {
                flushRun()
                runOrigStart = origStart
                runContent.append(node.content)
            }
        } else {
            flushRun()
            renderNode(node, ctx)
        }
    }
    flushRun()
}

private fun AnnotatedString.Builder.renderNodes(
    nodes: List<InlineNode>,
    ctx: RenderContext,
) {
    for (node in nodes) renderNode(node, ctx)
}

private fun AnnotatedString.Builder.renderNode(
    node: InlineNode,
    ctx: RenderContext,
) {
    when (node) {
        is TextNode -> {
            // Depth-0 TextNodes never reach here — renderTopLevel intercepts them to
            // merge runs and resolve suggestion offsets via the real parse span.
            if (node.content.isNotEmpty()) append(node.content)
        }

        is BoldNode ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                renderNodes(node.children, ctx)
            }

        is ItalicNode ->
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                renderNodes(node.children, ctx)
            }

        is StrikeNode ->
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                renderNodes(node.children, ctx)
            }

        is HighlightNode ->
            withStyle(SpanStyle(background = Color(0xFFFFFF00).copy(alpha = 0.35f))) {
                renderNodes(node.children, ctx)
            }

        is CodeNode ->
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = ctx.codeBackground,
                    color = ctx.linkColor.copy(alpha = 0.9f),
                )
            ) { append(node.content) }

        is WikiLinkNode -> {
            val decision = WikiLinkRenderDecision.resolve(
                target = node.target,
                alias = node.alias,
                localPageNames = ctx.localPageNames,
                hasSectionFilter = ctx.hasSectionFilter,
            )
            when (decision) {
                is WikiLinkRenderDecision.NavigableLink -> {
                    val start = length
                    withStyle(SpanStyle(color = ctx.linkColor, fontWeight = FontWeight.Medium)) {
                        append("[[${decision.displayName}]]")
                    }
                    addStringAnnotation(WIKI_LINK_TAG, node.target, start, length)
                }
                is WikiLinkRenderDecision.UnavailableLink -> {
                    // FR-14: render as plain text + subtle "?" badge.
                    // No WIKI_LINK_TAG so it is not clickable as a navigation link.
                    val start = length
                    // Plain page name — slightly muted to signal it is not navigable
                    withStyle(SpanStyle(color = ctx.linkColor.copy(alpha = 0.5f))) {
                        append(decision.displayName)
                    }
                    // "?" badge — superscript, grey
                    withStyle(
                        SpanStyle(
                            baselineShift = BaselineShift.Superscript,
                            fontSize = 0.7.em,
                            color = Color.Gray,
                        )
                    ) { append("?") }
                    // Annotation carries the fixed tooltip; no section/path metadata.
                    addStringAnnotation(
                        CROSS_SECTION_UNAVAILABLE_TAG,
                        WikiLinkRenderDecision.UNAVAILABLE_TOOLTIP,
                        start,
                        length,
                    )
                }
            }
        }

        is BlockRefNode -> {
            val displayText = ctx.resolvedRefs[node.blockUuid] ?: "((…))"
            val start = length
            withStyle(
                SpanStyle(
                    color = ctx.linkColor,
                    fontStyle = FontStyle.Italic,
                    background = ctx.blockRefBg,
                )
            ) { append(displayText) }
            addStringAnnotation(BLOCK_REF_TAG, node.blockUuid, start, length)
        }

        // [label](url) — InlineParser produces UrlLinkNode for this form
        is UrlLinkNode -> {
            val start = length
            withStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)) {
                renderNodes(node.text, ctx)
            }
            addStringAnnotation("link", node.url, start, length)
        }

        // MdLinkNode is in the AST for round-trip fidelity; if InlineParser ever emits it:
        is MdLinkNode -> {
            val start = length
            withStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)) {
                append(node.label)
            }
            addStringAnnotation("link", node.url, start, length)
        }

        is ImageNode -> {
            val start = length
            withStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)) {
                append(node.alt.ifEmpty { node.url })
            }
            addStringAnnotation("image", node.url, start, length)
        }

        is TagNode -> {
            val start = length
            withStyle(SpanStyle(color = ctx.linkColor, fontWeight = FontWeight.Medium)) {
                append("#${node.tag}")
            }
            addStringAnnotation(TAG_TAG, node.tag, start, length)
        }

        is TaskMarkerNode -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = taskMarkerColor(node.marker, ctx.linkColor))) {
                append(node.marker)
                append(" ")
            }
        }

        is MacroNode -> {
            val argsStr = if (node.arguments.isEmpty()) "" else " ${node.arguments.joinToString(", ")}"
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = ctx.linkColor.copy(alpha = 0.7f))) {
                append("{{${node.name}$argsStr}}")
            }
        }

        is LatexInlineNode -> {
            // TODO(latex-phase2): replace with full KMP LaTeX renderer when available
            val formula = node.formula.trim('$')
            withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Italic,
                background = ctx.codeBackground
            )) {
                append(formula)
            }
        }

        is PriorityNode ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ctx.linkColor)) {
                append("[#${node.priority}]")
            }

        is SubscriptNode ->
            withStyle(SpanStyle(
                baselineShift = BaselineShift(-0.3f),
                fontSize = 0.75.em
            )) {
                renderNodes(node.children, ctx)
            }

        is SuperscriptNode ->
            withStyle(SpanStyle(
                baselineShift = BaselineShift.Superscript,
                fontSize = 0.75.em
            )) {
                renderNodes(node.children, ctx)
            }

        HardBreakNode -> append("\n")
        SoftBreakNode -> append(" ")
    }
}

private fun taskMarkerColor(marker: String, linkColor: Color): Color = when (marker) {
    "TODO"                              -> Color(0xFFFF6B35)
    "DONE"                              -> Color(0xFF4CAF50)
    "CANCELLED"                         -> Color.Gray
    "NOW", "DOING", "IN-PROGRESS", "STARTED" -> Color(0xFF2196F3)
    "LATER", "WAITING", "WAIT"          -> Color(0xFF9E9E9E)
    else                                -> linkColor
}

/**
 * Renders a plain-text segment that may contain bare URLs and/or page-name suggestions.
 * Processes greedily left-to-right: URLs take priority; page suggestions fill the remaining gaps.
 *
 * [originalStart] is the position of [content] within the original block content string,
 * used to encode correct source offsets in PAGE_SUGGESTION_TAG annotations.
 */
private fun AnnotatedString.Builder.renderPlainText(
    content: String,
    originalStart: Int,
    ctx: RenderContext,
) {
    // Collect URL matches and select non-overlapping ones greedily
    data class Zone(val start: Int, val end: Int, val url: String)

    val urlZones = MarkdownPatterns.urlPattern.findAll(content)
        .fold(mutableListOf<Zone>()) { acc, m ->
            val z = Zone(m.range.first, m.range.last + 1, m.value)
            if (acc.isEmpty() || z.start >= acc.last().end) acc.add(z)
            acc
        }

    var pos = 0
    for (zone in urlZones) {
        if (zone.start > pos) {
            // Gap before this URL — scan for page suggestions
            renderGapWithSuggestions(content.substring(pos, zone.start), originalStart + pos, ctx)
        }
        // Render bare URL as a clickable link
        val start = length
        withStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)) {
            append(zone.url)
        }
        addStringAnnotation("url", zone.url, start, length)
        pos = zone.end
    }
    if (pos < content.length) {
        renderGapWithSuggestions(content.substring(pos), originalStart + pos, ctx)
    }
}

/**
 * Renders a pure plain-text gap (no URLs inside) and overlays any matching page suggestions
 * from the AhoCorasick matcher. Falls back to a plain [append] when there is no matcher.
 */
private fun AnnotatedString.Builder.renderGapWithSuggestions(
    gapText: String,
    gapStart: Int,
    ctx: RenderContext,
) {
    if (gapText.isEmpty()) return
    val gapEnd = gapStart + gapText.length
    // Filter pre-computed spans to those that fall within this gap and remap to gap-local offsets.
    val matches = ctx.suggestionSpans
        .filter { it.start >= gapStart && it.end <= gapEnd }
        .map { AhoCorasickMatcher.MatchSpan(it.start - gapStart, it.end - gapStart, it.canonicalName) }
    if (matches.isEmpty()) {
        append(gapText)
        return
    }
    val highlightColor = if (ctx.suggestionColor != Color.Unspecified) ctx.suggestionColor else ctx.linkColor
    var pos = 0
    for (match in matches) {
        if (match.start > pos) append(gapText.substring(pos, match.start))
        val start = length
        withStyle(
            SpanStyle(
                textDecoration = TextDecoration.Underline,
                color = highlightColor.copy(alpha = 0.75f),
                background = highlightColor.copy(alpha = 0.08f),
            )
        ) { append(gapText.substring(match.start, match.end)) }
        addStringAnnotation(
            PAGE_SUGGESTION_TAG,
            "${match.canonicalName}|${gapStart + match.start}|${gapStart + match.end}",
            start, length,
        )
        pos = match.end
    }
    if (pos < gapText.length) append(gapText.substring(pos))
}

// ── Public API ─────────────────────────────────────────────────────────────────

/**
 * Parses [text] as Logseq-flavoured Markdown and returns a styled [AnnotatedString].
 *
 * Markdown structure is determined by [InlineParser] (the AST layer); regex is used only
 * for bare-URL detection within plain-text nodes and for page-name suggestion highlighting.
 */
fun parseMarkdownWithStyling(
    text: String,
    linkColor: Color,
    textColor: Color,
    blockRefBackgroundColor: Color = linkColor.copy(alpha = 0.08f),
    resolvedRefs: Map<String, String> = emptyMap(),
    codeBackground: Color = Color.Gray.copy(alpha = 0.15f),
    suggestionSpans: List<AhoCorasickMatcher.MatchSpan> = emptyList(),
    suggestionColor: Color = Color.Unspecified,
    /** Page names present in the local DB; used for FR-14 cross-section link rendering. */
    localPageNames: Set<String> = emptySet(),
    /** When true, wikilinks absent from [localPageNames] render as unavailable badges. */
    hasSectionFilter: Boolean = false,
): AnnotatedString {
    val nodesWithSpans = InlineParser(text).parseWithSpans()
    val ctx = RenderContext(
        original = text,
        resolvedRefs = resolvedRefs,
        linkColor = linkColor,
        blockRefBg = blockRefBackgroundColor,
        codeBackground = codeBackground,
        suggestionSpans = suggestionSpans,
        suggestionColor = suggestionColor,
        localPageNames = localPageNames,
        hasSectionFilter = hasSectionFilter,
    )
    return buildAnnotatedString {
        if (textColor != Color.Unspecified) pushStyle(SpanStyle(color = textColor))
        renderTopLevel(nodesWithSpans, ctx)
        if (textColor != Color.Unspecified) pop()
    }
}

/**
 * Extracts all page-name suggestion spans from [content] that would be highlighted in view mode.
 * Only spans in plain-text leaves (no markup around them) are returned.
 *
 * Returns an empty list when [matcher] is null.
 */
fun extractSuggestions(
    content: String,
    matcher: AhoCorasickMatcher?,
): List<AhoCorasickMatcher.MatchSpan> {
    if (matcher == null) return emptyList()
    val nodesWithSpans = InlineParser(content).parseWithSpans()
    val result = mutableListOf<AhoCorasickMatcher.MatchSpan>()

    // Merge consecutive TextNodes (including spaces) into runs before searching,
    // so that multi-word page names like "Meeting Notes" are found across token
    // boundaries (the space between words is itself a TextNode).
    var runOrigStart = -1
    val runContent = StringBuilder()

    fun flushRun() {
        if (runOrigStart < 0 || runContent.isEmpty()) return
        val run = runContent.toString()
        val urlRanges = MarkdownPatterns.urlPattern.findAll(run)
            .map { it.range.first until it.range.last + 1 }.toList()
        var pos = 0
        for (urlRange in urlRanges) {
            if (urlRange.first > pos) {
                val gap = run.substring(pos, urlRange.first)
                matcher.findAll(gap).forEach { m ->
                    result.add(AhoCorasickMatcher.MatchSpan(
                        runOrigStart + pos + m.start,
                        runOrigStart + pos + m.end,
                        m.canonicalName,
                    ))
                }
            }
            pos = urlRange.last
        }
        if (pos < run.length) {
            matcher.findAll(run.substring(pos)).forEach { m ->
                result.add(AhoCorasickMatcher.MatchSpan(
                    runOrigStart + pos + m.start,
                    runOrigStart + pos + m.end,
                    m.canonicalName,
                ))
            }
        }
        runOrigStart = -1
        runContent.clear()
    }

    for ((node, span) in nodesWithSpans) {
        if (node is TextNode && node.content.isNotEmpty()) {
            val nodeOrigStart = span.first
            // Extend current run if adjacent, otherwise flush and start a new one
            if (runOrigStart >= 0 && runOrigStart + runContent.length == nodeOrigStart) {
                runContent.append(node.content)
            } else {
                flushRun()
                runOrigStart = nodeOrigStart
                runContent.append(node.content)
            }
        } else {
            // Non-TextNode (markup node): flush the current plain-text run.
            // Suggestions are suppressed inside markup spans.
            flushRun()
        }
    }
    flushRun()

    return result
}

/**
 * Applies markdown styling to an existing [AnnotatedString.Builder] for edit mode.
 * In edit mode the markdown markers remain visible but are styled as visual hints.
 */
fun applyMarkdownStylingForEditor(
    text: String,
    builder: AnnotatedString.Builder,
    linkColor: Color,
    codeBackground: Color = Color.Gray.copy(alpha = 0.1f),
) {
    val textLength = text.length
    fun safeRange(start: Int, end: Int): Pair<Int, Int> {
        val s = start.coerceIn(0, textLength)
        val e = end.coerceIn(s, textLength)
        return s to e
    }

    MarkdownPatterns.boldPattern.findAll(text).forEach { m ->
        val (s, e) = safeRange(m.range.first, m.range.last + 1)
        if (s < e) builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
    }
    MarkdownPatterns.italicPattern.findAll(text).forEach { m ->
        val (s, e) = safeRange(m.range.first, m.range.last + 1)
        if (s < e) builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
    }
    MarkdownPatterns.codePattern.findAll(text).forEach { m ->
        val (s, e) = safeRange(m.range.first, m.range.last + 1)
        if (s < e) builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), s, e)
    }
    MarkdownPatterns.strikethroughPattern.findAll(text).forEach { m ->
        val (s, e) = safeRange(m.range.first, m.range.last + 1)
        if (s < e) builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), s, e)
    }
    MarkdownPatterns.wikiLinkPattern.findAll(text).forEach { m ->
        val linkText = m.groupValues[1]
        val (s, e) = safeRange(m.range.first, m.range.last + 1)
        if (s < e) {
            builder.addStringAnnotation(WIKI_LINK_TAG, linkText, s, e)
            builder.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium), s, e)
        }
    }
    MarkdownPatterns.tagPattern.findAll(text).forEach { m ->
        val tagName = m.groupValues[1]
        val insideLink = MarkdownPatterns.wikiLinkPattern.findAll(text)
            .any { lm -> m.range.first >= lm.range.first && m.range.last <= lm.range.last }
        if (!insideLink) {
            val (s, e) = safeRange(m.range.first, m.range.last + 1)
            if (s < e) {
                builder.addStringAnnotation(TAG_TAG, tagName, s, e)
                builder.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium), s, e)
            }
        }
    }
}
