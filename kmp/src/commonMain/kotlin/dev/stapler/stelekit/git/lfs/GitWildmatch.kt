// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/**
 * Kotlin port of git's `wildmatch.c` (`dowild`), the matcher behind gitignore and gitattributes
 * patterns. With [pathname] set (git's `WM_PATHNAME`), `*` and `?` never cross a `/` and the `**`
 * forms (`**` followed by slash, slash followed by `**`, and slash-`**`-slash) span directories.
 * Kept free of regex so it behaves identically on every Kotlin target.
 */
internal object GitWildmatch {
    private const val MATCH = 0
    private const val NOMATCH = 1
    private const val ABORT_ALL = -1
    private const val ABORT_TO_STARSTAR = -2
    private const val NUL = '\u0000'

    fun matches(pattern: String, text: String, pathname: Boolean): Boolean =
        Matcher(pattern, text, pathname).run(0, 0) == MATCH

    private class Matcher(val p: String, val t: String, val pathname: Boolean) {
        fun pc(i: Int): Char = if (i < p.length) p[i] else NUL
        fun tc(i: Int): Char = if (i < t.length) t[i] else NUL

        fun run(pStart: Int, tStart: Int): Int {
            var pi = pStart
            var ti = tStart
            while (pi < p.length) {
                val pCh = p[pi]
                if (ti >= t.length && pCh != '*') return ABORT_ALL
                val tCh = tc(ti)
                when (pCh) {
                    '\\' -> {
                        pi++
                        if (tCh != pc(pi)) return NOMATCH
                    }
                    '?' -> if (pathname && tCh == '/') return NOMATCH
                    '*' -> {
                        val step = star(pi, ti)
                        if (step.result != null) return step.result
                        pi = step.pi
                        ti = step.ti
                    }
                    '[' -> {
                        val bracket = bracket(pi, tCh)
                        if (bracket.result != null) return bracket.result
                        pi = bracket.pi
                    }
                    else -> if (tCh != pCh) return NOMATCH
                }
                pi++
                ti++
            }
            return if (ti < t.length) NOMATCH else MATCH
        }

        /** Outcome of a `*` step: either a final [result], or the (pi, ti) to resume the main loop at. */
        class Step(val result: Int?, val pi: Int = 0, val ti: Int = 0)

        private fun star(piAtStar: Int, tiStart: Int): Step {
            var pi = piAtStar + 1
            var ti = tiStart
            val matchSlash: Boolean
            if (pc(pi) == '*') {
                val prev = piAtStar - 1
                while (pc(pi) == '*') pi++
                if (!pathname) {
                    matchSlash = true
                } else if ((prev < 0 || p[prev] == '/') && isDirBoundary(pi)) {
                    if (pc(pi) == '/' && run(pi + 1, ti) == MATCH) return Step(MATCH)
                    matchSlash = true
                } else {
                    matchSlash = false
                }
            } else {
                matchSlash = !pathname
            }
            if (pi >= p.length) {
                return Step(if (!matchSlash && t.indexOf('/', ti) >= 0) NOMATCH else MATCH)
            }
            if (!matchSlash && p[pi] == '/') {
                val slash = t.indexOf('/', ti)
                if (slash < 0) return Step(ABORT_ALL)
                // The shared slash is consumed by the caller's pi++/ti++.
                return Step(null, pi, slash)
            }
            return Step(starTail(pi, ti, matchSlash))
        }

        private fun isDirBoundary(pi: Int): Boolean =
            pi >= p.length || p[pi] == '/' || (p[pi] == '\\' && pc(pi + 1) == '/')

        private fun starTail(pi: Int, tiStart: Int, matchSlash: Boolean): Int {
            var ti = tiStart
            while (true) {
                var tCh = tc(ti)
                if (tCh == NUL) break
                if (!isGlobSpecial(p[pi])) {
                    val pCh = p[pi]
                    while (true) {
                        tCh = tc(ti)
                        if (tCh == NUL || (!matchSlash && tCh == '/')) break
                        if (tCh == pCh) break
                        ti++
                    }
                    if (tCh != pCh) return NOMATCH
                }
                val matched = run(pi, ti)
                if (matched != NOMATCH) {
                    if (!matchSlash || matched != ABORT_TO_STARSTAR) return matched
                } else if (!matchSlash && tCh == '/') {
                    return ABORT_TO_STARSTAR
                }
                ti++
            }
            return ABORT_ALL
        }

        /** A '-' inside a bracket expression is a range operator only when a real end character follows it. */
        private fun isRangeEnd(next: Char) = next != NUL && next != ']'

        private fun isGlobSpecial(c: Char) = c == '*' || c == '?' || c == '[' || c == '\\'

        class BracketStep(val result: Int?, val pi: Int = 0)

        @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        private fun bracket(piAtOpen: Int, tCh: Char): BracketStep {
            var pi = piAtOpen + 1
            var pCh = pc(pi)
            if (pCh == '^') pCh = '!'
            val negated = pCh == '!'
            if (negated) {
                pi++
                pCh = pc(pi)
            }
            var prevCh = NUL
            var matched = false
            do {
                if (pCh == NUL) return BracketStep(ABORT_ALL)
                if (pCh == '\\') {
                    pi++
                    pCh = pc(pi)
                    if (pCh == NUL) return BracketStep(ABORT_ALL)
                    if (tCh == pCh) matched = true
                } else if (pCh == '-' && prevCh != NUL && isRangeEnd(pc(pi + 1))) {
                    pi++
                    pCh = pc(pi)
                    if (pCh == '\\') {
                        pi++
                        pCh = pc(pi)
                        if (pCh == NUL) return BracketStep(ABORT_ALL)
                    }
                    if (tCh <= pCh && tCh >= prevCh) matched = true
                    pCh = NUL
                } else if (pCh == '[' && pc(pi + 1) == ':') {
                    val classStart = pi + 2
                    var end = classStart
                    while (pc(end) != NUL && pc(end) != ']') end++
                    if (pc(end) == NUL) return BracketStep(ABORT_ALL)
                    if (end - classStart - 1 < 0 || p[end - 1] != ':') {
                        // No ":]": treat the '[' as an ordinary set member.
                        pi = classStart - 2
                        pCh = '['
                        if (tCh == pCh) matched = true
                    } else {
                        val hit = classMatches(p.substring(classStart, end - 1), tCh) ?: return BracketStep(ABORT_ALL)
                        if (hit) matched = true
                        pi = end
                        pCh = NUL
                    }
                } else if (tCh == pCh) {
                    matched = true
                }
                prevCh = pCh
                pi++
                pCh = pc(pi)
            } while (pCh != ']')
            if (matched == negated || (pathname && tCh == '/')) return BracketStep(NOMATCH)
            return BracketStep(null, pi)
        }

        private fun classMatches(name: String, c: Char): Boolean? = when (name) {
            "alnum" -> c.isAsciiLetterOrDigit()
            "alpha" -> c in 'a'..'z' || c in 'A'..'Z'
            "blank" -> c == ' ' || c == '\t'
            "cntrl" -> c.code < 32 || c.code == 127
            "digit" -> c in '0'..'9'
            "graph" -> c.code in 33..126
            "lower" -> c in 'a'..'z'
            "print" -> c.code in 32..126
            "punct" -> c.code in 33..126 && !c.isAsciiLetterOrDigit()
            "space" -> c == ' ' || c in '\t'..'\r'
            "upper" -> c in 'A'..'Z'
            "xdigit" -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            else -> null
        }

        private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
