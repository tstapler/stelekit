// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.GitTransportRetryState
import dev.stapler.stelekit.git.NonRetryableReason

@Composable
internal fun Step5TestAndSave(
    testState: GitConnectionTestState,
    // ponytail: saving/cloneInProgress/existingRepoNeedsAllFilesAccess each independently gate a
    // single, self-contained UI element (a spinner, a progress row, a warning banner) — not one
    // function secretly doing two unrelated things. Left as plain booleans.
    saving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    onCancelTestConnection: () -> Unit,
    cloneInProgress: Boolean = false,
    // git-sync-resilience Story 4.1.3: replaces the old bare `cloneProgress: String` — one sealed
    // state drives both the in-progress row (Attempting/Retrying/ResumingDeepen) and the two
    // distinct terminal-failure treatments (Exhausted/NonRetryableFailure) below.
    retryState: GitTransportRetryState = GitTransportRetryState.Idle,
    // Story 4.1.4: a manual cancel is not part of GitTransportRetryState (it's a UI/UX outcome,
    // not a transport-retry-loop state) — a separate flag, mirroring how `saving`/`cloneInProgress`
    // are already independent booleans rather than folded into one enum.
    cloneCancelled: Boolean = false,
    cloneError: String? = null,
    existingRepoNeedsAllFilesAccess: Boolean = false,
    onSave: () -> Unit,
    onCancelClone: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Test and save", style = MaterialTheme.typography.titleMedium)
        Text("Optionally test your connection before saving.", style = MaterialTheme.typography.bodyMedium)

        // Repeats Step2RepoPath's warning here since this is where the underlying problem
        // actually surfaces to the user — a cryptic "repository not found: .../gitshadow"
        // from Test connection — not just at the earlier repo-path step.
        if (existingRepoNeedsAllFilesAccess) {
            AllFilesAccessWarning()
        }

        TestConnectionButton(
            testState = testState,
            onTestConnection = onTestConnection,
            onCancelTestConnection = onCancelTestConnection,
        )

        when {
            cloneInProgress -> CloneProgressRow(retryState, onCancelClone)
            cloneCancelled -> CancelledRow()
            retryState is GitTransportRetryState.Exhausted -> ExhaustedRow(onTryAgain = onSave, maxAttempts = retryState.maxAttempts)
            retryState is GitTransportRetryState.NonRetryableFailure -> NonRetryableFailureRow(retryState.reason)
            else -> {}
        }

        // cloneError is a fallback for outcomes the retry-state machine doesn't itself classify
        // (e.g. CloneAndSaveOutcome.SaveFailed's git-config-write failure, after a clone that
        // already succeeded) — suppressed here when retryState already rendered its own terminal
        // row above, so the same failure is never shown twice.
        val retryStateAlreadyRenderedTerminalCopy =
            retryState is GitTransportRetryState.Exhausted || retryState is GitTransportRetryState.NonRetryableFailure
        if (!retryStateAlreadyRenderedTerminalCopy) {
            cloneError?.let { error -> ErrorText(error) }
        }
        saveError?.let { error -> ErrorText(error) }

        BackAndSaveRow(saving = saving, cloneInProgress = cloneInProgress, onBack = onBack, onSave = onSave)
    }
}

@Composable
private fun BackAndSaveRow(
    saving: Boolean,
    cloneInProgress: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val busy = saving || cloneInProgress
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onBack, enabled = !busy) { Text("Back") }
        Button(
            onClick = onSave,
            enabled = !busy,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Save configuration")
        }
    }
}

@Composable
private fun AllFilesAccessWarning() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "This repo was picked via the system document picker, so \"Test " +
                "connection\" will fail here unless \"All files access\" is granted.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { dev.stapler.stelekit.platform.openAllFilesAccessSettings() }) {
            Text("Open \"All files access\" settings")
        }
    }
}

@Composable
private fun TestConnectionButton(
    testState: GitConnectionTestState,
    onTestConnection: () -> Unit,
    onCancelTestConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTestConnection,
                enabled = testState !is GitConnectionTestState.InProgress,
                modifier = Modifier.weight(1f),
            ) {
                if (testState is GitConnectionTestState.InProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Test connection")
            }
            // Finding #2: testRemote() is bounded by a 15s timeout, but a disabled button with no
            // way out until then is still worth an explicit escape hatch.
            if (testState is GitConnectionTestState.InProgress) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCancelTestConnection) { Text("Cancel") }
            }
        }

        TestResultRow(testState)
    }
}

@Composable
private fun TestResultRow(testState: GitConnectionTestState) {
    val resultMessage = when (testState) {
        is GitConnectionTestState.Success -> testState.message
        is GitConnectionTestState.Failure -> testState.message
        else -> null
    } ?: return
    val success = testState is GitConnectionTestState.Success
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (success) Icons.Default.Check else Icons.Default.Error,
            contentDescription = if (success) "Success" else "Error",
            tint = if (success) Color(0xFF047857) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = resultMessage,
            color = if (success) Color(0xFF047857) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Story 4.1.3/4.1.4: the in-progress clone row — primary/secondary text split per
 * `design/ux.md`'s Surface A wireframe, plus the always-visible Cancel affordance (Story 4.1.4,
 * UX Acceptance Test 1/10/16). The whole row carries `liveRegion = LiveRegionMode.Polite`
 * (Task 4.1.3b, UX Acceptance Test 14), matching `FolderSyncReconciliationProgress.kt`/
 * `StorageMoveProgressDialog.kt`'s existing convention exactly — announcement-rate debouncing
 * (at most once per retry-attempt transition) is already enforced upstream, at
 * `GitOperationSupport.runGitTransportOpWithRetry`'s `onStateChange` call sites (Task 4.1.2b), not
 * here.
 */
@Composable
private fun CloneProgressRow(retryState: GitTransportRetryState, onCancelClone: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(primaryTextFor(retryState), style = MaterialTheme.typography.bodySmall)
            secondaryTextFor(retryState)?.let { secondary ->
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // UX Acceptance Test 16: distinct accessible label from the pre-existing Test Connection
        // row's bare "Cancel" TextButton — both render the visible text "Cancel."
        TextButton(
            onClick = onCancelClone,
            modifier = Modifier.semantics { contentDescription = "Cancel clone" },
        ) { Text("Cancel") }
    }
}

private fun primaryTextFor(retryState: GitTransportRetryState): String = when (retryState) {
    is GitTransportRetryState.ResumingDeepen -> "Resuming…"
    else -> "Cloning your graph…"
}

/** `null` renders no secondary line at all (Attempting/ResumingDeepen) — only `Retrying` has one,
 * per plan.md's Story 4.1.3 AC. */
private fun secondaryTextFor(retryState: GitTransportRetryState): String? = when (retryState) {
    is GitTransportRetryState.Retrying -> {
        val base = "Reconnecting… Attempt ${retryState.attempt} of ${retryState.max}"
        val percent = retryState.progress?.let(::percentOf)
        if (percent != null) "$base — $percent%" else base
    }
    else -> null
}

private fun percentOf(progress: CloneProgress): Int? =
    if (progress.totalWork > 0) (progress.completed * 100 / progress.totalWork) else null

/**
 * Story 4.1.3's `Exhausted` terminal treatment — warning-styled (never error-red), offering
 * "Try again," which calls the exact same entry point as the original "Save configuration"
 * action (plan.md AC / UX Acceptance Test 2), not a special-cased retry function.
 *
 * [maxAttempts] is templated into the copy from `GitTransportRetryState.Exhausted.maxAttempts`
 * (the real retry budget for this run) rather than hardcoded — plan.md Story 4.1.3's own quoted
 * Acceptance Criterion text said "4 attempts", which had already drifted from
 * `RetryPolicies.gitTransportTransient`'s actual budget of 5 (asserted by
 * `GitTransportRetryTest.kt`'s `RetryExhausted(attempts=5,...)`); templating from live state means
 * this copy can never drift from the real constant again.
 */
@Composable
private fun ExhaustedRow(onTryAgain: () -> Unit, maxAttempts: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Couldn't finish after $maxAttempts attempts. Check your connection and try again — your progress is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        TextButton(onClick = onTryAgain, modifier = Modifier.align(Alignment.End)) { Text("Try again") }
    }
}

/** Story 4.1.3's `NonRetryableFailure` terminal treatment — error-styled, no attempt counter, no
 * "Try again" (retrying without fixing the underlying config can't succeed): copy names the exact
 * wizard step to fix, per [NonRetryableReason]'s tag. */
@Composable
private fun NonRetryableFailureRow(reason: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = nonRetryableCopyFor(reason),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun nonRetryableCopyFor(reason: String): String = when (reason) {
    NonRetryableReason.NOT_FOUND -> "Repository not found — check the URL on the previous step."
    else -> "Authentication failed — check your token/SSH key in Step 3."
}

/** Story 4.1.4's exact post-cancel copy — neutral (not error-styled): cancelling is not a failure. */
@Composable
private fun CancelledRow() {
    Text(
        "Cancelled — your progress is saved. Resume anytime from Step 5.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
