// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Splits a console line into tokens. Whitespace separates tokens; `'...'` is literal; inside `"..."`
 * a backslash escapes `\` and `"`; outside quotes a backslash escapes the next character. `""` is an
 * empty token. [quote] is the inverse: `tokenize(tokens.joinToString(" ") { quote(it) }) == tokens`.
 */
object CommandLineTokenizer {
    private const val DQ = '"'
    private const val SQ = '\''
    private const val BACKSLASH = '\\'

    fun tokenize(line: String): Either<ConsoleError.Usage, List<String>> {
        val scanner = Scanner(line)
        while (scanner.hasNext()) {
            scanner.step()?.let { return it.left() }
        }
        return scanner.finish().right()
    }

    fun quote(token: String): String =
        DQ + token.replace("\\", "\\\\").replace("\"", "\\\"") + DQ

    private class Scanner(private val line: String) {
        private val tokens = mutableListOf<String>()
        private val current = StringBuilder()
        private var inToken = false
        private var i = 0

        fun hasNext() = i < line.length

        fun finish(): List<String> {
            endToken()
            return tokens
        }

        /** Consumes one unit of input; returns an error if the line is malformed there. */
        fun step(): ConsoleError.Usage? {
            val c = line[i]
            return when {
                c == SQ -> singleQuoted()
                c == DQ -> doubleQuoted()
                c == BACKSLASH -> escaped()
                c.isWhitespace() -> { endToken(); i++; null }
                else -> { append(c); i++; null }
            }
        }

        private fun append(c: Char) {
            current.append(c)
            inToken = true
        }

        private fun endToken() {
            if (!inToken) return
            tokens += current.toString()
            current.clear()
            inToken = false
        }

        private fun singleQuoted(): ConsoleError.Usage? {
            val end = line.indexOf(SQ, i + 1)
            if (end < 0) return ConsoleError.Usage("Unterminated ' quote")
            current.append(line, i + 1, end)
            inToken = true
            i = end + 1
            return null
        }

        private fun doubleQuoted(): ConsoleError.Usage? {
            i++
            while (i < line.length && line[i] != DQ) {
                val escapes = line[i] == BACKSLASH && i + 1 < line.length && line[i + 1] in "\\\""
                if (escapes) i++
                current.append(line[i])
                i++
            }
            if (i >= line.length) return ConsoleError.Usage("Unterminated \" quote")
            inToken = true
            i++
            return null
        }

        private fun escaped(): ConsoleError.Usage? {
            if (i + 1 >= line.length) return ConsoleError.Usage("Line ends with a backslash")
            append(line[i + 1])
            i += 2
            return null
        }
    }
}
