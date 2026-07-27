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
            return HeadingBlockNode(
                level = topLevelHeadingLevel,
                content = listOf(TextNode(stripped))
            )
        }

        // 1b. Check for a fenced code block, blockquote, ordered list, thematic break, or
        // GFM table at the top level.
        tryConsumeNonHeadingConstruct(level)?.let { return it }

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
        // list item, thematic break, or GFM table (e.g. "- ```kotlin", "- > quote",
        // "- 1. item", "- ---", "- | a | b |"). These constructs were previously only
        // detected before bullet-token consumption (see 1b above), so decorating a bullet
        // with any of them fell through to plain bullet/paragraph parsing and rendered as
        // literal Markdown text — the same structural bug already fixed for headings.
        if (isBullet && bulletHeadingLevel == null) {
            tryConsumeNonHeadingConstruct(level)?.let { return it }
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
            // Consume the indent
            if (currentToken.type == TokenType.INDENT) advance()

            // Check for Property (key:: value)
            val property = tryParseProperty()
            if (property != null) {
                properties[property.first] = property.second
                // Consume newline after property
                if (currentToken.type == TokenType.NEWLINE) advance()
            } else {
                // Continuation text
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

    private fun parseBlockquote(_level: Int): BlockquoteBlockNode {
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
        return BlockquoteBlockNode(children = innerBlocks)
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
     * break, or GFM table starting at [currentToken]. Used both at the top level and
     * (after bullet-token consumption) for the same constructs decorating a bullet's
     * content — see the call sites in [parseBlock]. Returns null and leaves the token
     * stream untouched if none of these constructs match.
     */
    private fun tryConsumeNonHeadingConstruct(level: Int): BlockNode? {
        // Fenced code block: ```
        if (currentToken.type == TokenType.BACKTICK) {
            val fenceLen = currentToken.end - currentToken.start
            if (fenceLen >= 3) {
                return parseFencedCodeBlock(TokenType.BACKTICK)
            }
        }

        // Fenced code block: ~~~
        if (currentToken.type == TokenType.TILDE) {
            val fenceLen = currentToken.end - currentToken.start
            if (fenceLen >= 3) {
                return parseFencedCodeBlock(TokenType.TILDE)
            }
        }

        // Thematic break from TEXT token: --- or ___
        if (currentToken.type == TokenType.TEXT) {
            val text = currentToken.text(source).toString()
            if (text.matches(THEMATIC_BREAK_REGEX)) {
                val next = peekToken(1)
                if (next.type == TokenType.NEWLINE || next.type == TokenType.EOF) {
                    advance() // consume ---
                    if (currentToken.type == TokenType.NEWLINE) advance()
                    return ThematicBreakBlockNode()
                }
            }
        }

        // Thematic break from STAR token: ***
        if (currentToken.type == TokenType.STAR) {
            val runLen = currentToken.end - currentToken.start
            if (runLen >= 3) {
                val next = peekToken(1)
                if (next.type == TokenType.NEWLINE || next.type == TokenType.EOF) {
                    advance() // consume ***
                    if (currentToken.type == TokenType.NEWLINE) advance()
                    return ThematicBreakBlockNode()
                }
            }
        }

        // Blockquote: > content
        if (currentToken.type == TokenType.R_ANGLE) {
            advance() // consume >
            if (currentToken.type == TokenType.WS) advance() // optional space
            return parseBlockquote(level)
        }

        // Ordered list: N. content
        if (currentToken.type == TokenType.TEXT) {
            val txt = currentToken.text(source).toString()
            val numDotMatch = ORDERED_LIST_EXTRACT_REGEX.find(txt)
            if (numDotMatch != null) {
                val peekNext = peekToken(1)
                if (peekNext.type == TokenType.WS || peekNext.type == TokenType.EOF || peekNext.type == TokenType.NEWLINE) {
                    val number = numDotMatch.groupValues[1].toInt()
                    advance() // consume "N."
                    if (currentToken.type == TokenType.WS) advance() // consume space
                    val contentStr = parseLine()
                    val children = parseBlocksAtLevel(level + 1)
                    return OrderedListItemBlockNode(
                        number = number,
                        content = listOf(TextNode(contentStr)),
                        children = children,
                        level = level
                    )
                }
            }
        }

        // GFM pipe table: starts with |
        if (currentToken.type == TokenType.PIPE) {
            val tableNode = tryParseTable()
            if (tableNode != null) return tableNode
        }

        return null
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
