package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*
import dev.stapler.stelekit.parsing.lexer.*

class BlockParser(private val source: CharSequence) {
    private val lexer = Lexer(source)
    private var currentToken = lexer.nextToken()

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
        if (currentToken.type == TokenType.HASH) {
            val hashLen = currentToken.end - currentToken.start
            val headingLevel = hashLen.coerceIn(1, 6)
            // Valid heading: hash run followed by WS, NEWLINE, or EOF
            // Also reject if hashLen > 6 (e.g. ####### is not a heading)
            if (hashLen <= 6) {
                val next = peekToken(1)
                if (next.type == TokenType.WS || next.type == TokenType.NEWLINE || next.type == TokenType.EOF) {
                    advance() // consume HASH run
                    if (currentToken.type == TokenType.WS) advance() // consume space
                    val contentStr = parseLine()
                    // Strip optional trailing # sequence and whitespace
                    val stripped = contentStr.trimEnd('#').trimEnd()
                    return HeadingBlockNode(
                        level = headingLevel,
                        content = listOf(TextNode(stripped))
                    )
                }
            }
        }

        // 1b. Check for fenced code block: ``` or ~~~
        if (currentToken.type == TokenType.BACKTICK) {
            val fenceLen = currentToken.end - currentToken.start
            if (fenceLen >= 3) {
                advance() // consume opening ```
                // Optional language identifier on same line
                val language = if (currentToken.type == TokenType.TEXT) {
                    val lang = currentToken.text(source).toString().trim()
                    parseLine() // consume rest of opening line (including lang token)
                    lang
                } else {
                    parseLine() // consume newline
                    null
                }
                // Collect body until matching ``` (same fence length) or EOF
                val body = StringBuilder()
                while (currentToken.type != TokenType.EOF) {
                    if (currentToken.type == TokenType.BACKTICK) {
                        val closeLen = currentToken.end - currentToken.start
                        if (closeLen >= 3) {
                            advance() // consume closing ```
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
        }

        // 1c. Check for tilde fenced code block: ~~~
        if (currentToken.type == TokenType.TILDE) {
            val fenceLen = currentToken.end - currentToken.start
            if (fenceLen >= 3) {
                advance() // consume opening ~~~
                val language = if (currentToken.type == TokenType.TEXT) {
                    val lang = currentToken.text(source).toString().trim()
                    parseLine()
                    lang
                } else {
                    parseLine()
                    null
                }
                val body = StringBuilder()
                while (currentToken.type != TokenType.EOF) {
                    if (currentToken.type == TokenType.TILDE) {
                        val closeLen = currentToken.end - currentToken.start
                        if (closeLen >= 3) {
                            advance()
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
                val rawContent = body.toString().trimEnd('\n')
                return CodeFenceBlockNode(language = language, rawContent = rawContent)
            }
        }

        // 1d. Check for thematic break from TEXT token: --- or ___
        if (currentToken.type == TokenType.TEXT) {
            val text = currentToken.text(source).toString()
            if (text.matches(Regex("---+|___+"))) {
                val next = peekToken(1)
                if (next.type == TokenType.NEWLINE || next.type == TokenType.EOF) {
                    advance() // consume ---
                    if (currentToken.type == TokenType.NEWLINE) advance()
                    return ThematicBreakBlockNode()
                }
            }
        }

        // 1e. Check for thematic break from STAR token: ***
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

        // 1f. Check for blockquote: > content
        if (currentToken.type == TokenType.R_ANGLE) {
            advance() // consume >
            if (currentToken.type == TokenType.WS) advance() // optional space
            return parseBlockquote(level)
        }

        // 1g. Check for ordered list: N. content
        if (currentToken.type == TokenType.TEXT) {
            val txt = currentToken.text(source).toString()
            val numDotMatch = Regex("^(\\d+)\\.$").find(txt)
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

        // 1h. Check for GFM pipe table: starts with |
        if (currentToken.type == TokenType.PIPE) {
            val tableNode = tryParseTable()
            if (tableNode != null) return tableNode
        }

        // 2. Check for Bullet
        val isBullet = if (currentToken.type == TokenType.BULLET) {
            advance()
            true
        } else {
            false
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

        val inlineContent = listOf(TextNode(contentBuilder.toString())) // Placeholder

        return if (isBullet) {
            BulletBlockNode(
                content = inlineContent,
                children = children,
                properties = properties,
                level = level
            )
        } else {
            ParagraphBlockNode(
                content = inlineContent,
                children = children,
                properties = properties
            )
        }
    }

    private fun parseBlockquote(level: Int): BlockquoteBlockNode {
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
                c.matches(Regex("-+")) -> null
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

    private fun peekIsBullet(): Boolean {
        if (currentToken.type == TokenType.BULLET) return true

        // Check for ordered list "N."
        if (currentToken.type == TokenType.TEXT) {
            val txt = currentToken.text(source).toString()
            if (txt.matches(Regex("\\d+\\."))) return true
        }

        if (currentToken.type == TokenType.INDENT) {
            val next = peekToken(1)
            if (next.type == TokenType.BULLET) return true
            if (next.type == TokenType.TEXT && next.text(source).toString().matches(Regex("\\d+\\."))) return true
        }
        return false
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

        for (i in 0 until offset) {
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
