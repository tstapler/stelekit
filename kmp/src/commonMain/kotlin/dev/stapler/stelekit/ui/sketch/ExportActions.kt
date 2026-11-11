// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.sketch.export.SketchExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-line result for the export button: how many files were written, or why none were. */
fun exportStatusText(result: Either<DomainError, List<String>>): String = when (result) {
    is Either.Right -> "Exported ${result.value.size} files"
    is Either.Left -> "Export failed: ${result.value.toUiMessage()}"
}

/** Export button for the sketch toolbar; shows nothing when this graph has no writable location. */
@Composable
fun SketchExportButton(state: SketchUiState, exporter: SketchExporter?, modifier: Modifier = Modifier) {
    val slug = state.slug
    if (exporter == null || slug == null) return
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(
        onClick = {
            scope.launch { status = exportStatusText(withContext(PlatformDispatcher.IO) { exporter.export(state.document, slug, state.revision) }) }
        },
        modifier = modifier,
    ) { Text("Export") }
    status?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
}
