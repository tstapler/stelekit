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
    fun hostAccessState_should_ExposeExactlyFiveVariants_When_ExhaustiveWhenIsCompiled() {
        val states: List<HostAccessState> = listOf(
            HostAccessState.NotApplicable,
            HostAccessState.Granted,
            HostAccessState.PromptNeeded,
            HostAccessState.Denied,
            HostAccessState.Disconnected("stale handle"),
        )

        val labels = states.map { state ->
            // Exhaustive `when` with no `else` — the compile-time guard this test exists for.
            when (state) {
                is HostAccessState.NotApplicable -> "not_applicable"
                is HostAccessState.Granted -> "granted"
                is HostAccessState.PromptNeeded -> "prompt_needed"
                is HostAccessState.Denied -> "denied"
                is HostAccessState.Disconnected -> "disconnected:${state.reason}"
            }
        }

        assertEquals(
            listOf("not_applicable", "granted", "prompt_needed", "denied", "disconnected:stale handle"),
            labels,
        )
    }
}
