package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

/**
 * A bottom-strip overlay panel for navigating all page-name suggestions visible on
 * the current screen. Shows one suggestion at a time with prev/next/link/skip controls.
 *
 * Keyboard shortcuts (when panel is focused):
 *   Enter / L      → Link current suggestion as [[Page Name]]
 *   N / S          → Skip (dismiss) current suggestion
 *   ← / ,          → Previous suggestion
 *   → / .          → Next suggestion
 *   Escape         → Close panel
 */
@Composable
internal fun SuggestionNavigatorPanel(
    suggestions: List<SuggestionItem>,
    currentIndex: Int,
    onLink: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return

    val current = suggestions[currentIndex]
    val total = suggestions.size
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.L -> { onLink(); true }
                    Key.N, Key.S -> { onSkip(); true }
                    Key.DirectionLeft, Key.Comma -> { onPrevious(); true }
                    Key.DirectionRight, Key.Period -> { onNext(); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            // Header: progress counter + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Suggestion ${currentIndex + 1} of $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Current suggestion
            Text(
                text = "Link as [[${current.canonicalName}]]?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action row: ← Skip Link →
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPrevious,
                    enabled = currentIndex > 0,
                ) {
                    Text("◀")
                }

                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Skip  (N)")
                }

                Button(
                    onClick = onLink,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Link  (↵)")
                }

                TextButton(
                    onClick = onNext,
                    enabled = currentIndex < total - 1,
                ) {
                    Text("▶")
                }
            }

            // Keyboard hint
            Text(
                text = "◀/▶ navigate  •  ↵ or L link  •  N or S skip  •  Esc close",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
