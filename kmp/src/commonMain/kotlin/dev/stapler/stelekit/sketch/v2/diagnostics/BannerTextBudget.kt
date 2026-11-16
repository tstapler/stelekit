// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

/**
 * The text budget of one banner row, mirroring the `[params]` block of the chrome table in ux.md
 * (`ChromeBudgetPolicyTest` asserts these constants equal what the generated chrome fixture computes, so they
 * cannot drift). The budget is evaluated at the tightest supported window, 320 dp wide.
 * Pure: no `UI2` import, so `RT` producers can check strings before any composable exists.
 */
object BannerTextBudget {
    const val BANNER_LINES = 2
    const val CHAR_DP = 7
    const val GUTTER_DP = 16
    const val PRIMARY_DP = 88
    const val MORE_DP = 44
    const val ICON_DP = 24
    const val GAP_DP = 8
    const val BUTTON_PAD_DP = 4
    const val NARROWEST_WIDTH_DP = 320
    const val LABEL_WORDS = 2

    /** Characters per line of a summary or short title at [widthDp] and [fontScale] (floor of the real-valued share). */
    fun charsPerLine(widthDp: Int, fontScale: Double): Int {
        val available = widthDp - 2 * GUTTER_DP - PRIMARY_DP - MORE_DP - (ICON_DP + 3 * GAP_DP)
        return (available / (CHAR_DP * fontScale)).toInt()
    }

    /** Summary line width: 320 dp at font scale 1.0. */
    val summaryCharsPerLine: Int get() = charsPerLine(NARROWEST_WIDTH_DP, 1.0)

    /** Short-title line width: 320 dp at font scale 2.0. */
    val shortTitleCharsPerLine: Int get() = charsPerLine(NARROWEST_WIDTH_DP, 2.0)

    /** Characters of a primary label that fit the button at font scale 1.0. */
    val labelChars: Int get() = (PRIMARY_DP - 2 * BUTTON_PAD_DP) / CHAR_DP

    /** Characters of the longest label word that fits the button at font scale 2.0. */
    val labelWordChars: Int get() = (PRIMARY_DP - 2 * BUTTON_PAD_DP) / (CHAR_DP * 2)

    /**
     * Number of lines [text] takes by greedy word wrap at [perLine] characters, or null when a piece is wider than a
     * line. A word breaks only after a hyphen (the same rule as check-plan.py).
     */
    fun wrapLines(text: String, perLine: Int): Int? {
        var lines = 1
        var current = 0
        var first = true
        for (word in text.split(' ', '\t', '\n').filter { it.isNotEmpty() }) {
            for ((index, piece) in hyphenPieces(word).withIndex()) {
                if (piece.length > perLine) return null
                val separator = if (index > 0 || first) 0 else 1
                if (current + separator + piece.length <= perLine) {
                    current += separator + piece.length
                } else {
                    lines++
                    current = piece.length
                }
                first = false
            }
        }
        return lines
    }

    private fun hyphenPieces(word: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        for (i in word.indices) {
            if (word[i] == '-') {
                pieces += word.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < word.length) pieces += word.substring(start)
        return pieces
    }
}
