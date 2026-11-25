// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The words of the agent-review bar and badges (epic E8): what an agent wrote and nobody has accepted yet. */
object SketchProvenanceStrings {
    const val BAR_REGION = "Agent changes to review"
    const val BADGE = "AI"
    const val ACCEPT_ALL = "Accept all"
    const val ACCEPT_ALL_NAME = "Accept every agent change on this sheet"
    const val ACCEPT_SELECTED = "Accept"
    const val ACCEPT_SELECTED_NAME = "Accept the selected agent changes"
    const val ACCEPTED_TOAST_KEY = "provenance.accepted"

    fun summary(count: Int): String = if (count == 1) "1 agent change to review" else "$count agent changes to review"

    fun badgeName(name: String, note: String?): String =
        if (note.isNullOrBlank()) "$name, written by an agent, not reviewed" else "$name, written by an agent, not reviewed: $note"

    fun accepted(count: Int): String = if (count == 1) "Accepted 1 change" else "Accepted $count changes"
}
