// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.llm.ProposedBlock
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling

/**
 * Full-page, read-only preview of a synthesized `PendingLlmSuggestion.NewPage` proposal —
 * title + block tree exactly as it would appear if created, using the same rendering
 * primitives as the rest of the app.
 */
@Composable
fun NewPagePreview(
    title: String,
    blocks: List<ProposedBlock>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        blocks.sortedBy { it.order }.forEach { block ->
            BasicText(
                text = parseMarkdownWithStyling(
                    text = block.content,
                    linkColor = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (block.depth * 16).dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}
