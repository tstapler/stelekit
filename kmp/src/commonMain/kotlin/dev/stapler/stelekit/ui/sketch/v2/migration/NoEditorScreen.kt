// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.PlatformBackHandler

object NoEditorTags {
    const val SCREEN = "sketch.no-editor.screen"
    const val HEADING = "sketch.no-editor.heading"
    const val PENDING = "sketch.pending.screen"
    const val PENDING_HEADING = "sketch.pending.heading"
    const val PENDING_LONGER = "sketch.pending.longer"

    fun action(action: NoEditorAction) = "sketch.no-editor.action.${action.name}"
}

private val ScreenGap = 12.dp

/**
 * The shared host of the newer-schema and storage-unavailable states (Task 1.4.6j, UX-134, UX-138): a region named by its
 * level-1 heading (not a dialog: no focus trap), focus on the heading on arrival, the actions of the model in tab order, and
 * Esc and system back activating [NoEditorAction.Close]. Closing returns focus to the opener, which is the host's job.
 */
@Composable
fun NoEditorScreen(model: NoEditorModel, onAction: (NoEditorAction) -> Unit, modifier: Modifier = Modifier) {
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(model.heading) { headingFocus.requestFocus() }
    PlatformBackHandler(enabled = true) { onAction(NoEditorAction.Close) }
    Column(
        verticalArrangement = Arrangement.spacedBy(ScreenGap),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(NoEditorTags.SCREEN)
            .semantics { paneTitle = model.heading }
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onAction(NoEditorAction.Close) }
            },
    ) {
        Text(
            model.heading,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.focusRequester(headingFocus).focusable().testTag(NoEditorTags.HEADING).semantics { heading() },
        )
        model.paragraphs.forEach { Text(it) }
        model.actions.forEach { action -> ActionButton(action, onAction) }
    }
}

@Composable
private fun ActionButton(action: NoEditorAction, onAction: (NoEditorAction) -> Unit) {
    TextButton(onClick = { onAction(action) }, modifier = Modifier.testTag(NoEditorTags.action(action))) { Text(action.label) }
}

/** The host of the router's `Pending` outcome (UX-146): a heading with focus, a second line after 5 s, and [Close]. */
@Composable
fun PendingScreen(model: PendingModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { headingFocus.requestFocus() }
    PlatformBackHandler(enabled = true, onBack = onClose)
    Column(
        verticalArrangement = Arrangement.spacedBy(ScreenGap),
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(NoEditorTags.PENDING)
            .semantics { paneTitle = model.heading }
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onClose() }
            },
    ) {
        Text(
            model.heading,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.focusRequester(headingFocus).focusable().testTag(NoEditorTags.PENDING_HEADING).semantics { heading() },
        )
        model.longerLine?.let { Text(it, modifier = Modifier.testTag(NoEditorTags.PENDING_LONGER)) }
        TextButton(onClick = onClose, modifier = Modifier.testTag(NoEditorTags.action(NoEditorAction.Close))) { Text(NoEditorAction.Close.label) }
    }
}
