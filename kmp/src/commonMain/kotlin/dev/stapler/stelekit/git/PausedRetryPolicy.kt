// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError

/**
 * The automatic retries after a sync paused on an unknown lock owner (ADR-001 decision 14): one wait per try,
 * so [delaysMs].size is the retry budget. After the last try fails the sheet reads Failed.
 */
data class PausedRetryPolicy(val delaysMs: List<Long> = DEFAULT_DELAYS_MS) {
    init {
        require(delaysMs.isNotEmpty()) { "a paused retry policy needs at least one try" }
    }

    val maxTries: Int get() = delaysMs.size

    /** The wait before try [tryNumber] (1-based). */
    fun delayMs(tryNumber: Int): Long = delaysMs[tryNumber - 1]

    companion object {
        val DEFAULT_DELAYS_MS: List<Long> = listOf(30_000L, 120_000L)

        init {
            check(DEFAULT_DELAYS_MS.size == PausedRetryInfo.MAX_TRIES) { "keep the displayed 'of N' in step with the policy" }
        }

        /** True for the verdicts that mean the owner could not be determined: the only ones worth waiting out. */
        fun isUnsure(error: DomainError.GitError): Boolean {
            val verdict = (error as? DomainError.GitError.StaleLockFile)?.verdict
            return verdict == LockVerdict.NoRecord || verdict is LockVerdict.OwnerUnknown
        }
    }
}
