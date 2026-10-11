// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

/**
 * Step 5's "Test connection" state machine — replaces the separate `testInProgress`/`testResult`/
 * `testSuccess` trio that was previously threaded through [GitSetupScreen] and `Step5TestAndSave`
 * as three independently-settable flags.
 */
internal sealed class GitConnectionTestState {
    data object Idle : GitConnectionTestState()
    data object InProgress : GitConnectionTestState()
    data class Success(val message: String) : GitConnectionTestState()
    data class Failure(val message: String) : GitConnectionTestState()
}
