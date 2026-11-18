// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PausedRetryPolicyTest {
    @Test
    fun default_should_BeTwoRetriesAtThirtySecondsThenTwoMinutes_MatchingTheDisplayedBudget() {
        val policy = PausedRetryPolicy()
        assertEquals(PausedRetryInfo.MAX_TRIES, policy.maxTries)
        assertEquals(30_000L, policy.delayMs(1))
        assertEquals(120_000L, policy.delayMs(2))
    }

    @Test
    fun emptyPolicy_should_BeRejected() {
        assertFailsWith<IllegalArgumentException> { PausedRetryPolicy(emptyList()) }
    }

    @Test
    fun isUnsure_should_BeTrueOnlyForUnknownOwnerAndNoRecord() {
        fun lock(v: LockVerdict?) = DomainError.GitError.StaleLockFile("p", v)
        assertTrue(PausedRetryPolicy.isUnsure(lock(LockVerdict.NoRecord)))
        assertTrue(PausedRetryPolicy.isUnsure(lock(LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported))))
        assertFalse(PausedRetryPolicy.isUnsure(lock(LockVerdict.OwnerAlive)))
        assertFalse(PausedRetryPolicy.isUnsure(lock(null)))
        assertFalse(PausedRetryPolicy.isUnsure(DomainError.GitError.SyncInProgress(null)))
        assertFalse(PausedRetryPolicy.isUnsure(DomainError.GitError.FetchFailed("x")))
    }
}
