package dev.stapler.stelekit.ui.components.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.tags.LlmSuggestionStatus
import dev.stapler.stelekit.tags.TagSuggestionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionBottomSheet(
    state: TagSuggestionState,
    onAcceptTag: (blockUuid: String, term: String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible = state is TagSuggestionState.Ready || state is TagSuggestionState.Loading
    if (!isVisible) return

    // GAP-003 (Story D.1.1): skip the partially-expanded resting state so the sheet reaches its
    // final position in one continuous motion instead of settling-then-expanding — removing a
    // perceptible extra animation step from the flagship "insert tag" button-path journey.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

                    TagChipRow(
                        suggestions = allSuggestions,
                        llmStatus = state.llmStatus,
                        onAccept = { suggestion -> onAcceptTag(state.blockUuid, suggestion.term) },
                        onDismiss = { /* dismiss silently */ },
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    when (val status = state.llmStatus) {
                        is LlmSuggestionStatus.Pending -> status.caption?.let { caption ->
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        is LlmSuggestionStatus.Stalled -> {
                            Column(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Text(
                                    text = "Taking longer than expected.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                Text(
                                    text = "Tap Retry to check again, or keep typing the tag yourself.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Structurally absent (an `if`, not enabled=false) when not retryable — a
                                // disabled-but-visible button reads as broken to screen readers.
                                if (status.retryable) {
                                    TextButton(
                                        onClick = onRetry,
                                        modifier = Modifier.semantics { contentDescription = "Retry downloading tags" },
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                        is LlmSuggestionStatus.Failed -> {
                            Column(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                // Structurally absent (an `if`, not enabled=false) when not retryable — same
                                // accessibility rule as the Stalled branch above (a disabled-but-visible
                                // button reads as broken to screen readers). Retryable Failed (e.g. a
                                // DomainError.NetworkError.Timeout) needs this exactly like Stalled does.
                                if (status.retryable) {
                                    TextButton(
                                        onClick = onRetry,
                                        modifier = Modifier.semantics { contentDescription = "Retry downloading tags" },
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                        LlmSuggestionStatus.NotStarted, LlmSuggestionStatus.Resolved -> Unit
                    }
                }
                else -> Unit
            }
        }
    }
}
