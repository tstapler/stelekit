// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloneFailureCopyTest {
    private val raw = "Software caused connection abort"

    @Test
    fun `clone failure copy never contains the raw transport message`() {
        val errors = listOf(
            DomainError.GitError.CloneFailed(raw),
            DomainError.GitError.FetchFailed(raw),
            DomainError.GitError.NetworkFailure(raw),
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed(raw)),
        )
        errors.forEach { assertFalse(cloneFailureCopy(it).contains(raw), "leaked raw text for $it") }
    }

    @Test
    fun `non-git errors fall back to generic authored copy`() {
        assertEquals(
            "Clone failed — check your connection and try again",
            cloneFailureCopy(DomainError.DatabaseError.WriteFailed(raw)),
        )
    }
}
