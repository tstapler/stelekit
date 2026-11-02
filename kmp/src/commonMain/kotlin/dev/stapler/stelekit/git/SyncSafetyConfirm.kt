// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.SyncState

/** Where a tap on an unhealthy sync badge (or the review's `Open details`) leads. */
enum class SyncDetailsTarget { None, RepairNeeded, MassChange, Conflict }

/** The detail surface that explains [this] state, or [SyncDetailsTarget.None] when the state has none. */
fun SyncState.detailsTarget(): SyncDetailsTarget = when {
    this is SyncState.ConflictPending -> SyncDetailsTarget.Conflict
    this is SyncState.Error && error is DomainError.GitError.RepairNeeded -> SyncDetailsTarget.RepairNeeded
    this is SyncState.Error && error is DomainError.GitError.MassChangeBlocked -> SyncDetailsTarget.MassChange
    else -> SyncDetailsTarget.None
}

/** Typed-word confirm rules shared by every dialog that asks the user to type to continue. */
object TypedConfirm {
    const val SYNC_WITHOUT_PREVIEW_WORD = "sync"

    /** Trimmed, case-insensitive; internal whitespace must match. */
    fun matches(input: String, expected: String): Boolean = input.trim().equals(expected, ignoreCase = true)
}

/**
 * The mass-change confirm: the user counts a figure from the listed paths and types it, so a reflexive
 * tap-through (or copying a number out of the prompt) cannot lift the guard. The expected figure never
 * equals a number printed in the headline or question.
 */
class MassChangeChallenge private constructor(
    val headline: String,
    val listedPaths: List<String>,
    val question: String,
    private val expected: Int?,
) {
    /** False when every candidate collides with a printed number; the run then stays blocked (fail closed). */
    val answerable: Boolean get() = expected != null

    /** Digits only: `214 files` does not match `214`. */
    fun accepts(input: String): Boolean {
        val answer = input.trim()
        return expected != null && answer.isNotEmpty() && answer.all { it in '0'..'9' } && answer.toIntOrNull() == expected
    }

    companion object {
        const val UNANSWERABLE_LINE = "Can't confirm here. Run git status in the dev console to review."
        const val MISMATCH_HINT = "Doesn't match — count the list again"
        private val NUMBER = Regex("[0-9]+")

        fun isJournalPath(path: String): Boolean = path.startsWith("journals/") || path.contains("/journals/")

        fun of(error: DomainError.GitError.MassChangeBlocked): MassChangeChallenge {
            val listed = error.sample
            val headline = "Sync paused: ${error.deleted} files would be deleted"
            val question = "Of the ${listed.size} files listed, how many are journals? Type the number."
            val printed = NUMBER.findAll("$headline $question").map { it.value.toInt() }.toSet()
            val journals = listed.count(::isJournalPath)
            val folders = listed.map { it.substringBeforeLast('/', "") }.toSet().size
            val candidates = listOf(journals, listed.size - journals, folders)
            return MassChangeChallenge(headline, listed, question, candidates.firstOrNull { it !in printed })
        }
    }
}
