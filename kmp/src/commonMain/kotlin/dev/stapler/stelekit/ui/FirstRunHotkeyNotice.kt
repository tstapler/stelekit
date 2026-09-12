// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * One-time notice teaching the user the quick-capture hotkey (Story 1.4.2, `design/ux.md`
 * Surface 2). Shown once (caller persists the flag), dismissible via "Got it" or `Escape`, and
 * revisitable afterward from the Settings "Keyboard Shortcuts" row — never re-triggers itself.
 *
 * Requests focus onto itself on first composition: `onPreviewKeyEvent` only receives key events
 * routed through a focused node or its ancestors, and this overlay renders over whatever the
 * user was already focused on (e.g. a block editor) -- without an explicit focus request here,
 * Escape would never reach this card at all (this was previously untested and silently broken).
 */
@Composable
fun FirstRunHotkeyNotice(hotkeyCombo: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("firstRunHotkeyNotice")
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ℹ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                "New: Quick Capture — Press $hotkeyCombo anywhere to capture a note into today's journal.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("firstRunHotkeyNoticeDismiss")) {
                Text("Got it")
            }
        }
    }
}
