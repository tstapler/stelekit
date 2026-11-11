// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.COULDNT_READ_SETTINGS
import dev.stapler.stelekit.git.GRAPH_SEARCH_THRESHOLD
import dev.stapler.stelekit.git.aboutEveryPhrase
import dev.stapler.stelekit.git.graphRowDescription
import dev.stapler.stelekit.git.graphRowSummary
import dev.stapler.stelekit.git.kindSummary
import dev.stapler.stelekit.git.networkLabel
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.ui.components.typedItems

/** A graph the Background tasks list can configure; [name] is the display name, never the raw id. */
data class BackgroundTaskGraph(val id: String, val name: String)

/** Everything the dialog needs to show [BackgroundTasksSection]; null hides the section. */
data class BackgroundTasksSettingsState(
    val controller: BackgroundTaskSettingsController,
    val graphs: List<BackgroundTaskGraph>,
    val onAddGraph: () -> Unit,
    /**
     * Why a graph's switch cannot be turned on. Only the active graph's remote and first-sync review are
     * knowable (the config repository is single-open), so other graphs return null and show no reason.
     */
    val switchGate: (kind: TaskKind, graphId: String) -> SwitchGate? = { _, _ -> null },
)

internal const val BACKGROUND_TASKS_TITLE = "Background tasks"
internal const val NO_GRAPHS_TEXT = "No graphs yet. Add a graph to schedule syncing."
internal const val ADD_GRAPH_LABEL = "Add graph"
internal const val DEFAULT_ROW_LABEL = "Default for new graphs"
internal const val SEARCH_LABEL = "Search graphs"
internal const val NO_MATCHES_TEXT = "No matches"
internal const val CLEAR_SEARCH_LABEL = "Clear search"
internal const val BACK_LABEL = "Back"
private const val TARGET_DP = 48
private const val LIST_MAX_HEIGHT_DP = 480
private const val APP_WIDE_SCOPE = ""

private sealed interface Destination {
    data object List : Destination
    data class Kind(val kind: TaskKind) : Destination
    data class Defaults(val kind: TaskKind) : Destination
    data class Graph(val kind: TaskKind, val graph: BackgroundTaskGraph) : Destination
    data class AppWide(val kind: TaskKind) : Destination

    /** The stable identity of the row that opens this screen, for restoring focus on Back. */
    fun openerId(): String = when (this) {
        List -> ""
        is Kind -> "kind:${kind.id}"
        is Defaults -> "defaults"
        is Graph -> "graph:${graph.id}"
        is AppWide -> "kind:${kind.id}"
    }
}

/**
 * Settings > Background tasks: one summary line per kind, graph rows only after drilling in. Hidden
 * (not disabled) when the scheduler cannot schedule work (iOS, Web). Graph rows are a lazy list with no
 * hard cap; a search field appears from [GRAPH_SEARCH_THRESHOLD] graphs.
 */
@Composable
fun BackgroundTasksSection(
    controller: BackgroundTaskSettingsController,
    graphs: List<BackgroundTaskGraph>,
    onAddGraph: () -> Unit,
    modifier: Modifier = Modifier,
    switchGate: (kind: TaskKind, graphId: String) -> SwitchGate? = { _, _ -> null },
) {
    if (!controller.capabilities.schedulesWork) return
    var destination by remember { mutableStateOf<Destination>(Destination.List) }
    var refresh by remember { mutableIntStateOf(0) }
    var focusOpener by remember { mutableStateOf<String?>(null) }

    fun open(next: Destination) {
        focusOpener = null
        destination = next
    }

    fun back(to: Destination) {
        focusOpener = destination.openerId()
        destination = to
        refresh++
    }

    SettingsSection(BACKGROUND_TASKS_TITLE) {
        Column(modifier = modifier.fillMaxWidth()) {
            when (val d = destination) {
                Destination.List -> KindList(controller, graphs, refresh, focusOpener, onAddGraph, ::open)
                is Destination.Kind -> KindScreen(controller, d.kind, graphs, refresh, focusOpener, { back(Destination.List) }, ::open)
                is Destination.Defaults -> {
                    BackRow { back(Destination.Kind(d.kind)) }
                    val following = remember(graphs, refresh) { graphs.map { it.id } }
                    TaskSettingsPanel(
                        controller, TaskKey(d.kind, TaskScope(APP_WIDE_SCOPE)), "${controller.titleOf(d.kind)} - $DEFAULT_ROW_LABEL",
                        target = PanelTarget.Defaults(following),
                    )
                }
                is Destination.Graph -> {
                    BackRow { back(Destination.Kind(d.kind)) }
                    TaskSettingsPanel(
                        controller, TaskKey(d.kind, TaskScope(d.graph.id)), "${controller.titleOf(d.kind)} - ${d.graph.name}",
                        switchGate = switchGate(d.kind, d.graph.id),
                        onChange = { refresh++ },
                    )
                }
                is Destination.AppWide -> {
                    BackRow { back(Destination.List) }
                    TaskSettingsPanel(
                        controller, TaskKey(d.kind, TaskScope(APP_WIDE_SCOPE)), controller.titleOf(d.kind),
                        onChange = { refresh++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text("< $BACK_LABEL") }
}

@Composable
private fun KindList(
    controller: BackgroundTaskSettingsController,
    graphs: List<BackgroundTaskGraph>,
    refresh: Int,
    focusOpener: String?,
    onAddGraph: () -> Unit,
    open: (Destination) -> Unit,
) {
    Column {
        val kinds = controller.kinds()
        val periodic = kinds.filterNot(controller::isOneShot)
        if (periodic.isNotEmpty() && graphs.isEmpty()) {
            Text(NO_GRAPHS_TEXT, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAddGraph, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(ADD_GRAPH_LABEL) }
        }
        kinds.forEach { kind ->
            if (controller.isOneShot(kind)) {
                val summary by produceKindSummary(controller, kind, graphs, refresh)
                NavRow(controller.titleOf(kind), summary, "kind:${kind.id}", focusOpener) { open(Destination.AppWide(kind)) }
            } else if (graphs.isNotEmpty()) {
                val summary by produceKindSummary(controller, kind, graphs, refresh)
                NavRow(controller.titleOf(kind), summary, "kind:${kind.id}", focusOpener) { open(Destination.Kind(kind)) }
            }
        }
    }
}

@Composable
private fun produceKindSummary(
    controller: BackgroundTaskSettingsController,
    kind: TaskKind,
    graphs: List<BackgroundTaskGraph>,
    refresh: Int,
) = androidx.compose.runtime.produceState(initialValue = "", kind, graphs, refresh) {
    value = summarizeKind(controller, kind, graphs)
}

private suspend fun summarizeKind(controller: BackgroundTaskSettingsController, kind: TaskKind, graphs: List<BackgroundTaskGraph>): String {
    val base = controller.baseConfig(kind)
    if (controller.isOneShot(kind)) {
        val loaded = controller.load(TaskKey(kind, TaskScope(APP_WIDE_SCOPE)))
        val network = (loaded as? TaskSettingsLoad.Loaded)?.resolved?.constraints?.network ?: base.constraints.network
        return "${networkLabel(network)} - app-wide"
    }
    var on = 0
    graphs.forEach { g ->
        val loaded = controller.load(TaskKey(kind, TaskScope(g.id)))
        if ((loaded as? TaskSettingsLoad.Loaded)?.entry?.enabled == true) on++
    }
    return kindSummary(base, on, graphs.size)
}

@Composable
private fun NavRow(title: String, summary: String, id: String, focusOpener: String?, description: String? = null, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusOpener) { if (focusOpener == id) runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TARGET_DP.dp)
            .focusRequester(focus)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description ?: listOf(title, summary).filter { it.isNotEmpty() }.joinToString(", ") }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(">", style = MaterialTheme.typography.bodyLarge)
    }
}

private data class GraphRow(val graph: BackgroundTaskGraph, val load: TaskSettingsLoad?)

@Composable
private fun KindScreen(
    controller: BackgroundTaskSettingsController,
    kind: TaskKind,
    graphs: List<BackgroundTaskGraph>,
    refresh: Int,
    focusOpener: String?,
    onBack: () -> Unit,
    open: (Destination) -> Unit,
) {
    Column {
        val rows = remember(kind) { mutableStateMapOf<String, TaskSettingsLoad>() }
        var base by remember(kind) { mutableStateOf<TaskConfig?>(null) }
        var query by remember { mutableStateOf("") }
        LaunchedEffect(kind, graphs, refresh) {
            base = controller.baseConfig(kind)
            graphs.forEach { rows[it.id] = controller.load(TaskKey(kind, TaskScope(it.id))) }
        }
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(kind) { if (focusOpener == null) runCatching { titleFocus.requestFocus() } }

        BackRow(onBack)
        Text(
            controller.titleOf(kind),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() }.padding(bottom = 8.dp),
        )
        val usingDefault = graphs.count { (rows[it.id] as? TaskSettingsLoad.Loaded)?.entry?.overrides == null && rows.containsKey(it.id) }
        DefaultRow(base, usingDefault, rows.size == graphs.size, focusOpener) { open(Destination.Defaults(kind)) }

        if (graphs.size >= GRAPH_SEARCH_THRESHOLD) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(SEARCH_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = SEARCH_LABEL },
            )
        }
        val shown = graphs.filter { it.name.contains(query.trim(), ignoreCase = true) }
        if (shown.isEmpty()) {
            Text(NO_MATCHES_TEXT, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(CLEAR_SEARCH_LABEL) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT_DP.dp)) {
                typedItems(shown, key = { it.id }) { graph ->
                    GraphRowItem(controller, kind, GraphRow(graph, rows[graph.id]), base, focusOpener) { open(Destination.Graph(kind, graph)) }
                }
            }
        }
    }
}

@Composable
private fun DefaultRow(base: TaskConfig?, usingDefault: Int, allLoaded: Boolean, focusOpener: String?, onClick: () -> Unit) {
    val summary = base?.let { "${aboutEveryPhrase(it.schedule.intervalMinutes).replaceFirstChar(Char::uppercase)} - ${networkLabel(it.constraints.network)}" }.orEmpty()
    val cue = if (allLoaded) "Applies to $usingDefault ${if (usingDefault == 1) "graph" else "graphs"}" else ""
    NavRow(DEFAULT_ROW_LABEL, listOf(summary, cue).filter { it.isNotEmpty() }.joinToString(" - "), "defaults", focusOpener, onClick = onClick)
}

@Composable
private fun GraphRowItem(
    controller: BackgroundTaskSettingsController,
    kind: TaskKind,
    row: GraphRow,
    base: TaskConfig?,
    focusOpener: String?,
    onClick: () -> Unit,
) {
    val id = "graph:${row.graph.id}"
    when (val load = row.load) {
        null -> NavRow(row.graph.name, "Loading...", id, focusOpener, onClick = onClick)
        TaskSettingsLoad.ReadFailed -> NavRow(row.graph.name, COULDNT_READ_SETTINGS.substringBefore(". "), id, focusOpener, onClick = onClick)
        is TaskSettingsLoad.Loaded -> {
            val summary = graphRowSummary(load.entry.overrides, base ?: load.base)
            val state = if (load.entry.enabled) "On" else "Off"
            val line = if (load.entry.enabled) "$state - $summary" else state
            val description = graphRowDescription(controller.titleOf(kind), row.graph.name, load.resolved, load.entry.enabled, summary)
            NavRow(row.graph.name, line, id, focusOpener, description, onClick)
        }
    }
}
