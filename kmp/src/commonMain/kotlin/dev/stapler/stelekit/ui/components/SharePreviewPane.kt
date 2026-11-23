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
import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import dev.stapler.stelekit.error.DomainError

/** Outcome of computing the preview text for the current format + scope. */
internal sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val text: String) : PreviewState
    data class Failed(val message: String) : PreviewState
}

/**
 * Runs [compute] on [PlatformDispatcher.Default] (never the UI thread) and shows the string it yields (the export flow's own output for the chosen format + scope), as
 * selectable monospace text (HTML is shown as source, never rendered). Recomputed off the UI
 * thread (the export service runs on the DB dispatcher) whenever an input changes; the previous
 * computation is cancelled by [LaunchedEffect].
 */
@Composable
internal fun SharePreviewPane(
    previewKey: Any?,
    compute: suspend () -> Either<DomainError, String>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCompute by rememberUpdatedState(compute)
    var state by remember { mutableStateOf<PreviewState>(PreviewState.Loading) }

    // previewKey carries every input of [compute] (format, scope, blocks, ...); a change cancels
    // the in-flight computation and starts a new one.
    LaunchedEffect(previewKey) {
        state = PreviewState.Loading
        state = try {
            withContext(PlatformDispatcher.Default) { currentCompute() }.fold({ PreviewState.Failed(it.message) }, { PreviewState.Ready(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PreviewState.Failed(e.message ?: "Preview failed")
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
