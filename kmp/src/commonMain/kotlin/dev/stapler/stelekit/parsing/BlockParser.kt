package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*
import dev.stapler.stelekit.parsing.lexer.*

class BlockParser(private val source: CharSequence) {
    private val lexer = Lexer(source)
    private var currentToken = lexer.nextToken()

    companion object {
        private val THEMATIC_BREAK_REGEX = Regex("---+|___+")
        private val TABLE_SEPARATOR_REGEX = Regex("-+")
        private val ORDERED_LIST_EXTRACT_REGEX = Regex("^(\\d+)\\.$")

        // Block-level HTML tag names (CommonMark §4.6 type-6 tag list, trimmed to the
        // subset relevant for a Markdown outliner). Case-insensitive.
        private val BLOCK_HTML_TAGS = setOf(
            "address", "article", "aside", "base", "basefont", "blockquote", "body",
            "caption", "center", "col", "colgroup", "dd", "details", "dialog", "dir",
            "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header",
            "hr", "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem",
            "nav", "noframes", "ol", "optgroup", "option", "p", "param", "section",
            "summary", "table", "tbody", "td", "tfoot", "th", "thead", "title", "tr",
            "track", "ul", "script", "style", "pre", "textarea"
        )
    }

    fun parse(): DocumentNode {
        val rootBlocks = parseBlocksAtLevel(0)
        return DocumentNode(rootBlocks)
    }

    private fun parseBlocksAtLevel(minLevel: Int): List<BlockNode> {
        val blocks = mutableListOf<BlockNode>()

        while (currentToken.type != TokenType.EOF) {
            val currentLevel = peekIndentLevel()

            if (currentLevel < minLevel) {
                // This block belongs to a parent (or grandparent)
                break
            }

            // We are at a block that is at least minLevel.
            // In strict mode, it should be == minLevel.
            // But if user skips levels (e.g. 0 -> 2), we treat it as a child of the previous?
            // Or just a block at level 2.

            // For now, let's assume we consume one block and its children.
            val block = parseBlock(currentLevel)
            blocks.add(block)
        }
        return blocks
    }

    private fun parseBlock(level: Int): BlockNode {
        // 1. Consume INDENT if present
        if (currentToken.type == TokenType.INDENT) {
            advance()
        }

        // 1a. Check for ATX heading: # / ## / ### etc at line start
        val topLevelHeadingLevel = tryConsumeAtxHeadingMarker()
        if (topLevelHeadingLevel != null) {
            val contentStr = parseLine()
            // Strip optional trailing # sequence and whitespace
            val stripped = contentStr.trimEnd('#').trimEnd()
            val (properties, children) = parseTrailingPropertiesAndChildren(level)
            return HeadingBlockNode(
                level = topLevelHeadingLevel,
                content = listOf(TextNode(stripped)),
                children = children,
                properties = properties,
                indentLevel = level
            )
        }

        // 1b. Check for a fenced code block, blockquote, ordered list, thematic break,
        // GFM table, or raw HTML block at the top level.
        tryConsumeNonHeadingConstruct(level, isBulletDecorated = false)?.let { return it }

        // 2. Check for Bullet
        val isBullet = if (currentToken.type == TokenType.BULLET) {
            advance()
            true
        } else {
            false
        }

        // 2a. A bullet's content may itself be an ATX heading (e.g. "- # Core Definition"),
        // which is how Logseq decorates outline items as headings. Detect it here so the
        // bullet's outline structure (level/children) is preserved alongside heading styling.
        val bulletHeadingLevel = if (isBullet) tryConsumeAtxHeadingMarker() else null

        // 2b. A bullet's content may likewise be a fenced code block, blockquote, ordered
        // list item, thematic break, GFM table, or raw HTML block (e.g. "- ```kotlin",
        // "- > quote", "- 1. item", "- ---", "- | a | b |", "- <div>"). These constructs
        // were previously only detected before bullet-token consumption (see 1b above), so
        // decorating a bullet with any of them fell through to plain bullet/paragraph
        // parsing and rendered as literal Markdown text — the same structural bug already
        // fixed for headings.
        if (isBullet && bulletHeadingLevel == null) {
            tryConsumeNonHeadingConstruct(level, isBulletDecorated = true)?.let { return it }
        }

        // 3. Parse Content & Properties
        // A block consists of:
        // - First line text
        // - Optional properties (indented, key:: value)
        // - Optional continuation text (indented, no bullet)
        // - Children (indented, bullet)

        val contentBuilder = StringBuilder()
        val properties = mutableMapOf<String, String>()

        // Parse first line
        contentBuilder.append(parseLine())

        // Check for subsequent lines that belong to this block
        while (currentToken.type != TokenType.EOF) {
            val nextLevel = peekIndentLevel()
            val nextIsBullet = peekIsBullet()

            if (nextLevel <= level) {
                // Sibling or parent -> Stop
                break
            }

            if (nextIsBullet) {
                // Child block -> Stop processing content, move to children
                break
            }

            // It is indented and NOT a bullet -> Content or Property
            val property = tryConsumeIndentedProperty()
            if (property != null) {
                properties[property.first] = property.second
            } else {
                // Not a property — consume the indent and treat the rest of the line as
                // continuation text.
                if (currentToken.type == TokenType.INDENT) advance()
                if (contentBuilder.isNotEmpty()) contentBuilder.append("\n")
                contentBuilder.append(parseLine())
            }
        }

        // 4. Parse Children
        // Children are blocks with level > current level
        // We already verified above that if we hit a bullet > level, it's a child.
        val children = parseBlocksAtLevel(level + 1)

        return when {
            bulletHeadingLevel != null -> {
                // Strip optional trailing # sequence and whitespace, mirroring the
                // top-level ATX heading handling above.
                val stripped = contentBuilder.toString().trimEnd('#').trimEnd()
                HeadingBlockNode(
                    level = bulletHeadingLevel,
                    content = listOf(TextNode(stripped)),
                    children = children,
                    properties = properties,
                    indentLevel = level
                )
            }
            isBullet -> BulletBlockNode(
                content = listOf(TextNode(contentBuilder.toString())),
                children = children,
                properties = properties,
                level = level
            )
            else -> ParagraphBlockNode(
                content = listOf(TextNode(contentBuilder.toString())),
                children = children,
                properties = properties
            )
        }
    }

    /**
     * Parses a blockquote's own lines (the `> `-prefixed content, plus any `>`-prefixed
     * continuation lines). [indentLevel] is the outline nesting depth of the bullet this
     * blockquote decorates (0 for a top-level, non-bulleted blockquote) and is attached
     * verbatim to the returned node so [MarkdownParser.convertBlock] can recover the
     * blockquote's outline position — mirroring [HeadingBlockNode.indentLevel].
     */
    private fun parseBlockquote(indentLevel: Int): BlockquoteBlockNode {
        val innerBlocks = mutableListOf<BlockNode>()
        // Parse first line content
        val line = parseLine()
        innerBlocks.add(ParagraphBlockNode(content = listOf(TextNode(line))))
        // Collect continuation lines
        while (currentToken.type != TokenType.EOF) {
            if (currentToken.type == TokenType.R_ANGLE) {
                advance() // consume >
                if (currentToken.type == TokenType.WS) advance()
                val nextLine = parseLine()
                // Merge into last paragraph or add new block
                val last = innerBlocks.lastOrNull()
                if (last is ParagraphBlockNode) {
                    val existing = (last.content.firstOrNull() as? TextNode)?.content ?: ""
                    innerBlocks[innerBlocks.lastIndex] = last.copy(
                        content = listOf(TextNode("$existing\n$nextLine"))
                    )
                } else {
                    innerBlocks.add(ParagraphBlockNode(content = listOf(TextNode(nextLine))))
                }
            } else break
        }
        return BlockquoteBlockNode(children = innerBlocks, indentLevel = indentLevel)
    }

    private fun tryParseTable(): TableBlockNode? {
        val savedState = lexer.saveState()
        val savedToken = currentToken

        // Parse header row
        val headerRow = parsePipeRow() ?: run {
            lexer.restoreState(savedState); currentToken = savedToken; return null
        }

        // Parse separator row
        val separatorRow = parsePipeRow() ?: run {
            lexer.restoreState(savedState); currentToken = savedToken; return null
        }
        // Validate separator row (must be all dashes with optional colons)
        val alignments = mutableListOf<TableAlignment?>()
        for (cell in separatorRow) {
            val c = cell.trim()
            val alignment = when {
                c.startsWith(":") && c.endsWith(":") -> TableAlignment.CENTER
                c.endsWith(":") -> TableAlignment.RIGHT
                c.startsWith(":") -> TableAlignment.LEFT
                c.matches(TABLE_SEPARATOR_REGEX) -> null
                else -> {
                    lexer.restoreState(savedState)
                    currentToken = savedToken
                    return null
                }
            }
            alignments.add(alignment)
        }

        // Parse data rows
        val rows = mutableListOf<List<String>>()
        while (currentToken.type == TokenType.PIPE) {
            val row = parsePipeRow() ?: break
            rows.add(row)
        }

        return TableBlockNode(
            headers = headerRow,
            alignments = alignments,
            rows = rows
        )
    }

    private fun parsePipeRow(): List<String>? {
        if (currentToken.type != TokenType.PIPE) return null
        advance() // consume leading |

        val cells = mutableListOf<String>()
        val cellSb = StringBuilder()

        while (currentToken.type != TokenType.NEWLINE && currentToken.type != TokenType.EOF) {
            if (currentToken.type == TokenType.PIPE) {
                cells.add(cellSb.toString().trim())
                cellSb.clear()
            } else {
                cellSb.append(currentToken.text(source))
            }
            advance()
        }
        // Don't add trailing empty cell from final |
        val trailing = cellSb.toString().trim()
        if (trailing.isNotEmpty()) cells.add(trailing)

        if (currentToken.type == TokenType.NEWLINE) advance()

        return if (cells.isEmpty()) null else cells
    }

    private fun parseLine(): String {
        val sb = StringBuilder()
        while (currentToken.type != TokenType.NEWLINE && currentToken.type != TokenType.EOF) {
            sb.append(currentToken.text(source))
            advance()
        }
        if (currentToken.type == TokenType.NEWLINE) {
            advance()
        }
        return sb.toString()
    }

    private fun tryParseProperty(): Pair<String, String>? {
        // Expected sequence: TEXT(key) [UNDERSCORE TEXT | TEXT-hyphen]* COLON COLON WS? value
        // Keys may contain underscores (page_type) or hyphens (created-at).
        // The Lexer emits UNDERSCORE as a separate token, and '-' is part of TEXT for non-bullet lines.
        // Hyphens in mid-key text arrive as part of the TEXT token itself (e.g. "created-at").

        // 1. Must start with TEXT
        if (currentToken.type != TokenType.TEXT) return null

        // Save state so we can backtrack if this is not a property
        val savedState = lexer.saveState()
        val savedToken = currentToken

        val keySb = StringBuilder()
        keySb.append(currentToken.text(source))
        advance()

        // 2. Collect additional key parts: (UNDERSCORE TEXT?)*
        while (currentToken.type == TokenType.UNDERSCORE) {
            keySb.append(currentToken.text(source)) // append '_'
            advance()
            if (currentToken.type == TokenType.TEXT) {
                keySb.append(currentToken.text(source))
                advance()
            }
        }

        // 3. Now expect COLON COLON
        if (currentToken.type != TokenType.COLON) {
            // Not a property — backtrack
            lexer.restoreState(savedState)
            currentToken = savedToken
            return null
        }
        advance() // consume first ':'

        if (currentToken.type != TokenType.COLON) {
            // Only one colon — not a property, backtrack
            lexer.restoreState(savedState)
            currentToken = savedToken
            return null
        }
        advance() // consume second ':'

        // 4. Consume optional WS
        if (currentToken.type == TokenType.WS) advance()

        // 5. Consume value (rest of line)
        val value = parseLine()
        return keySb.toString() to value
    }

    private fun peekIndentLevel(): Int {
        if (currentToken.type == TokenType.INDENT) {
            val text = currentToken.text(source)
            return calculateLevel(text)
        }
        return 0
    }

    private fun isOrderedListMarker(text: String): Boolean {
        if (text.isEmpty() || !text.last().equals('.')) return false
        return text.dropLast(1).all { it.isDigit() } && text.length > 1
    }

    private fun peekIsBullet(): Boolean {
        if (currentToken.type == TokenType.BULLET) return true

        // Check for ordered list "N."
        if (currentToken.type == TokenType.TEXT) {
            val txt = currentToken.text(source).toString()
            if (isOrderedListMarker(txt)) return true
        }

        if (currentToken.type == TokenType.INDENT) {
            val next = peekToken(1)
            if (next.type == TokenType.BULLET) return true
            if (next.type == TokenType.TEXT && isOrderedListMarker(next.text(source).toString())) return true
        }
        return false
    }

    /**
     * If [currentToken] starts a valid ATX heading marker (a run of 1–6 `#` followed by
     * whitespace, a newline, or EOF), consumes the `#` run and any single following space
     * and returns the heading level (1–6). Otherwise leaves the token stream untouched and
     * returns null.
     */
    private fun tryConsumeAtxHeadingMarker(): Int? {
        if (currentToken.type != TokenType.HASH) return null
        val hashLen = currentToken.end - currentToken.start
        if (hashLen > 6) return null
        val next = peekToken(1)
        if (next.type != TokenType.WS && next.type != TokenType.NEWLINE && next.type != TokenType.EOF) return null

        val headingLevel = hashLen.coerceIn(1, 6)
        advance() // consume HASH run
        if (currentToken.type == TokenType.WS) advance() // consume space
        return headingLevel
    }

    /**
     * Detects and parses a fenced code block, blockquote, ordered list item, thematic
     * break, GFM table, or raw HTML block starting at [currentToken]. Used both at the
     * top level and (after bullet-token consumption) for the same constructs decorating
     * a bullet's content — see the call sites in [parseBlock]. Returns null and leaves
     * the token stream untouched if none of these constructs match.
     *
     * [isBulletDecorated] indicates whether this construct is decorating a bullet or
     * standing as a non-bulleted construct; either way [level] is the correct outline
     * nesting depth and is used directly as `indentLevel` — mirrors the
     * [HeadingBlockNode.indentLevel] pattern.
     *
     * Each matched construct also collects any trailing property lines ("key:: value")
     * and outline children indented past [level] via [parseTrailingPropertiesAndChildren],
     * mirroring how [parseBlock]'s shared step 3 handles headings and plain bullets. Without
     * this, a bullet decorated with one of these constructs would return immediately and
     * orphan its nested children/properties to the caller as mis-leveled siblings.
     */
    private fun tryConsumeNonHeadingConstruct(level: Int, isBulletDecorated: Boolean): BlockNode? {
        val indentLevel = level

        tryParseFencedCodeConstruct(level, indentLevel)?.let { return it }
        tryParseThematicBreakConstruct(level, indentLevel)?.let { return it }
        tryParseBlockquoteConstruct(level, indentLevel)?.let { return it }
        tryParseOrderedListItemConstruct(level)?.let { return it }
        tryParseTableConstruct(level, indentLevel)?.let { return it }
        tryParseRawHtmlConstruct(level, indentLevel)?.let { return it }

        return null
    }

    /** Fenced code block: ``` or ~~~ (both fence characters share identical dispatch logic). */
    private fun tryParseFencedCodeConstruct(level: Int, indentLevel: Int): CodeFenceBlockNode? {
        val fenceType = currentToken.type
        if (fenceType != TokenType.BACKTICK && fenceType != TokenType.TILDE) return null
        val fenceLen = currentToken.end - currentToken.start
        if (fenceLen < 3) return null

        val node = parseFencedCodeBlock(fenceType)
        val (properties, children) = parseTrailingPropertiesAndChildren(level)
        return node.copy(properties = properties, children = children, indentLevel = indentLevel)
    }

    /**
     * Thematic break: `---`/`___` (lexed as TEXT) or `***` (lexed as a STAR run) — both
     * forms share identical marker-consumption and trailing-properties/children logic
     * once the marker itself is recognized.
     */
    private fun tryParseThematicBreakConstruct(level: Int, indentLevel: Int): ThematicBreakBlockNode? {
        val isThematicBreakMarker = when (currentToken.type) {
            TokenType.TEXT -> currentToken.text(source).toString().matches(THEMATIC_BREAK_REGEX)
            TokenType.STAR -> (currentToken.end - currentToken.start) >= 3
            else -> false
        }
        if (!isThematicBreakMarker) return null

        val next = peekToken(1)
        if (next.type != TokenType.NEWLINE && next.type != TokenType.EOF) return null

        advance() // consume marker run
        if (currentToken.type == TokenType.NEWLINE) advance()
        val (properties, children) = parseTrailingPropertiesAndChildren(level)
        return ThematicBreakBlockNode(properties = properties, children = children, indentLevel = indentLevel)
    }

    /** Blockquote: `> content`. */
    private fun tryParseBlockquoteConstruct(level: Int, indentLevel: Int): BlockquoteBlockNode? {
        if (currentToken.type != TokenType.R_ANGLE) return null

        advance() // consume >
        if (currentToken.type == TokenType.WS) advance() // optional space
        val bq = parseBlockquote(indentLevel)
        // BlockquoteBlockNode.children already holds the quote's own continuation
        // paragraphs (see parseBlockquote); append outline children after them so
        // neither the quote's internal structure nor its nested outline items are lost.
        val (properties, outlineChildren) = parseTrailingPropertiesAndChildren(level)
        return bq.copy(properties = properties, children = bq.children + outlineChildren)
    }

    /** Ordered list item: `N. content`. */
    private fun tryParseOrderedListItemConstruct(level: Int): OrderedListItemBlockNode? {
        if (currentToken.type != TokenType.TEXT) return null
        val txt = currentToken.text(source).toString()
        val numDotMatch = ORDERED_LIST_EXTRACT_REGEX.find(txt) ?: return null

        val peekNext = peekToken(1)
        if (peekNext.type != TokenType.WS && peekNext.type != TokenType.EOF && peekNext.type != TokenType.NEWLINE) return null

        val number = numDotMatch.groupValues[1].toInt()
        advance() // consume "N."
        if (currentToken.type == TokenType.WS) advance() // consume space
        val contentStr = parseLine()
        val (properties, children) = parseTrailingPropertiesAndChildren(level)
        return OrderedListItemBlockNode(
            number = number,
            content = listOf(TextNode(contentStr)),
            children = children,
            properties = properties,
            level = level
        )
    }

    /** GFM pipe table: starts with `|`. */
    private fun tryParseTableConstruct(level: Int, indentLevel: Int): TableBlockNode? {
        if (currentToken.type != TokenType.PIPE) return null
        val tableNode = tryParseTable() ?: return null
        val (properties, children) = parseTrailingPropertiesAndChildren(level)
        return tableNode.copy(properties = properties, children = children, indentLevel = indentLevel)
    }

    /** Raw HTML block: `<div>`, `<!-- comment -->`, etc. (CommonMark §4.6, type-6 tag subset). */
    private fun tryParseRawHtmlConstruct(level: Int, indentLevel: Int): RawHtmlBlockNode? {
        if (currentToken.type != TokenType.L_ANGLE) return null

        val isComment = run {
            val excl = peekToken(1)
            val body = peekToken(2)
            excl.type == TokenType.EXCLAMATION &&
                body.type == TokenType.TEXT &&
                body.text(source).startsWith("--")
        }
        val tagName = run {
            // A closing tag ("</div>") lexes as a single TEXT token "/div" because '/'
            // is not a special character — strip the leading slash before extracting
            // the tag name so both opening and closing tags are recognized.
            val nameToken = peekToken(1)
            if (nameToken.type == TokenType.TEXT) {
                nameToken.text(source).toString()
                    .removePrefix("/")
                    .takeWhile { it.isLetterOrDigit() }
                    .lowercase()
            } else {
                null
            }
        }
        if (!(isComment || (tagName != null && tagName in BLOCK_HTML_TAGS))) return null

        // CommonMark §4.6 type-6 raw HTML blocks continue consuming lines indented at
        // or deeper than the opening line's level until a blank line or EOF — a single
        // parseLine() call previously captured only the opening tag's line, splitting
        // multi-line HTML (e.g. "<div>\ncontent\n</div>") into separate sibling blocks
        // instead of one raw HTML block, and requiring exact-indent continuation lines
        // broke on any deeper-indented (but still-inside) content like "  <li>...". The
        // block ends when a line dedents shallower than this construct's level, or
        // dedents back to exactly this level as a new sibling bullet (so a following
        // "- next item" at the same depth is NOT swallowed as HTML text). A line indented
        // *deeper* than this level that is itself a bullet (nested outline child) or a
        // "key:: value" property line is likewise left alone — those belong to this
        // outliner's own nested properties/children, handled below by
        // parseTrailingPropertiesAndChildren, not to the raw HTML text.
        val htmlBuilder = StringBuilder(parseLine())
        while (currentToken.type != TokenType.EOF) {
            if (currentToken.type == TokenType.NEWLINE) {
                // Blank line reached — terminates the raw HTML block. Consume it so
                // it doesn't leak into the next construct's parsing.
                advance()
                break
            }
            val nextIndent = peekIndentLevel()
            if (nextIndent < level) break
            if (nextIndent == level) {
                if (peekIsBullet()) break
            } else if (peekIsBullet() || peekIsIndentedProperty()) {
                break
            }
            htmlBuilder.append('\n')
            htmlBuilder.append(parseLine())
        }
        val (properties, children) = parseTrailingPropertiesAndChildren(level)
        return RawHtmlBlockNode(
            rawHtml = htmlBuilder.toString(),
            properties = properties,
            children = children,
            indentLevel = indentLevel
        )
    }

    /**
     * Collects property lines ("key:: value") and outline children immediately following
     * a just-parsed non-heading construct (fenced code, blockquote, thematic break,
     * ordered list item, or table), mirroring [parseBlock]'s shared step 3 handling for
     * headings and plain bullets. A candidate property line is speculatively consumed past
     * its leading INDENT; if it does not turn out to be a property, the lexer position is
     * restored (INDENT included) so [parseBlocksAtLevel] sees the correct indent level and
     * parses it as its own block instead.
     */
    private fun parseTrailingPropertiesAndChildren(level: Int): Pair<Map<String, String>, List<BlockNode>> {
        val properties = mutableMapOf<String, String>()
        while (currentToken.type != TokenType.EOF) {
            val nextLevel = peekIndentLevel()
            val nextIsBullet = peekIsBullet()
            if (nextLevel <= level || nextIsBullet) break

            val property = tryConsumeIndentedProperty()
            if (property != null) {
                properties[property.first] = property.second
            } else {
                // Not a property line — the lexer position was already restored (including
                // the INDENT) by tryConsumeIndentedProperty, so parseBlocksAtLevel sees the
                // correct indent level and parses it as its own block.
                break
            }
        }
        val children = parseBlocksAtLevel(level + 1)
        return properties to children
    }

    /**
     * Speculatively parses a "key:: value" property line, consuming a leading INDENT
     * token (if present) and the trailing NEWLINE on success. Shared by [parseBlock]'s
     * content/property loop and [parseTrailingPropertiesAndChildren] — both need to try
     * a candidate indented line as a property before falling back to their own
     * different handling of a non-property line (continuation text vs. leaving it for
     * [parseBlocksAtLevel]).
     *
     * On failure (the line is not a property), the lexer/token state is fully restored
     * to exactly where it was before this call — including the INDENT token — so the
     * caller can decide how to (re-)consume the line.
     */
    private fun tryConsumeIndentedProperty(): Pair<String, String>? {
        val savedState = lexer.saveState()
        val savedToken = currentToken
        if (currentToken.type == TokenType.INDENT) advance()

        val property = tryParseProperty()
        if (property != null) {
            if (currentToken.type == TokenType.NEWLINE) advance()
            return property
        }

        lexer.restoreState(savedState)
        currentToken = savedToken
        return null
    }

    /**
     * Non-consuming lookahead for [tryConsumeIndentedProperty]: reports whether the
     * current (possibly INDENT-prefixed) line is a "key:: value" property line, without
     * disturbing the lexer/token state either way. Used by [tryParseRawHtmlConstruct]'s
     * continuation loop to tell a genuine trailing property line apart from raw HTML
     * text that happens to be indented past the construct's own level.
     */
    private fun peekIsIndentedProperty(): Boolean {
        val savedState = lexer.saveState()
        val savedToken = currentToken
        val isProperty = tryConsumeIndentedProperty() != null
        lexer.restoreState(savedState)
        currentToken = savedToken
        return isProperty
    }

    /**
     * Parses the body of a fenced code block whose opening fence token type is
     * [fenceType] (BACKTICK for ``` ``` ```, TILDE for `~~~`). [currentToken] must be
     * positioned on the opening fence token when this is called.
     */
    private fun parseFencedCodeBlock(fenceType: TokenType): CodeFenceBlockNode {
        advance() // consume opening fence
        val language = if (currentToken.type == TokenType.TEXT) {
            val lang = currentToken.text(source).toString().trim()
            parseLine() // consume rest of opening line (including lang token)
            lang
        } else {
            parseLine() // consume newline
            null
        }
        // Collect body until matching fence (same fence type, length >= 3) or EOF
        val body = StringBuilder()
        while (currentToken.type != TokenType.EOF) {
            if (currentToken.type == fenceType) {
                val closeLen = currentToken.end - currentToken.start
                if (closeLen >= 3) {
                    advance() // consume closing fence
                    if (currentToken.type == TokenType.NEWLINE) advance()
                    break
                }
            }
            if (currentToken.type == TokenType.NEWLINE) {
                body.append('\n')
                advance()
            } else {
                body.append(currentToken.text(source))
                advance()
            }
        }
        // Trim trailing newline from body
        val rawContent = body.toString().trimEnd('\n')
        return CodeFenceBlockNode(language = language, rawContent = rawContent)
    }

    private fun peekToken(offset: Int): Token {
        if (offset == 0) return currentToken

        val state = lexer.saveState()
        // Current token is already consumed/cached in `currentToken`?
        // No, `currentToken` holds the result of `lexer.nextToken()`.
        // The lexer's cursor is AFTER `currentToken`.
        // So `lexer.nextToken()` will return the *next* token (offset 1).

        var token = currentToken
        // We need to advance `offset` times from current state?
        // No, `lexer` is poised to return `next` (offset 1).

        repeat(offset) {
            token = lexer.nextToken()
        }

        lexer.restoreState(state)
        return token
    }



    private fun calculateLevel(text: CharSequence): Int {
        var spaces = 0
        var tabs = 0
        for (char in text) {
            when (char) {
                ' ' -> spaces++
                '\t' -> tabs++
            }
        }
        // Logic: 1 tab = 1 level, 2 spaces = 1 level. Rounding up.
        return tabs + ((spaces + 1) / 2)
    }

    private fun advance() {
        currentToken = lexer.nextToken()
    }
}
