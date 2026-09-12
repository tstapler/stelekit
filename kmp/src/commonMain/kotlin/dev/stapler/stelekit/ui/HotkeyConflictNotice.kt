// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.capture.HotkeyRegistrationFailure

private fun causeMessage(failure: HotkeyRegistrationFailure, hotkeyCombo: String): String = when (failure) {
    HotkeyRegistrationFailure.AlreadyInUse ->
        "$hotkeyCombo is already in use by another application."
    HotkeyRegistrationFailure.UnsupportedSession ->
        "This desktop session doesn't support global hotkeys."
    HotkeyRegistrationFailure.Unknown ->
        "$hotkeyCombo couldn't be registered for an unknown reason."
}

/**
 * Informational, non-blocking notice shown when the quick-capture hotkey failed to register
 * (Story 1.4.3, `design/ux.md` Surface 3). States the cause in plain language rather than a
 * generic "error occurred" — the rest of the app remains fully usable either way.
 */
@Composable
fun HotkeyConflictNotice(
    failure: HotkeyRegistrationFailure,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hotkeyCombo: String = "The Quick Capture hotkey",
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("hotkeyConflictNotice"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Quick Capture hotkey unavailable",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    causeMessage(failure, hotkeyCombo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag("hotkeyConflictNoticeMessage"),
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("hotkeyConflictNoticeDismiss")) {
                Text("Dismiss")
            }
        }
    }
}
