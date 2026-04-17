package dev.stapler.stelekit.parsing.lexer

class Lexer(private val input: CharSequence) {
    private var cursor = 0
    private val length = input.length
    private var isStartOfLine = true

    fun nextToken(): Token {
        if (cursor >= length) {
            return Token(TokenType.EOF, cursor, cursor)
        }

        val start = cursor
        val char = input[cursor]

        // 1. Handle Indentation (only at start of line)
        if (isStartOfLine) {
            if (char == ' ' || char == '\t') {
                while (cursor < length && (input[cursor] == ' ' || input[cursor] == '\t')) {
                    cursor++
                }
                // isStartOfLine remains true because next token (Bullet) expects it?
                // Or rather, we just emitted INDENT, next is content.
                // Let's say INDENT is a distinct token, separate from WS.
                return Token(TokenType.INDENT, start, cursor)
            }
            // If not space/tab, we are still at start of line, but no indent.
            // Check for Bullet
            if (isBulletStart(char)) {
                // Check if it looks like a bullet "- " or "* "
                if (cursor + 1 < length && input[cursor + 1] == ' ') {
                    cursor += 2 // Consume "- "
                    isStartOfLine = false
                    return Token(TokenType.BULLET, start, cursor)
                }
            }
            // If we are here, we are at start of line but it's not indent or bullet.
            // Just normal tokens.
            isStartOfLine = false
        }

        // 2. Handle Newline
        if (char == '\n') {
            cursor++
            isStartOfLine = true
            return Token(TokenType.NEWLINE, start, cursor)
        }

        // 3. Handle Special Characters
        // Consuming runs of formatting characters
        when (char) {
            '*' -> return consumeRun(TokenType.STAR, start)
            '_' -> return consumeRun(TokenType.UNDERSCORE, start)
            '~' -> return consumeRun(TokenType.TILDE, start)
            '`' -> return consumeRun(TokenType.BACKTICK, start)
            '#' -> return consumeRun(TokenType.HASH, start)
            '=' -> return consumeRun(TokenType.EQ, start)
        }

        cursor++ // Default consume 1 for others
        return when (char) {
            '[' -> Token(TokenType.L_BRACKET, start, cursor)
            ']' -> Token(TokenType.R_BRACKET, start, cursor)
            '(' -> {
                if (cursor < length && input[cursor] == '(') {
                    cursor++
                    Token(TokenType.BLOCK_REF_OPEN, start, cursor)
                } else {
                    Token(TokenType.L_PAREN, start, cursor)
                }
            }
            ')' -> {
                if (cursor < length && input[cursor] == ')') {
                    cursor++
                    Token(TokenType.BLOCK_REF_CLOSE, start, cursor)
                } else {
                    Token(TokenType.R_PAREN, start, cursor)
                }
            }
            ':' -> Token(TokenType.COLON, start, cursor)
            '!' -> Token(TokenType.EXCLAMATION, start, cursor)
            '<' -> Token(TokenType.L_ANGLE, start, cursor)
            '>' -> Token(TokenType.R_ANGLE, start, cursor)
            '{' -> {
                if (cursor < length && input[cursor] == '{') {
                    cursor++
                    Token(TokenType.MACRO_OPEN, start, cursor)
                } else {
                    Token(TokenType.L_BRACE, start, cursor)
                }
            }
            '}' -> {
                if (cursor < length && input[cursor] == '}') {
                    cursor++
                    Token(TokenType.MACRO_CLOSE, start, cursor)
                } else {
                    Token(TokenType.R_BRACE, start, cursor)
                }
            }
            '^' -> Token(TokenType.CARET, start, cursor)
            '\\' -> Token(TokenType.BACKSLASH, start, cursor)
            '|' -> Token(TokenType.PIPE, start, cursor)
            ' ', '\t', '\r' -> Token(TokenType.WS, start, cursor) // WS not at start of line
            else -> {
                // Consuming consecutive text
                while (cursor < length) {
                    val c = input[cursor]
                    if (isSpecial(c)) break
                    cursor++
                }
                Token(TokenType.TEXT, start, cursor)
            }
        }
    }

    private fun consumeRun(type: TokenType, start: Int): Token {
        val char = input[start]
        var current = start
        while (current < length && input[current] == char) {
            current++
        }
        cursor = current
        isStartOfLine = false // Assuming formatting is not start of line structure (bullet)
        return Token(type, start, cursor)
    }

    private fun isBulletStart(c: Char): Boolean {
        return c == '-' || c == '*' || c == '+'
    }

    data class State(val cursor: Int, val isStartOfLine: Boolean)

    fun saveState(): State {
        return State(cursor, isStartOfLine)
    }

    fun restoreState(state: State) {
        cursor = state.cursor
        isStartOfLine = state.isStartOfLine
    }

    private fun isSpecial(c: Char): Boolean {
        return c == '\n' || c == '[' || c == ']' || c == '(' || c == ')' ||
               c == '*' || c == '_' || c == '~' || c == '`' || c == '#' || c == ':' ||
               c == ' ' || c == '\t' || c == '\r' ||
               c == '=' || c == '!' || c == '<' || c == '>' || c == '{' || c == '}' ||
               c == '^' || c == '\\' || c == '|'
    }
}
