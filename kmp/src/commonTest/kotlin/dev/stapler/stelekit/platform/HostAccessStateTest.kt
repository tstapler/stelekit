// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compile-time exhaustiveness guard for [HostAccessState] (Epic 1.3 of
 * `web-local-folder-livesync`). If a variant is ever added or removed, the `when` below (which
 * has no `else` branch) fails to compile until every branch is updated — this test's real
 * assertion is that the module compiles at all.
 */
class HostAccessStateTest {
    @Test
    fun hostAccessState_should_ExposeExactlySixVariants_When_ExhaustiveWhenIsCompiled() {
        val states: List<HostAccessState> = listOf(
            HostAccessState.NotApplicable,
            HostAccessState.Granted,
            HostAccessState.PromptNeeded,
            HostAccessState.Denied,
            HostAccessState.Disconnected("stale handle"),
            // Epic 4.1 (Task 4.1.2b): added by unlinkHostDirectory — see HostAccessState.Unlinked's
            // doc comment for why this is distinct from Disconnected/NotApplicable.
            HostAccessState.Unlinked,
        )

        val labels = states.map { state ->
            // Exhaustive `when` with no `else` — the compile-time guard this test exists for.
            when (state) {
                is HostAccessState.NotApplicable -> "not_applicable"
                is HostAccessState.Granted -> "granted"
                is HostAccessState.PromptNeeded -> "prompt_needed"
                is HostAccessState.Denied -> "denied"
                is HostAccessState.Disconnected -> "disconnected:${state.reason}"
                is HostAccessState.Unlinked -> "unlinked"
            }
        }

        assertEquals(
            listOf("not_applicable", "granted", "prompt_needed", "denied", "disconnected:stale handle", "unlinked"),
            labels,
        )
    }
}
