// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

/** What the editor should do when the user presses Back. */
sealed interface BackOutcome {
    /** Nothing to lose: close immediately. */
    data object Close : BackOutcome

    /** Only the [count] unfinished (not yet placed) points would be lost. */
    data class ConfirmDiscard(val count: Int) : BackOutcome

    /** The last save failed; offer retry or close-anyway. */
    data object SaveFailed : BackOutcome
}

/**
 * Decides Back after any wait for in-flight saves. Placed measurements are saved as they are made,
 * so only unfinished points count as unsaved. A write still pending after the wait window does not
 * block leaving: the queue keeps draining after the editor closes.
 */
fun backOutcome(unfinishedPoints: Int, saveFailed: Boolean): BackOutcome = when {
    saveFailed -> BackOutcome.SaveFailed
    unfinishedPoints > 0 -> BackOutcome.ConfirmDiscard(unfinishedPoints)
    else -> BackOutcome.Close
}

fun discardDialogTitle(unfinishedPoints: Int): String =
    "Discard $unfinishedPoints unfinished ${if (unfinishedPoints == 1) "point" else "points"}?"

/** How long Back waits for in-flight saves before leaving. */
const val LEAVE_SAVE_WAIT_MILLIS: Long = 2_000
