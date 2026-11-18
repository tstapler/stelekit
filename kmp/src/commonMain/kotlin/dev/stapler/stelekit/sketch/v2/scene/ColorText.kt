// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

/**
 * The one filter every colour string from a file passes before it reaches an [ElementStyle]: at most 64 characters of
 * letters, digits and `#(),.% -`, so a hostile value such as `url(https://evil/x.svg#a)` never reaches an SVG attribute
 * or a painter. Anything else becomes the default.
 */
object ColorText {
    private const val MAX_CHARS = 64
    private const val PUNCTUATION = "#(),.% -"

    fun clean(css: String?, default: String): String = css?.takeIf { it.length <= MAX_CHARS && it.all(::isColorChar) } ?: default

    private fun isColorChar(c: Char): Boolean = c.isLetterOrDigit() || c in PUNCTUATION
}
