// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** "Go to element..." (Story 3.6.5, UX U5): the dialog, its result rows and what is announced. */
object SketchGoToStrings {
    const val MENU_ITEM = "Go to element..."
    const val TITLE = "Go to element"
    const val SEARCH_LABEL = "Search by name or text"
    const val CLOSE = "Close"
    const val NO_MATCHES = "No matching elements"
    const val HIDDEN_LAYER = "on a hidden layer"
    const val LOCKED = "locked"
    const val SHOW_LAYER = "Show layer"
    const val UNLOCK = "Unlock"

    /** A result row: the kind first, then the label, so a screen reader hears what it is before which one. */
    fun row(kind: String, label: String, state: String?): String =
        listOfNotNull(kind, label.takeIf { it.isNotBlank() }, state).joinToString(", ")

    /** "N more" announced politely beside the shown results; empty text when nothing is left over. */
    fun more(count: Int): String = if (count > 0) "$count more, refine the search" else ""

    /** The count of matches announced once per search, not once per keystroke of the row list. */
    fun resultCount(shown: Int, more: Int): String = when {
        shown == 0 -> NO_MATCHES
        more > 0 -> "$shown shown, $more more"
        shown == 1 -> "1 match"
        else -> "$shown matches"
    }

    /** Spoken after a result is activated: "Selected: Rectangle, Billing API". */
    fun selected(kind: String, text: String): String = if (text.isBlank()) "Selected: $kind" else "Selected: $kind, $text"

    /** Spoken when the chosen element's layer is hidden and nothing was selected. */
    fun layerHidden(name: String): String = "$name is on a hidden layer. Nothing selected."
}
