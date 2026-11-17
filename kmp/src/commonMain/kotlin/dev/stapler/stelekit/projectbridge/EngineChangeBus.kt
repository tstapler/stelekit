// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Carries "the mode changed while an editor is open" from Settings to the open editor's route (UX-69). Settings signals only
 * after [EngineChangeFlow.onModeChanged] said an editor is open; the route shows the `EngineChange` notice and swaps nothing
 * until the user activates Reopen.
 */
class EngineChangeBus {
    private val count = MutableStateFlow(0)
    val revision: StateFlow<Int> = count

    fun signal() = count.update { it + 1 }
}

/** Whether the whole-project warning was confirmed in Settings during THIS app session (the first-write dialog then shows it by reference). */
class SessionConfirmation {
    var confirmed: Boolean = false
        private set

    fun confirmedHere() {
        confirmed = true
    }
}
