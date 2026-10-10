package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.capture.InboxItem
import dev.stapler.stelekit.capture.InboxItemStatus
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.QuarantinedShare
import dev.stapler.stelekit.capture.RetryResult
import dev.stapler.stelekit.capture.ShareInboxState
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.ui.ShareInboxActions
import dev.stapler.stelekit.ui.ShareInboxUi
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private const val SNIPPET_CHARS = 24
private const val CONFIRM_CHARS = 80

private fun shares(n: Int) = if (n == 1) "1 share" else "$n shares"

/** "2 shares queued for Work graph"; null when nothing is queued or unreadable. */
fun queuedSharesSummary(state: ShareInboxState, graphNameOf: (InboxSlot) -> String): String? {
    val items = state.items
    if (items.isEmpty()) return if (state.quarantinedCount > 0) "${shares(state.quarantinedCount)} couldn't be read" else null
    val slots = items.map { it.slot }.distinct()
    return when {
        slots.size == 1 && slots[0] is InboxSlot.Unassigned -> "${shares(items.size)} waiting for a graph"
        slots.size == 1 -> "${shares(items.size)} queued for ${graphNameOf(slots[0])}"
        else -> "${shares(items.size)} queued for ${slots.size} graphs"
    }
}

/** The once-per-cold-start snackbar text; null when the inbox has no items. */
fun queuedSharesNotice(state: ShareInboxState, graphNameOf: (InboxSlot) -> String): String? {
    val items = state.items
    if (items.isEmpty()) return null
    val verb = if (items.size == 1) "is" else "are"
    val slots = items.map { it.slot }.distinct()
    return when {
        slots.size == 1 && slots[0] is InboxSlot.Unassigned -> "${shares(items.size)} $verb waiting for a graph"
        slots.size == 1 -> "${shares(items.size)} $verb queued for ${graphNameOf(slots[0])}"
        else -> "${shares(items.size)} $verb queued across ${slots.size} graphs"
    }
}

/** "Today 09:14", "Yesterday 18:02", else "2026-10-01 07:30". */
fun queuedAtLabel(createdAtEpochMs: Long, nowEpochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val at = Instant.fromEpochMilliseconds(createdAtEpochMs).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowEpochMs).toLocalDateTime(zone).date
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    return when (at.date.toEpochDays() - today.toEpochDays()) {
        0L -> "Today $time"
        -1L -> "Yesterday $time"
        else -> "${at.date} $time"
    }
}

/** Display names for inbox slots; a removed graph falls back to its id so the item is still identifiable. */
fun shareGraphNameOf(graphs: List<GraphInfo>): (InboxSlot) -> String = { slot ->
    when (slot) {
        InboxSlot.Unassigned -> "the first graph you create"
        is InboxSlot.Graph -> graphs.firstOrNull { it.id == slot.id }?.displayName ?: "a removed graph (${slot.id.value})"
    }
}

private fun snippet(text: String?, max: Int): String {
    val flat = (text ?: "").replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max).trimEnd() + "..."
}

/** Persistent indicator for the graph switcher: icon plus text, never colour alone. Hidden when nothing is queued. */
@Composable
fun QueuedSharesBadge(
    state: ShareInboxState,
    graphNameOf: (InboxSlot) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = queuedSharesSummary(state, graphNameOf) ?: return
    AssistChip(
        onClick = onClick,
        label = { Text(summary) },
        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            role = Role.Button
            contentDescription = "$summary. Opens queued shares"
        },
    )
}

/** Badge fed from [ui]. */
@Composable
fun QueuedSharesBadge(ui: ShareInboxUi, graphNameOf: (InboxSlot) -> String, modifier: Modifier = Modifier) {
    val state by ui.state.collectAsState()
    QueuedSharesBadge(state, graphNameOf, ui::openPanel, modifier)
}

/** The panel in a dialog, shown while [ShareInboxUi.panelOpen]. */
@Composable
fun ShareInboxPanelHost(ui: ShareInboxUi, graphNameOf: (InboxSlot) -> String) {
    val open by ui.panelOpen.collectAsState()
    if (!open) return
    val state by ui.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = ui::closePanel,
        confirmButton = { TextButton(onClick = ui::closePanel) { Text("Close") } },
        text = {
            ShareInboxPanelContent(state, graphNameOf, ui, onCopyToClipboard = { clipboard.setText(AnnotatedString(it)) })
        },
    )
}

/** Shows the queued-shares snackbar once per cold start; View opens the panel. */
@Composable
fun ShareInboxStartNotice(
    ui: ShareInboxUi,
    graphNameOf: (InboxSlot) -> String,
    snackbarHostState: SnackbarHostState,
    gate: StartNoticeGate = StartNoticeGate.Process,
) = ShareInboxStartNotice(ui, ui::refresh, ui::openPanel, graphNameOf, snackbarHostState, gate)

/** [loadState] must return the state after the inbox recovered from disk. */
@Composable
fun ShareInboxStartNotice(
    key: Any,
    loadState: suspend () -> ShareInboxState,
    onView: () -> Unit,
    graphNameOf: (InboxSlot) -> String,
    snackbarHostState: SnackbarHostState,
    gate: StartNoticeGate = StartNoticeGate.Process,
) {
    val currentLoad by rememberUpdatedState(loadState)
    val currentView by rememberUpdatedState(onView)
    val currentNames by rememberUpdatedState(graphNameOf)
    LaunchedEffect(key) {
        if (!gate.tryShow()) return@LaunchedEffect
        val text = queuedSharesNotice(currentLoad(), currentNames) ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(text, actionLabel = "View", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) currentView()
    }
}

/** One-shot latch so the notice appears once per process even if the host recomposes or is recreated. */
class StartNoticeGate {
    private var shown = false

    fun tryShow(): Boolean = !shown.also { shown = true }

    companion object {
        val Process = StartNoticeGate()
    }
}

/**
 * The queued shares, one merged accessibility node per item with Copy text / Discard / Retry now
 * as custom actions (UX S13). Discard asks first, quoting the opening 80 characters, and offers
 * Copy text there too, so text is never lost without the chance to rescue it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareInboxPanelContent(
    state: ShareInboxState,
    graphNameOf: (InboxSlot) -> String,
    actions: ShareInboxActions,
    onCopyToClipboard: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<InboxItem?>(null) }
    var focusAfterRemoval by remember { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(focusAfterRemoval, state.items) {
        val id = focusAfterRemoval ?: return@LaunchedEffect
        if (state.items.any { it.captureId == id }) {
            requesters[id]?.requestFocus()
            focusAfterRemoval = null
        }
    }

    fun copy(item: InboxItem) {
        scope.launch {
            val text = actions.copyText(item)
            if (text == null) {
                status = "Couldn't recover the text of this share"
            } else {
                onCopyToClipboard(text)
                status = "Copied the share for ${graphNameOf(item.slot)}"
            }
        }
    }

    fun retry(item: InboxItem) {
        scope.launch {
            val graph = graphNameOf(item.slot)
            status = when (actions.retryNow(item)) {
                RetryResult.Drained -> "Added to $graph"
                RetryResult.Kept -> "Couldn't add it to $graph yet. The share is still queued"
                RetryResult.NotReady -> "$graph isn't open yet. The share is still queued"
                RetryResult.NotFound -> "That share is no longer queued"
            }
        }
    }

    fun discard(item: InboxItem) {
        val list = state.items
        val i = list.indexOfFirst { it.captureId == item.captureId && it.slot == item.slot }
        focusAfterRemoval = (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.captureId
        confirming = null
        scope.launch {
            status = if (actions.discard(item)) "Discarded the share for ${graphNameOf(item.slot)}"
            else "Couldn't discard the share"
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Queued shares", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val summary = queuedSharesSummary(state, graphNameOf) ?: "No queued shares"
        Text(summary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.items.isNotEmpty()) {
            Text(
                "A share waits here until its graph is open or reachable.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            typedItems(state.items, key = { "${it.slot.dirName}/${it.captureId}" }) { item ->
                val requester = requesters.getOrPut(item.captureId) { FocusRequester() }
                QueuedShareRow(
                    item, graphNameOf(item.slot), nowEpochMs(), requester,
                    onCopy = { copy(item) }, onDiscard = { confirming = item }, onRetry = { retry(item) },
                )
                HorizontalDivider()
            }
            typedItems(state.quarantined, key = { "q/${it.fileName}" }) { share ->
                QuarantinedRow(share) {
                    share.recoverableText?.let {
                        onCopyToClipboard(it)
                        status = "Copied the share that couldn't be read"
                    }
                }
                HorizontalDivider()
            }
        }
    }

    confirming?.let { item ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Discard this share?") },
            text = {
                Column {
                    Text("'${snippet(item.text, CONFIRM_CHARS)}'")
                    Text("Copy the text first if you might still need it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { discard(item) }) { Text("Discard") } },
            dismissButton = {
                FlowRow {
                    TextButton(onClick = { copy(item) }) { Text("Copy text") }
                    TextButton(onClick = { confirming = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueuedShareRow(
    item: InboxItem,
    graph: String,
    nowEpochMs: Long,
    requester: FocusRequester,
    onCopy: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
) {
    val time = queuedAtLabel(item.createdAtEpochMs, nowEpochMs)
    if (item.status is InboxItemStatus.NeedsNewerApp) {
        val description = "Queued share for $graph needs a newer app version. Kept untouched, $time"
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(requester).focusable()
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Text("Needs a newer app version")
            Text("For $graph, $time. Kept untouched until you update the app.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val description = "Queued share for $graph: '${snippet(item.text, SNIPPET_CHARS)}', $time"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(requester).focusable()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Copy text for $graph") { onCopy(); true },
                    CustomAccessibilityAction("Discard share for $graph") { onDiscard(); true },
                    CustomAccessibilityAction("Retry now for $graph") { onRetry(); true },
                )
            },
    ) {
        Text("'${snippet(item.text, SNIPPET_CHARS)}'")
        Text("$graph, $time", style = MaterialTheme.typography.bodySmall)
        item.lastError?.let { Text("Not added yet: $it", style = MaterialTheme.typography.bodySmall) }
        FlowRow {
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy text") }
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") }
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry now") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuarantinedRow(share: QuarantinedShare, onCopy: () -> Unit) {
    val recoverable = share.recoverableText != null
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable()
            .semantics(mergeDescendants = true) {
                contentDescription = "1 share couldn't be read" + if (recoverable) ". Its text can be copied" else ""
                if (recoverable) customActions = listOf(CustomAccessibilityAction("Copy text") { onCopy(); true })
            },
    ) {
        Text("1 share couldn't be read")
        if (recoverable) {
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy text") }
        }
    }
}
