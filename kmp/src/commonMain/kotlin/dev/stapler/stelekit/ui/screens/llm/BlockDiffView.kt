// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling

/**
 * Read-only before/after rendering of a single block edit, using the same markdown
 * rendering primitive ([parseMarkdownWithStyling]) the rest of the app uses to render a
 * block's content — not a generic text diff.
 */
@Composable
fun BlockDiffView(
    beforeContent: String,
    afterContent: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DiffPanel(
            label = "Before",
            content = beforeContent,
            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        DiffPanel(
            label = "After",
            content = afterContent,
            backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        )
    }
}

@Composable
private fun DiffPanel(label: String, content: String, backgroundColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = 4.dp))
        BasicText(
            text = parseMarkdownWithStyling(
                text = content,
                linkColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}
