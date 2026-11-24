package dev.stapler.stelekit.table.formula

sealed interface Token {
    val start: Int
    val end: Int

    data class Number(val value: Double, override val start: Int, override val end: Int) : Token
    data class Text(val value: String, override val start: Int, override val end: Int) : Token
    data class CellRef(
        val col: Int,
        val row: Int,
        val absCol: Boolean,
        val absRow: Boolean,
        override val start: Int,
        override val end: Int,
    ) : Token

    /** A bare name; a function when followed by `(`. */
    data class Ident(val name: String, override val start: Int, override val end: Int) : Token

    data class RefErr(override val start: Int, override val end: Int) : Token
    data class Op(val symbol: String, override val start: Int, override val end: Int) : Token

    /** Unlexable input (unterminated string, stray character); the parser turns it into `#PARSE!`. */
    data class Invalid(override val start: Int, override val end: Int) : Token
    data class End(override val start: Int, override val end: Int) : Token
}

/** Pure single-pass lexer. Never throws; bad input becomes [Token.Invalid]. */
object Tokenizer {
    const val MAX_COLUMN = 702 // A..ZZ

    fun tokenize(text: String, from: Int = 0): List<Token> {
        val out = ArrayList<Token>()
        var i = from
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            val tok = when {
                c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit()) -> number(text, i)
                c == '"' -> string(text, i)
                c == '#' -> refError(text, i)
                c == '$' || c.isAsciiLetter() || c == '_' -> word(text, i)
                else -> operator(text, i)
            }
            out.add(tok)
            i = tok.end
        }
        out.add(Token.End(n, n))
        return out
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun number(text: String, start: Int): Token {
        var i = start
        while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
            if (j < text.length && text[j].isDigit()) {
                while (j < text.length && text[j].isDigit()) j++
                i = j
            }
        }
        val value = text.substring(start, i).toDoubleOrNull()
        return if (value == null || value.isNaN() || value.isInfinite()) Token.Invalid(start, i) else Token.Number(value, start, i)
    }

    private fun string(text: String, start: Int): Token {
        val sb = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            if (text[i] != '"') {
                sb.append(text[i++])
            } else if (i + 1 < text.length && text[i + 1] == '"') {
                sb.append('"')
                i += 2
            } else {
                return Token.Text(sb.toString(), start, i + 1)
            }
        }
        return Token.Invalid(start, text.length)
    }

    private fun refError(text: String, start: Int): Token =
        if (text.regionMatches(start, "#REF!", 0, 5, ignoreCase = true)) Token.RefErr(start, start + 5)
        else Token.Invalid(start, start + 1)

    private fun word(text: String, start: Int): Token {
        cellRef(text, start)?.let { return it }
        if (text[start] == '$') return Token.Invalid(start, start + 1)
        var i = start
        while (i < text.length && (text[i].isAsciiLetter() || text[i].isDigit() || text[i] == '_' || text[i] == '.')) i++
        return Token.Ident(text.substring(start, i), start, i)
    }

    /** `A1`, `$A$1`, `a1`; not when glued to more name characters or a `(` (so `LOG10(` is a name). */
    private fun cellRef(text: String, start: Int): Token.CellRef? {
        var i = start
        val absCol = text[i] == '$'
        if (absCol) i++
        val colStart = i
        while (i < text.length && text[i].isAsciiLetter()) i++
        val letters = i - colStart
        if (letters == 0 || letters > 2) return null
        val absRow = i < text.length && text[i] == '$'
        if (absRow) i++
        val rowStart = i
        while (i < text.length && text[i].isDigit()) i++
        if (i == rowStart || i - rowStart > 9) return null
        if (i < text.length && (text[i].isAsciiLetter() || text[i] == '_' || text[i] == '.' || text[i] == '(')) return null
        val row = text.substring(rowStart, i).toInt()
        if (row < 1) return null
        var col = 0
        for (k in colStart until colStart + letters) col = col * 26 + (text[k].uppercaseChar() - 'A' + 1)
        if (col > MAX_COLUMN) return null
        return Token.CellRef(col, row, absCol, absRow, start, i)
    }

    private fun operator(text: String, start: Int): Token {
        val two = if (start + 1 < text.length) text.substring(start, start + 2) else ""
        if (two == "<>" || two == "<=" || two == ">=") return Token.Op(two, start, start + 2)
        val one = text[start]
        return if (one in "+-*/^%=<>(),:") Token.Op(one.toString(), start, start + 1) else Token.Invalid(start, start + 1)
    }
}
