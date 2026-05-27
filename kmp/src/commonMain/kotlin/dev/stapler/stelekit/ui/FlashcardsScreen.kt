// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.flashcard.FlashcardScheduler
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun FlashcardsScreen(blockStateManager: dev.stapler.stelekit.ui.state.BlockStateManager) {
    val allBlocks by blockStateManager.blocks.collectAsState()
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    val dueCards = remember(allBlocks) {
        allBlocks.values.flatten().filter { FlashcardScheduler.isDue(it, today) }
    }

    var currentIndex by remember(dueCards) { mutableIntStateOf(0) }
    var showBack by remember(currentIndex) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun onPass() {
        val card = dueCards.getOrNull(currentIndex) ?: return
        val newProps = FlashcardScheduler.computeNextReview(card, pass = true, today = today)
        scope.launch { blockStateManager.updateBlockProperties(card.uuid, newProps) }
        showBack = false
        currentIndex++
    }

    fun onFail() {
        val card = dueCards.getOrNull(currentIndex) ?: return
        val newProps = FlashcardScheduler.computeNextReview(card, pass = false, today = today)
        scope.launch { blockStateManager.updateBlockProperties(card.uuid, newProps) }
        showBack = false
        currentIndex++
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Flashcards",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "${minOf(currentIndex, dueCards.size)} / ${dueCards.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (dueCards.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Style,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No cards due for review",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tag a block with card:: true to create a flashcard",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else if (currentIndex >= dueCards.size) {
            // All done state
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "All done!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You've reviewed all ${dueCards.size} cards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { currentIndex = 0 }) {
                        Text("Review again")
                    }
                }
            }
        } else {
            val card = dueCards[currentIndex]

            // Card with swipe gesture
            var offsetX by remember(currentIndex) { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .offset { IntOffset(offsetX.roundToInt() / 4, 0) }
                        .pointerInput(currentIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        offsetX > FlashcardScheduler.SWIPE_THRESHOLD -> onPass()
                                        offsetX < -FlashcardScheduler.SWIPE_THRESHOLD -> onFail()
                                        else -> offsetX = 0f
                                    }
                                },
                                onDragCancel = { offsetX = 0f }
                            ) { _, dragAmount -> offsetX += dragAmount }
                        }
                        .clickable { showBack = !showBack },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            offsetX > FlashcardScheduler.SWIPE_COLOR_THRESHOLD -> MaterialTheme.colorScheme.primaryContainer
                            offsetX < -FlashcardScheduler.SWIPE_COLOR_THRESHOLD -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Front: strip properties from content display
                            val displayContent = card.content.lines()
                                .filterNot { it.contains("::") }
                                .joinToString("\n")
                                .trim()
                            Text(
                                displayContent.ifBlank { card.content },
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (!showBack) {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    "Tap to reveal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            } else {
                                // Back: show child blocks hint (we don't have them here easily, show "Answer revealed")
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    "How did you do?",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Pass/Fail buttons (shown after revealing back)
            if (showBack) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = ::onFail,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Again")
                    }
                    Button(
                        onClick = ::onPass,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Good")
                    }
                }
            } else {
                // Swipe hint
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("← Fail", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    Text("Pass →", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
            }
        }
    }
}
