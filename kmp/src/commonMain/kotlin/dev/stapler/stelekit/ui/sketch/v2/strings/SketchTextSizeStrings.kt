// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The words of the text-size controls: the A-/A+ chips, the Style tab's size field and the badge shown while a text is scaled. */
object SketchTextSizeStrings {
    const val LABEL = "Text size"
    const val SMALLER = "A-"
    const val LARGER = "A+"
    const val SMALLER_NAME = "Smaller text"
    const val LARGER_NAME = "Larger text"
    const val TOAST_KEY = "text-size"

    fun size(text: String) = "Text size $text"

    fun tooSmall(min: String) = "Text can't go smaller than $min"

    fun tooLarge(max: String) = "Text can't go larger than $max"

    fun invalid(min: String, max: String) = "Type a size from $min to $max"
}
