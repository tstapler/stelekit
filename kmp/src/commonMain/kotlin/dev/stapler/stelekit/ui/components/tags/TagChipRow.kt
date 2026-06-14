package dev.stapler.stelekit.ui.components.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.tags.TagSuggestion

@Composable
fun TagChipRow(
    suggestions: List<TagSuggestion>,
    isLlmLoading: Boolean,
    llmError: String?,
    onAccept: (TagSuggestion) -> Unit,
    onDismiss: (TagSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displaySuggestions = suggestions.filter { !it.autoApplied }

    if (displaySuggestions.isEmpty() && !isLlmLoading && llmError == null) return

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(displaySuggestions) { suggestion ->
                FilterChip(
                    selected = false,
                    onClick = { onAccept(suggestion) },
                    label = { Text(suggestion.term) },
                )
            }
        }

        if (isLlmLoading && displaySuggestions.isEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }

        if (llmError != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Could not reach LLM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
