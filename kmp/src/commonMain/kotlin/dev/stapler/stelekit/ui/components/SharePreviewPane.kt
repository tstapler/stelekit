// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.ShareScope
import dev.stapler.stelekit.ui.StelekitViewModel
import kotlinx.datetime.LocalDate

/** Outcome of computing the preview text for the current format + scope. */
internal sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val text: String) : PreviewState
    data class Failed(val message: String) : PreviewState
}

/**
 * Shows the exact string the export flow would produce for [formatId] + [shareScope], as
 * selectable monospace text (HTML is shown as source, never rendered). Recomputed off the UI
 * thread (the export service runs on the DB dispatcher) whenever an input changes; the previous
 * computation is cancelled by [LaunchedEffect].
 */
@Composable
internal fun SharePreviewPane(
    viewModel: StelekitViewModel,
    shareScope: ShareScope,
    page: Page,
    blocks: List<Block>,
    selectedUuids: Set<String>,
    formatId: String,
    stripTrackingParams: Boolean,
    journalFrom: LocalDate?,
    journalTo: LocalDate?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<PreviewState>(PreviewState.Loading) }

    LaunchedEffect(shareScope, page.uuid, blocks, selectedUuids, formatId, stripTrackingParams, journalFrom, journalTo) {
        state = PreviewState.Loading
        state = if (shareScope == ShareScope.JournalRange && (journalFrom == null || journalTo == null)) {
            PreviewState.Failed("Enter a valid date range to preview")
        } else {
            viewModel.resolveExportContent(shareScope, page, blocks, selectedUuids, formatId, journalFrom, journalTo)
                .fold({ PreviewState.Failed(it.message) }, { PreviewState.Ready(it) })
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
        ) {
            when (val s = state) {
                PreviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is PreviewState.Failed -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
                is PreviewState.Ready -> SelectionContainer {
                    Text(
                        text = s.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
        FilledTonalButton(
            onClick = onCopy,
            enabled = state is PreviewState.Ready,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Copy") }
    }
}
