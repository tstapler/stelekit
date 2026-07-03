package dev.stapler.stelekit.ui.components.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.tags.TagSuggestionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionBottomSheet(
    state: TagSuggestionState,
    onAcceptTag: (blockUuid: String, term: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible = state is TagSuggestionState.Ready || state is TagSuggestionState.Loading
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Suggested tags for this block",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }

            when (state) {
                is TagSuggestionState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is TagSuggestionState.Ready -> {
                    val allSuggestions = state.localSuggestions + state.llmSuggestions
                    val isLlmLoading = state.llmPending

                    TagChipRow(
                        suggestions = allSuggestions,
                        isLlmLoading = isLlmLoading,
                        llmError = state.llmError,
                        onAccept = { suggestion ->
                            onAcceptTag(state.blockUuid, suggestion.term)
                        },
                        onDismiss = { /* dismiss silently */ },
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    if (state.llmError != null) {
                        Text(
                            text = state.llmError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}
