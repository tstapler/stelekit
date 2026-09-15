// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.llm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.llm.PendingLlmSuggestion

/**
 * Persistent one-at-a-time review queue for LLM-sourced proposals — parallel to
 * [dev.stapler.stelekit.ui.screens.git.JournalMergeReviewScreen]. Shows the current item with
 * a type badge and next/previous navigation, distinct rendering for edit-diff
 * ([BlockDiffView]) vs. new-note-preview ([NewPagePreview]).
 *
 * Discard fires immediately with no confirmation (features research §3's Notion precedent —
 * rejection is non-destructive since nothing was ever written). Bulk "Apply all" requires a
 * second confirmation tap and is never the first action offered (pitfalls §5.2's
 * approval-fatigue mitigation); bulk "Discard all" stays single-tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSuggestionReviewScreen(
    pending: List<PendingLlmSuggestion>,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onAcceptAll: () -> Unit,
    onRejectAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var confirmApplyAll by remember { mutableStateOf(false) }
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    // Keep the index in range as items are accepted/rejected out from under the list.
    LaunchedEffect(pending.size) {
        if (currentIndex >= pending.size && pending.isNotEmpty()) {
            currentIndex = pending.size - 1
        }
        confirmApplyAll = false
    }

    if (pending.isEmpty()) {
        LaunchedEffect(Unit) { latestOnDismiss() }
        return
    }

    val current = pending[currentIndex.coerceIn(0, pending.size - 1)]
    val hasSeenBeyondFirst = currentIndex > 0 || pending.size == 1

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review LLM Suggestions (${currentIndex + 1}/${pending.size})") },
                actions = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (pending.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
                                enabled = currentIndex > 0,
                            ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous suggestion") }
                            Text("${currentIndex + 1} of ${pending.size}")
                            IconButton(
                                onClick = { currentIndex = (currentIndex + 1).coerceAtMost(pending.size - 1) },
                                enabled = currentIndex < pending.size - 1,
                            ) { Icon(Icons.Default.ChevronRight, contentDescription = "Next suggestion") }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onReject(current.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Discard") }
                        Button(
                            onClick = { onAccept(current.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text(applyLabel(current)) }
                    }

                    // Bulk actions — only reachable after the user has navigated past the
                    // first item (or there is only one item, making "past the first" moot).
                    if (hasSeenBeyondFirst && pending.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = onRejectAll,
                                modifier = Modifier.weight(1f),
                            ) { Text("Discard all") }
                            TextButton(
                                onClick = {
                                    if (confirmApplyAll) {
                                        confirmApplyAll = false
                                        onAcceptAll()
                                    } else {
                                        confirmApplyAll = true
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (confirmApplyAll) "Tap again to apply all" else "Apply all") }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AssistChip(onClick = {}, label = { Text(typeBadge(current)) })
            Spacer(modifier = Modifier.height(8.dp))
            current.rationale?.let { rationale ->
                Text(text = rationale, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                when (current) {
                    is PendingLlmSuggestion.BlockEdit -> BlockDiffView(
                        beforeContent = current.currentContentSnapshot,
                        afterContent = current.proposedContent,
                    )
                    is PendingLlmSuggestion.TagChange -> BlockDiffView(
                        beforeContent = current.currentContentSnapshot,
                        afterContent = describeTagChange(current),
                    )
                    is PendingLlmSuggestion.NewPage -> NewPagePreview(
                        title = current.proposedTitle,
                        blocks = current.proposedBlocks,
                    )
                    is PendingLlmSuggestion.UnlinkedReference -> BlockDiffView(
                        beforeContent = current.currentContentSnapshot,
                        afterContent = applyUnlinkedRefPreview(current),
                    )
                }
            }
        }
    }
}

private fun typeBadge(suggestion: PendingLlmSuggestion): String = when (suggestion) {
    is PendingLlmSuggestion.BlockEdit -> "Edit"
    is PendingLlmSuggestion.TagChange -> "Tag Change"
    is PendingLlmSuggestion.NewPage -> "New Note"
    is PendingLlmSuggestion.UnlinkedReference -> "Link"
}

private fun applyLabel(suggestion: PendingLlmSuggestion): String = when (suggestion) {
    is PendingLlmSuggestion.BlockEdit -> "Apply"
    is PendingLlmSuggestion.TagChange -> "Apply"
    is PendingLlmSuggestion.NewPage -> "Create"
    is PendingLlmSuggestion.UnlinkedReference -> "Link"
}

private fun applyUnlinkedRefPreview(s: PendingLlmSuggestion.UnlinkedReference): String {
    val c = s.currentContentSnapshot
    val safeEnd = s.matchEnd.coerceAtMost(c.length)
    val safeStart = s.matchStart.coerceIn(0, safeEnd)
    return c.substring(0, safeStart) + "[[${s.targetPageName}]]" + c.substring(safeEnd)
}

private fun describeTagChange(tagChange: PendingLlmSuggestion.TagChange): String {
    val added = tagChange.addedTerms.joinToString(" ") { "[[$it]]" }
    val removed = tagChange.removedTerms.joinToString(", ")
    return buildString {
        append(tagChange.currentContentSnapshot)
        if (added.isNotEmpty()) append(" $added")
        if (removed.isNotEmpty()) append(" (removing: $removed)")
    }
}
