// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The words of the tours, Help and search (part of the string table, so the hardcoded-string audit does not scan it). */
object SketchGuideStrings {
    const val BACK = "Back"
    const val NEXT = "Next"
    const val DONE = "Done"
    const val SKIP = "Skip tour"
    const val DONT_SHOW = "Don't show tips"

    fun indicator(step: Int, total: Int) = "Step $step of $total"
}
