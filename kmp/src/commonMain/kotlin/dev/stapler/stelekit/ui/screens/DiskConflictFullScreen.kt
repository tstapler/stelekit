package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.PlatformBackHandler
// `diff` is a top-level Kotlin function in this package (compiled to a `DiffUtils` facade class
// via @file:JvmName on the JVM target only) — there is no Kotlin-visible `DiffUtils` object/class,
// so it must be imported and called as a plain function, not as `DiffUtils.diff(...)`.
import io.github.petertrr.diffutils.diff as diffLines
import io.github.petertrr.diffutils.patch.Delta
import io.github.petertrr.diffutils.patch.DeltaType
import io.github.petertrr.diffutils.patch.Patch

/**
 * Full-screen "view full comparison" escape hatch for a [dev.stapler.stelekit.ui.DiskConflict],
 * reached from [dev.stapler.stelekit.ui.components.DiskConflictDialog]'s "View full comparison"
 * button. This is a real line-diff (via kotlin-multiplatform-diff), unlike the 200-char preview
 * shown inline in the dialog.
 *
 * [onDismiss] returns to the still-open [dev.stapler.stelekit.ui.components.DiskConflictDialog] —
 * this screen never mutates [dev.stapler.stelekit.ui.AppState.diskConflict] itself, only the
 * visibility flag that gates which of the two surfaces renders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskConflictFullScreen(
    localContent: String,
    diskContent: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val diffState = remember(localContent, diskContent) { computeDiskDiffState(localContent, diskContent) }

    // Intercept the Android system back gesture/predictive-back so it goes through onDismiss
    // rather than bypassing it — otherwise diskConflictViewFullVisible could get stuck true
    // while the screen visually disappears, breaking the "return to the dialog" guarantee.
    PlatformBackHandler(enabled = true) {
        onDismiss()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Compare versions")
                        Text(
                            "Closing returns to the conflict dialog",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close comparison",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (diffState) {
                is DiskDiffState.Identical -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No differences — the disk version matches your edit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is DiskDiffState.NoLocalEdit -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            "(no local edit to compare)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = diskContent,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                is DiskDiffState.Different -> {
                    val lines = remember(diffState) { buildDiffLines(diffState.patch, localContent.lines()) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(lines) { line ->
                            DiffLineRow(line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLineItem) {
    val backgroundColor = when (line.kind) {
        DiffLineKind.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        DiffLineKind.ADDED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        DiffLineKind.UNCHANGED -> Color.Transparent
    }
    val marker = when (line.kind) {
        DiffLineKind.REMOVED -> "- "
        DiffLineKind.ADDED -> "+ "
        DiffLineKind.UNCHANGED -> "  "
    }
    Text(
        text = marker + line.text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** The three possible states of a disk-vs-local comparison, computed without Compose. */
sealed class DiskDiffState {
    /** [localContent] is blank — there is no local edit to compare against the disk version. */
    data object NoLocalEdit : DiskDiffState()

    /** Local and disk content are byte-for-byte identical — a known spurious-conflict race. */
    data object Identical : DiskDiffState()

    /** A real difference exists; [patch] holds the computed line-level diff. */
    data class Different(val patch: Patch<String>) : DiskDiffState()
}

/**
 * Pure function — computes which of the three [DiskDiffState] cases applies, and the diff
 * itself when applicable. Independently testable without Compose.
 */
fun computeDiskDiffState(localContent: String, diskContent: String): DiskDiffState = when {
    localContent.isBlank() -> DiskDiffState.NoLocalEdit
    localContent == diskContent -> DiskDiffState.Identical
    else -> DiskDiffState.Different(diffLines(localContent.lines(), diskContent.lines()))
}

internal enum class DiffLineKind { UNCHANGED, REMOVED, ADDED }

internal data class DiffLineItem(val text: String, val kind: DiffLineKind)

/**
 * Reconstructs the full rendered line sequence (unchanged + removed + added) from a [Patch]
 * whose deltas only cover the changed regions, filling the gaps between deltas with unchanged
 * lines taken from [localLines] (identical to the disk lines at those positions by definition).
 * Pure function — independently testable without Compose.
 */
internal fun buildDiffLines(patch: Patch<String>, localLines: List<String>): List<DiffLineItem> {
    val items = mutableListOf<DiffLineItem>()
    var cursor = 0
    val deltas: List<Delta<String>> = patch.deltas.sortedBy { it.source.position }
    for (delta in deltas) {
        val sourcePosition = delta.source.position
        while (cursor < sourcePosition) {
            items += DiffLineItem(localLines[cursor], DiffLineKind.UNCHANGED)
            cursor++
        }
        when (delta.type) {
            DeltaType.DELETE, DeltaType.CHANGE -> {
                delta.source.lines.forEach { items += DiffLineItem(it, DiffLineKind.REMOVED) }
            }
            else -> Unit
        }
        when (delta.type) {
            DeltaType.INSERT, DeltaType.CHANGE -> {
                delta.target.lines.forEach { items += DiffLineItem(it, DiffLineKind.ADDED) }
            }
            else -> Unit
        }
        cursor = sourcePosition + delta.source.lines.size
    }
    while (cursor < localLines.size) {
        items += DiffLineItem(localLines[cursor], DiffLineKind.UNCHANGED)
        cursor++
    }
    return items
}
