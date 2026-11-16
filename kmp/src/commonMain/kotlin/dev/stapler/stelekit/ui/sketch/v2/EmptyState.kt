// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object EmptyStateTags {
    const val ROOT = "sketch.empty"
    const val PROMPT = "sketch.empty.prompt"
    const val IMPORT = "sketch.empty.import"
    const val SHORTCUTS = "sketch.empty.shortcuts"
}

/** What the two empty-state actions do; the host opens its import flow and the shortcuts sheet. */
data class EmptyStateActions(val onImport: () -> Unit = {}, val onShortcuts: () -> Unit = {})

private const val TARGET_DP = 44

/**
 * The empty canvas (Task 3.6.6c, UX-74): "Pick a tool and draw, or import an .excalidraw file" with Import and "Keyboard
 * shortcuts". It is a state, not a nag: [BannerSlotState] shows it only while the sheet is empty and no banner is up, and
 * it goes with the first element and comes back if the sheet is emptied again. Both actions are buttons, so they are
 * keyboard operable, and the prompt is a polite live region so a screen reader announces it.
 */
@Composable
fun EmptyState(actions: EmptyStateActions, modifier: Modifier = Modifier) {
    Column(modifier.testTag(EmptyStateTags.ROOT).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            SketchStrings.Empty.PROMPT,
            Modifier.testTag(EmptyStateTags.PROMPT).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onImport, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(EmptyStateTags.IMPORT)) { Text(SketchStrings.Empty.IMPORT) }
            TextButton(onClick = actions.onShortcuts, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(EmptyStateTags.SHORTCUTS)) { Text(SketchStrings.Empty.SHORTCUTS) }
        }
    }
}
