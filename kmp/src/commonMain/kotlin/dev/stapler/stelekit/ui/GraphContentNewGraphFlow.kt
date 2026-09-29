// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.performance.getDeviceInfo
import dev.stapler.stelekit.ui.components.NewGraphDialog
import dev.stapler.stelekit.ui.components.PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY
import dev.stapler.stelekit.ui.components.PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY
import dev.stapler.stelekit.ui.components.PlainGraphAppOwnedWarningDialog
import dev.stapler.stelekit.ui.components.UnifiedLocationPicker
import dev.stapler.stelekit.ui.components.isValidGraphFolderName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns "New graph…" (Story 2.2.1/ADR-003) for [GraphContent]: name/description/location state
 * that must survive the app-storage location picker and the plain-graph-in-AppOwned-storage
 * warning round trip, [onStartNewGraphFlow] (shared by the sidebar's "New graph…" action and, on
 * platforms with no "open an existing folder" concept — `AddGraphFlowMode.ShowNameDialog` — by
 * "Open local folder..." too, via [GraphContentLeftSidebarInputs.onStartNewGraphFlow]), and the
 * three dialogs ([NewGraphDialog], [UnifiedLocationPicker], [PlainGraphAppOwnedWarningDialog])
 * that step through it, rendered by [GraphContentNewGraphDialogs].
 */
internal class GraphContentNewGraphFlowController(
    val showNewGraphDialogState: MutableState<Boolean>,
    val newGraphNameState: MutableState<String>,
    val newGraphDescriptionState: MutableState<String>,
    val newGraphParentState: MutableState<String>,
    val newGraphLocationState: MutableState<StorageLocation?>,
    val newGraphPathState: MutableState<String>,
    // Set alongside the StorageLocation.SafFolder returned from the picker's onBrowseRequest —
    // SafFolder.treeUri only carries the tree-root segment (see that lambda's comment below), so
    // the real picked path is stashed here rather than reconstructed from that shorter field.
    val pendingNewGraphSafPathState: MutableState<String>,
    // Story 2.2.1: new-graph "App storage" flow (Android-only for now — gated on
    // fileSystem.supportsAppOwnedStorage). showNewGraphLocationPickerState/
    // pendingNewGraphAppOwnedPathState are the picker dialog's own open-state and its
    // pre-generated "App storage" candidate path (needed up front because UnifiedLocationPicker
    // takes graphId as a constructor param, before the user has chosen a row).
    val pendingNewGraphAppOwnedPathState: MutableState<String>,
    val showNewGraphLocationPickerState: MutableState<Boolean>,
    // Holds the resolved AppOwned location + path awaiting the ADR-003 warning's "Create
    // anyway"/"Go back".
    val pendingPlainGraphWarningState: MutableState<Pair<StorageLocation.AppOwned, String>?>,
    // Tells the shared location-picker/warning dialogs to return to this flow (name + description)
    // instead of creating/opening a graph directly.
    val newGraphFlowState: MutableState<Boolean>,
    val onStartNewGraphFlow: () -> Unit,
)

/** Builds [GraphContentNewGraphFlowController] and its [GraphContentNewGraphFlowController.onStartNewGraphFlow]. */
@Composable
internal fun rememberGraphContentNewGraphFlowController(deps: GraphContentDeps): GraphContentNewGraphFlowController {
    val fileSystem = deps.fileSystem
    val graphManager = deps.graphManager

    val showNewGraphDialogState = remember { mutableStateOf(false) }
    val newGraphNameState = remember { mutableStateOf("") }
    val newGraphDescriptionState = remember { mutableStateOf("") }
    val newGraphParentState = remember { mutableStateOf("") }
    val newGraphLocationState = remember { mutableStateOf<StorageLocation?>(null) }
    val newGraphPathState = remember { mutableStateOf("") }
    val pendingNewGraphSafPathState = remember { mutableStateOf("") }
    val pendingNewGraphAppOwnedPathState = remember { mutableStateOf("") }
    val showNewGraphLocationPickerState = remember { mutableStateOf(false) }
    val pendingPlainGraphWarningState = remember {
        mutableStateOf<Pair<StorageLocation.AppOwned, String>?>(null)
    }
    val newGraphFlowState = remember { mutableStateOf(false) }

    // Shared by the sidebar's "New graph..." action and, on platforms with no "open an existing
    // folder" concept (AddGraphFlowMode.ShowNameDialog), by "Open local folder..." too — see
    // GraphContentLeftSidebar's onAddGraph.
    fun startNewGraphFlow() {
        newGraphFlowState.value = true
        newGraphNameState.value = ""
        newGraphDescriptionState.value = ""
        newGraphLocationState.value = null
        newGraphPathState.value = ""
        if (addGraphFlowMode(fileSystem) == AddGraphFlowMode.ShowLocationPicker) {
            pendingNewGraphAppOwnedPathState.value = fileSystem.newAppOwnedGraphPath()
            newGraphPathState.value = pendingNewGraphAppOwnedPathState.value
            newGraphLocationState.value = StorageLocation.AppOwned(
                graphManager.graphIdFromPath(fileSystem.expandTilde(newGraphPathState.value)).value
            )
        } else {
            newGraphParentState.value = fileSystem.getDefaultGraphPath().substringBeforeLast('/')
        }
        showNewGraphDialogState.value = true
    }

    return GraphContentNewGraphFlowController(
        showNewGraphDialogState = showNewGraphDialogState,
        newGraphNameState = newGraphNameState,
        newGraphDescriptionState = newGraphDescriptionState,
        newGraphParentState = newGraphParentState,
        newGraphLocationState = newGraphLocationState,
        newGraphPathState = newGraphPathState,
        pendingNewGraphSafPathState = pendingNewGraphSafPathState,
        pendingNewGraphAppOwnedPathState = pendingNewGraphAppOwnedPathState,
        showNewGraphLocationPickerState = showNewGraphLocationPickerState,
        pendingPlainGraphWarningState = pendingPlainGraphWarningState,
        newGraphFlowState = newGraphFlowState,
        onStartNewGraphFlow = ::startNewGraphFlow,
    )
}

/**
 * Renders the three dialogs that step through "New graph…": [NewGraphDialog] itself,
 * [UnifiedLocationPicker] (Story 2.2.1's app-storage location choice), and
 * [PlainGraphAppOwnedWarningDialog] (ADR-003's no-backup warning). Split out from [GraphContent]
 * purely for length/nesting — see ADR-001-style decomposition rationale at [GraphContent]'s own
 * doc. Pure relocation of the original inline dialogs: no behavior change.
 */
@Composable
internal fun GraphContentNewGraphDialogs(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    scope: CoroutineScope,
    controller: GraphContentNewGraphFlowController,
) {
    val fileSystem = deps.fileSystem
    val graphManager = deps.graphManager

    var showNewGraphDialog by controller.showNewGraphDialogState
    var newGraphName by controller.newGraphNameState
    var newGraphDescription by controller.newGraphDescriptionState
    var newGraphParent by controller.newGraphParentState
    var newGraphLocation by controller.newGraphLocationState
    var newGraphPath by controller.newGraphPathState
    var pendingNewGraphSafPath by controller.pendingNewGraphSafPathState
    var pendingNewGraphAppOwnedPath by controller.pendingNewGraphAppOwnedPathState
    var showNewGraphLocationPicker by controller.showNewGraphLocationPickerState
    var pendingPlainGraphWarning by controller.pendingPlainGraphWarningState
    var newGraphFlow by controller.newGraphFlowState

    // Story 2.2.1: replaces the immediate SAF-picker call in onAddGraph above, whenever
    // fileSystem.supportsAppOwnedStorage (Android today) — see that callback's comment.
    // createNewGraph is shared by both this picker's SafFolder branch (no warning needed) and the
    // AppOwned warning's "Create anyway" below.
    val createNewGraph: (String, StorageLocation?) -> Unit = { path, location ->
        val name = newGraphName.takeIf { newGraphFlow }
        val description = newGraphDescription.takeIf { newGraphFlow } ?: ""
        scope.launch {
            graphManager.switchGraph(graphManager.addGraph(path, location, name, description))
            newGraphFlow = false
        }
    }
    if (showNewGraphDialog) {
        val nameOk = isValidGraphFolderName(newGraphName)
        val newGraphMode = addGraphFlowMode(fileSystem)
        NewGraphDialog(
            name = newGraphName,
            onNameChange = { newGraphName = it },
            description = newGraphDescription,
            onDescriptionChange = { newGraphDescription = it },
            canCreate = nameOk && (newGraphMode != AddGraphFlowMode.ImmediateNativePicker || newGraphParent.isNotBlank()),
            onDismiss = { showNewGraphDialog = false; newGraphFlow = false },
            onCreate = {
                showNewGraphDialog = false
                val location = newGraphLocation
                when {
                    location is StorageLocation.AppOwned ->
                        pendingPlainGraphWarning = location to newGraphPath
                    location != null -> createNewGraph(newGraphPath, location)
                    else -> {
                        val path = newGraphPathFor(newGraphMode, newGraphParent, newGraphName) ?: return@NewGraphDialog
                        if (newGraphMode == AddGraphFlowMode.ShowNameDialog) {
                            createNewGraph(path, null)
                        } else if (fileSystem.directoryExists(path)) {
                            newGraphFlow = false
                            viewModel.sendSnackbar("A folder named \"${newGraphName.trim()}\" already exists there")
                        } else if (!fileSystem.createDirectory(path)) {
                            newGraphFlow = false
                            viewModel.sendSnackbar("Couldn't create $path")
                        } else {
                            createNewGraph(path, null)
                        }
                    }
                }
            },
            location = {
                if (newGraphMode == AddGraphFlowMode.ShowNameDialog) {
                    Text(
                        "Stored in your browser's private storage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (newGraphMode == AddGraphFlowMode.ShowLocationPicker) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Save to", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (newGraphLocation is StorageLocation.AppOwned) "App storage (default)" else "Folder you picked",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        TextButton(onClick = {
                            showNewGraphDialog = false
                            showNewGraphLocationPicker = true
                        }) { Text("Change…") }
                    }
                } else {
                    OutlinedTextField(
                        value = newGraphParent,
                        onValueChange = { newGraphParent = it },
                        label = { Text("Parent folder") },
                        supportingText = { Text("Creates a folder named after the graph inside it.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                fileSystem.requestDirectoryPickerNow()
                                scope.launch { fileSystem.pickDirectoryAsync()?.let { newGraphParent = it } }
                            }) { Icon(Icons.Default.FolderOpen, contentDescription = "Browse for parent folder") }
                        },
                    )
                }
            },
        )
    }
    if (showNewGraphLocationPicker) {
        UnifiedLocationPicker(
            title = "Choose where to keep this graph",
            graphId = graphManager.graphIdFromPath(
                fileSystem.expandTilde(pendingNewGraphAppOwnedPath)
            ).value,
            appStorageSubtitle = appStorageSubtitleFor(getDeviceInfo().platform),
            platformCapabilities = fileSystem.supportsNativeDirectoryPicker,
            onBrowseClick = {
                // Must run synchronously here, not inside onBrowseRequest's scope.launch — see
                // UnifiedLocationPicker's onBrowseClick doc.
                fileSystem.requestDirectoryPickerNow()
            },
            onBrowseRequest = {
                val path = fileSystem.pickDirectoryAsync()
                path?.let {
                    val expanded = fileSystem.expandTilde(it)
                    pendingNewGraphSafPath = expanded
                    // Only the tree-root segment — see the matching comment at GitSetupScreen's
                    // own onBrowseRequest (Story 2.2.2).
                    val treeUri = expanded.removePrefix("saf://").substringBefore("/")
                    StorageLocation.SafFolder(graphManager.graphIdFromPath(expanded).value, treeUri)
                }
            },
            onConfirm = { location ->
                showNewGraphLocationPicker = false
                if (newGraphFlow) {
                    newGraphLocation = location
                    newGraphPath = if (location is StorageLocation.SafFolder) pendingNewGraphSafPath else pendingNewGraphAppOwnedPath
                    showNewGraphDialog = true
                    return@UnifiedLocationPicker
                }
                when (location) {
                    is StorageLocation.AppOwned ->
                        // ADR-003: a plain (non-git) graph in AppOwned storage has no backup at
                        // all — warn before creating, not after.
                        pendingPlainGraphWarning = location to pendingNewGraphAppOwnedPath
                    is StorageLocation.SafFolder ->
                        createNewGraph(pendingNewGraphSafPath, location)
                    else ->
                        Unit
                }
            },
            onDismiss = { showNewGraphLocationPicker = false; if (newGraphFlow) showNewGraphDialog = true },
        )
    }

    pendingPlainGraphWarning?.let { (location, path) ->
        val zipExporter = rememberGraphZipExporter()
        // ADR-003's Amendment: Android and Web describe a different concrete consequence
        // (uninstalling the app vs. clearing site data) — see getDeviceInfo's "platform" field
        // convention (SloChecker.diskThresholdsFor).
        val warningCopy = remember { getDeviceInfo().platform }.let { platform ->
            if (platform == "Android") PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY
            else PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY
        }
        PlainGraphAppOwnedWarningDialog(
            bodyText = warningCopy,
            onExportZip = zipExporter?.let { exporter ->
                {
                    exporter.export(fileSystem, path, "stelekit-graph").isRight()
                }
            },
            onCreateAnyway = {
                pendingPlainGraphWarning = null
                createNewGraph(path, location)
            },
            onGoBack = {
                // ux.md Surface 11: returns to an editable New-graph dialog rather than all the
                // way back to the sidebar.
                pendingPlainGraphWarning = null
                if (newGraphFlow) showNewGraphDialog = true else showNewGraphLocationPicker = true
            },
        )
    }
}
