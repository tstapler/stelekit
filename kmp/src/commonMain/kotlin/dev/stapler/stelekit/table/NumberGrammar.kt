package dev.stapler.stelekit.table

/** Locale-independent number syntax from ADR-002. Hand-scanned so `NaN`, `Infinity`, hex and `1d` never match. */
object NumberGrammar {
    /** Null when [raw] (surrounding whitespace ignored) is not a number. */
    fun parse(raw: String): Double? {
        val s = raw.trim()
        val n = s.length
        var i = 0
        val clean = StringBuilder()
        if (i < n && (s[i] == '+' || s[i] == '-')) {
            if (s[i] == '-') clean.append('-')
            i++
        }
        val intStart = i
        while (i < n && (s[i].isAsciiDigit() || s[i] == ',')) i++
        val intDigits = stripThousands(s.substring(intStart, i)) ?: return null
        clean.append(if (intDigits.isEmpty() && i < n && s[i] == '.') "0" else intDigits)
        var fracDigits = 0
        if (i < n && s[i] == '.') {
            clean.append('.')
            i++
            val fracStart = i
            while (i < n && s[i].isAsciiDigit()) i++
            fracDigits = i - fracStart
            clean.append(if (fracDigits == 0) "0" else s.substring(fracStart, i))
        }
        if (intDigits.length + fracDigits == 0) return null
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            val expStart = j
            if (j < n && (s[j] == '+' || s[j] == '-')) j++
            val digitsStart = j
            while (j < n && s[j].isAsciiDigit()) j++
            if (j == digitsStart) return null
            clean.append('e').append(s, expStart, j)
            i = j
        }
        val percent = i < n && s[i] == '%'
        if (percent) i++
        if (i != n) return null
        val v = clean.toString().toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return if (percent) v / 100.0 else v
    }

    /** `1,234,567` -> `1234567`; null unless the first group has 1-3 digits and every later group exactly 3. */
    private fun stripThousands(token: String): String? {
        if (!token.contains(',')) return token
        val groups = token.split(',')
        if (groups[0].length !in 1..3) return null
        if (groups.drop(1).any { it.length != 3 }) return null
        return groups.joinToString("")
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
