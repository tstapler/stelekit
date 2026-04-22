package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*
import dev.stapler.stelekit.parsing.lexer.*

class InlineParser(private val source: CharSequence) {
    private val lexer = Lexer(source)
    private var currentToken = lexer.nextToken()

    fun parse(): List<InlineNode> {
        val nodes = mutableListOf<InlineNode>()
        while (currentToken.type != TokenType.EOF) {
            val node = parseExpression(0)
            // Hard line break: trailing two spaces + newline
            if (node is TextNode && node.content == "\n") {
                val prev = nodes.lastOrNull()
                if (prev is TextNode && prev.content.endsWith("  ")) {
                    // Single node with trailing two spaces
                    nodes[nodes.lastIndex] = TextNode(prev.content.dropLast(2))
                    nodes.add(HardBreakNode)
                    continue
                } else if (nodes.size >= 2 && prev is TextNode && prev.content.isBlank()) {
                    // Previous node is whitespace — check if second-to-last also has trailing space
                    val secondPrev = nodes[nodes.size - 2]
                    if (secondPrev is TextNode && secondPrev.content.endsWith(" ")) {
                        // Remove trailing space from secondPrev and remove the space-only prev node
                        nodes[nodes.size - 2] = TextNode(secondPrev.content.dropLast(1))
                        nodes.removeAt(nodes.lastIndex)
                        nodes.add(HardBreakNode)
                        continue
                    } else if (secondPrev is TextNode && secondPrev.content.isBlank()) {
                        // Both prev nodes are blank spaces — that counts as trailing two spaces
                        nodes.removeAt(nodes.lastIndex)
                        nodes.removeAt(nodes.lastIndex)
                        nodes.add(HardBreakNode)
                        continue
                    }
                }
            }
            nodes.add(node)
        }
        return nodes
    }

    private fun parseExpression(precedence: Int): InlineNode {
        var token = currentToken
        advance()

        var left = parsePrefix(token)

        while (precedence < currentPrecedence) {
            token = currentToken
            advance()
            left = parseInfix(left, token)
        }
        return left
    }

    private fun parsePrefix(token: Token): InlineNode {
        return when (token.type) {
            TokenType.TEXT -> parseTextOrTaskMarker(token)
            TokenType.WS, TokenType.COLON, TokenType.NEWLINE -> TextNode(token.text(source).toString())
            TokenType.L_BRACKET -> parseLink(token)
            TokenType.L_PAREN -> TextNode("(") // Was parseBlockRef, now handled by BLOCK_REF_OPEN
            TokenType.BLOCK_REF_OPEN -> parseBlockRef(token)
            TokenType.STAR -> parseEmphasis(token, TokenType.STAR)
            TokenType.UNDERSCORE -> parseEmphasis(token, TokenType.UNDERSCORE)
            TokenType.TILDE -> parseEmphasis(token, TokenType.TILDE)
            TokenType.BACKTICK -> parseCode(token)
            TokenType.HASH -> parseTag(token)
            TokenType.MACRO_OPEN -> parseMacro()
            TokenType.EQ -> {
                val len = token.end - token.start
                if (len >= 2) parseHighlight(token) else TextNode(token.text(source).toString())
            }
            TokenType.EXCLAMATION -> parseImage()
            TokenType.L_ANGLE -> parseAutoLink()
            TokenType.CARET -> {
                if (currentToken.type == TokenType.L_BRACE) parseSuperscript()
                else TextNode("^")
            }
            TokenType.BACKSLASH -> {
                if (currentToken.type == TokenType.NEWLINE) {
                    advance() // consume newline
                    HardBreakNode
                } else {
                    TextNode("\\")
                }
            }
            TokenType.PIPE, TokenType.R_ANGLE, TokenType.L_BRACE, TokenType.R_BRACE -> TextNode(token.text(source).toString())
            else -> TextNode(token.text(source).toString())
        }
    }

    private fun parseTextOrTaskMarker(token: Token): InlineNode {
        val text = token.text(source).toString()
        val taskMarkers = setOf("TODO", "DONE", "NOW", "LATER", "WAITING", "CANCELLED", "DOING", "WAIT", "STARTED")
        if (text in taskMarkers) {
            return TaskMarkerNode(text)
        }
        return TextNode(text)
    }

    private fun parseInfix(left: InlineNode, _token: Token): InlineNode {
        return left // Placeholder
    }

    private val currentPrecedence: Int = 0

    private fun parseMacro(): InlineNode {
        // Parse {{name args}}
        // currentToken is whatever comes after MACRO_OPEN
        val nameSb = StringBuilder()
        val argSb = StringBuilder()
        var nameCollected = false

        while (currentToken.type != TokenType.MACRO_CLOSE && currentToken.type != TokenType.EOF) {
            if (!nameCollected) {
                if (currentToken.type == TokenType.WS) {
                    // First WS after name means name is done
                    nameCollected = true
                    advance()
                } else {
                    nameSb.append(currentToken.text(source))
                    advance()
                }
            } else {
                argSb.append(currentToken.text(source))
                advance()
            }
        }
        if (currentToken.type == TokenType.MACRO_CLOSE) advance()

        val name = nameSb.toString()
        val args = argSb.toString()
        return if (args.isNotEmpty()) {
            MacroNode(name = name, arguments = listOf(args))
        } else {
            MacroNode(name = name, arguments = emptyList())
        }
    }

    private fun parseHighlight(_token: Token): InlineNode {
        // ==text==
        val savedLexerState = lexer.saveState()
        val savedToken = currentToken
        val children = mutableListOf<InlineNode>()

        while (currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            if (currentToken.type == TokenType.EQ) {
                val closeLen = currentToken.end - currentToken.start
                if (closeLen >= 2) {
                    advance() // consume closing ==
                    return HighlightNode(children)
                }
            }
            children.add(parseExpression(0))
        }

        // No closing == found — backtrack
        lexer.restoreState(savedLexerState)
        currentToken = savedToken
        return TextNode("==")
    }

    private fun parseImage(): InlineNode {
        // ![alt](url)
        val savedLexerState = lexer.saveState()
        val savedToken = currentToken

        if (currentToken.type != TokenType.L_BRACKET) {
            return TextNode("!")
        }
        advance() // consume [

        val altSb = StringBuilder()
        while (currentToken.type != TokenType.R_BRACKET && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            altSb.append(currentToken.text(source))
            advance()
        }
        if (currentToken.type != TokenType.R_BRACKET) {
            lexer.restoreState(savedLexerState)
            currentToken = savedToken
            return TextNode("!")
        }
        advance() // consume ]

        if (currentToken.type != TokenType.L_PAREN) {
            lexer.restoreState(savedLexerState)
            currentToken = savedToken
            return TextNode("!")
        }
        advance() // consume (

        val urlSb = StringBuilder()
        while (currentToken.type != TokenType.R_PAREN && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            urlSb.append(currentToken.text(source))
            advance()
        }
        if (currentToken.type != TokenType.R_PAREN) {
            lexer.restoreState(savedLexerState)
            currentToken = savedToken
            return TextNode("!")
        }
        advance() // consume )

        return ImageNode(alt = altSb.toString(), url = urlSb.toString())
    }

    private fun parseAutoLink(): InlineNode {
        // <url>
        val savedLexerState = lexer.saveState()
        val savedToken = currentToken

        val urlSb = StringBuilder()
        while (currentToken.type != TokenType.R_ANGLE && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            urlSb.append(currentToken.text(source))
            advance()
        }
        if (currentToken.type != TokenType.R_ANGLE) {
            lexer.restoreState(savedLexerState)
            currentToken = savedToken
            return TextNode("<")
        }
        advance() // consume >

        val url = urlSb.toString()
        return UrlLinkNode(url = url, text = listOf(TextNode(url)))
    }

    private fun parseSuperscript(): InlineNode {
        // ^{text} — currentToken is L_BRACE
        advance() // consume {
        val children = mutableListOf<InlineNode>()
        while (currentToken.type != TokenType.R_BRACE && currentToken.type != TokenType.EOF) {
            children.add(parseExpression(0))
        }
        if (currentToken.type == TokenType.R_BRACE) advance()
        return SuperscriptNode(children)
    }

    private fun parseLink(_token: Token): InlineNode {
        // Handle [[WikiLink]] or [label](url)
        if (currentToken.type == TokenType.L_BRACKET) {
            // [[ detected — wiki-link path
            val savedLexerState = lexer.saveState()
            val savedToken = currentToken
            advance() // consume second [
            val sb = StringBuilder()
            while (currentToken.type != TokenType.R_BRACKET && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
                sb.append(currentToken.text(source))
                advance()
            }
            if (currentToken.type == TokenType.R_BRACKET) advance() // first ]
            if (currentToken.type == TokenType.R_BRACKET) {
                advance() // second ]
                // Split on | for alias: [[page|alias]]
                val raw = sb.toString()
                val pipeIdx = raw.indexOf('|')
                return if (pipeIdx >= 0) {
                    WikiLinkNode(target = raw.substring(0, pipeIdx), alias = raw.substring(pipeIdx + 1))
                } else {
                    WikiLinkNode(target = raw, alias = null)
                }
            }
            // No closing ]] — backtrack, treat [[ as plain text
            lexer.restoreState(savedLexerState)
            currentToken = savedToken
            return TextNode("[")
        }

        // Check for [#A] priority shorthand
        if (currentToken.type == TokenType.HASH) {
            val savedState2 = lexer.saveState()
            val savedTok2 = currentToken
            advance() // consume #
            if (currentToken.type == TokenType.TEXT) {
                val ptext = currentToken.text(source).toString()
                if (ptext.length == 1 && ptext[0] in 'A'..'C') {
                    advance() // consume priority letter
                    if (currentToken.type == TokenType.R_BRACKET) {
                        advance() // consume ]
                        return PriorityNode(ptext)
                    }
                }
            }
            // Backtrack
            lexer.restoreState(savedState2)
            currentToken = savedTok2
        }

        // Plain [ — try to parse [label](url)
        val labelSaved = lexer.saveState()
        val labelToken = currentToken
        val labelSb = StringBuilder()
        val labelNodes = mutableListOf<InlineNode>()
        while (currentToken.type != TokenType.R_BRACKET && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            labelSb.append(currentToken.text(source))
            val t = currentToken
            advance()
            labelNodes.add(parsePrefix(t))
        }
        if (currentToken.type == TokenType.R_BRACKET) {
            advance() // consume ]
            if (currentToken.type == TokenType.L_PAREN) {
                advance() // consume (
                val urlSb = StringBuilder()
                while (currentToken.type != TokenType.R_PAREN && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
                    urlSb.append(currentToken.text(source))
                    advance()
                }
                if (currentToken.type == TokenType.R_PAREN) {
                    advance() // consume )
                    return UrlLinkNode(url = urlSb.toString(), text = labelNodes)
                }
            }
        }
        // No match — backtrack
        lexer.restoreState(labelSaved)
        currentToken = labelToken
        return TextNode("[")
    }

    private fun parseBlockRef(_token: Token): InlineNode {
        // Token is BLOCK_REF_OPEN ((
        val sb = StringBuilder()
        while (currentToken.type != TokenType.BLOCK_REF_CLOSE && currentToken.type != TokenType.EOF) {
            sb.append(currentToken.text(source))
            advance()
        }
        // Consume BLOCK_REF_CLOSE ))
        if (currentToken.type == TokenType.BLOCK_REF_CLOSE) advance()

        return BlockRefNode(sb.toString())
    }

    private fun parseEmphasis(token: Token, type: TokenType): InlineNode {
        val len = token.end - token.start

        val isBold = (type == TokenType.STAR || type == TokenType.UNDERSCORE) && len >= 2
        val isStrike = (type == TokenType.TILDE) && len >= 2

        // Single ~ — check for ~{text} subscript, otherwise plain text
        if (type == TokenType.TILDE && len == 1) {
            if (currentToken.type == TokenType.L_BRACE) {
                advance() // consume {
                val children = mutableListOf<InlineNode>()
                while (currentToken.type != TokenType.R_BRACE && currentToken.type != TokenType.EOF) {
                    children.add(parseExpression(0))
                }
                if (currentToken.type == TokenType.R_BRACE) advance()
                return SubscriptNode(children)
            }
            return TextNode(token.text(source).toString())
        }

        // Left-flanking rule for underscore: _ preceded by word char is NOT emphasis
        if (type == TokenType.UNDERSCORE) {
            val charBefore = if (token.start > 0) source[token.start - 1] else ' '
            if (charBefore.isLetterOrDigit()) {
                return TextNode(token.text(source).toString())
            }
        }

        // Save lexer state so we can backtrack if no closing delimiter is found.
        val savedLexerState = lexer.saveState()
        val savedToken = currentToken

        val children = mutableListOf<InlineNode>()

        // Loop until we find a matching closing token
        while (currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            if (currentToken.type == type) {
                val closeLen = currentToken.end - currentToken.start
                if (closeLen == len) {
                    // Match found
                    advance() // consume closing

                    return when {
                        len == 3 && type == TokenType.STAR -> ItalicNode(listOf(BoldNode(children)))
                        isBold -> BoldNode(children)
                        isStrike -> StrikeNode(children)
                        else -> ItalicNode(children)
                    }
                }
            }

            children.add(parseExpression(0))
        }

        // No closing token found — restore state and treat the opening marker as plain text.
        // This prevents silently dropping the content that was consumed while searching.
        lexer.restoreState(savedLexerState)
        currentToken = savedToken
        return TextNode(token.text(source).toString())
    }

    private fun parseCode(token: Token): InlineNode {
        // `code` — backtrack if no closing backtick found
        val savedLexerState = lexer.saveState()
        val savedToken = currentToken
        val sb = StringBuilder()
        while (currentToken.type != TokenType.BACKTICK && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
            sb.append(currentToken.text(source))
            advance()
        }
        if (currentToken.type == TokenType.BACKTICK) {
            advance() // consume closing backtick
            return CodeNode(sb.toString())
        }
        // No closing backtick — backtrack and return plain text
        lexer.restoreState(savedLexerState)
        currentToken = savedToken
        return TextNode(token.text(source).toString())
    }

    private fun parseTag(_token: Token): InlineNode {
        // #[[multi word tag]] bracket form
        if (currentToken.type == TokenType.L_BRACKET) {
            val savedLexerState = lexer.saveState()
            val savedToken = currentToken
            advance() // consume first [
            if (currentToken.type != TokenType.L_BRACKET) {
                // Not [[, restore and fall through
                lexer.restoreState(savedLexerState)
                currentToken = savedToken
            } else {
                advance() // consume second [
                val sb = StringBuilder()
                while (currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
                    if (currentToken.type == TokenType.R_BRACKET) {
                        val firstCloseToken = currentToken
                        advance()
                        if (currentToken.type == TokenType.R_BRACKET) {
                            advance() // consume second ]
                            return TagNode(sb.toString())
                        }
                        // Only one ], not a closing ]], keep accumulating
                        sb.append(firstCloseToken.text(source))
                        // currentToken is already the token after the single ]
                        continue
                    }
                    sb.append(currentToken.text(source))
                    advance()
                }
                // EOF/NEWLINE before closing ]] — restore and return TextNode("#")
                lexer.restoreState(savedLexerState)
                currentToken = savedToken
                return TextNode("#")
            }
        }

        // #tag - only valid if immediately followed by text
        if (currentToken.type == TokenType.TEXT) {
            val rawTag = currentToken.text(source).toString()
            val terminators = setOf(',', '.', '!', '?', '"', ';')
            val endIdx = rawTag.indexOfFirst { it in terminators }
            if (endIdx > 0) {
                // Truncate at terminator: restore lexer to position after valid tag part
                val textToken = currentToken
                val splitCursor = textToken.start + endIdx
                lexer.restoreState(Lexer.State(splitCursor, false))
                currentToken = lexer.nextToken()
                return TagNode(rawTag.substring(0, endIdx))
            }
            advance()
            return TagNode(rawTag)
        }
        // Fallback: just a hash
        return TextNode("#")
    }

    private fun advance() {
        currentToken = lexer.nextToken()
    }
}
