// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.screens.LibrarySetupScreen
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** SAF permission was revoked after being granted — folder content is still there, just re-grant. */
@Composable
private fun PermissionRecoveryScreenThemed(
    libraryDisplayName: String?,
    folderPickError: String?,
    onRequestFolder: () -> Unit,
) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        PermissionRecoveryScreen(
            folderName = libraryDisplayName,
            onReconnectFolder = onRequestFolder,
            onChooseDifferentFolder = onRequestFolder,
            errorMessage = folderPickError,
        )
    }
}

/** First launch — no folder chosen yet. */
@Composable
private fun FirstLaunchSetupScreenThemed(folderPickError: String?, onRequestFolder: () -> Unit) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        LibrarySetupScreen(onChooseFolder = onRequestFolder, errorMessage = folderPickError)
    }
}

/**
 * Handles permission gating and graph initialization for [StelekitApp].
 */
@Composable
internal fun permissionGateAndGraphInit(
    fileSystem: FileSystem,
    graphPath: String,
    graphManager: GraphManager,
    scope: CoroutineScope,
): Boolean {
    var currentGraphPath by remember { mutableStateOf(initialGraphPath(graphManager, graphPath)) }
    var permissionGranted by remember { mutableStateOf(fileSystem.hasStoragePermission()) }
    var folderPickError by remember { mutableStateOf<String?>(null) }

    val appLogger = remember { Logger("StelekitApp") }
    val isSafPath = currentGraphPath.startsWith("saf://")

    ObservePermissionRevocationOnResume(
        fileSystem = fileSystem,
        permissionGranted = { permissionGranted },
        onPermissionGrantedChange = { permissionGranted = it },
    )

    if (!permissionGranted && (isSafPath || currentGraphPath.isEmpty())) {
        PermissionGateScreen(fileSystem, currentGraphPath, folderPickError) {
            fileSystem.requestDirectoryPickerNow()
            launchFolderPick(scope, fileSystem, appLogger, FolderPickCallbacks(
                onPathPicked = { currentGraphPath = it },
                onPermissionGrantedChange = { permissionGranted = it },
                onError = { folderPickError = it },
            ))
        }
        return true
    }

    InitializeGraphFromPath(graphManager, currentGraphPath)
    return false
}

@Composable
private fun PermissionGateScreen(
    fileSystem: FileSystem,
    currentGraphPath: String,
    folderPickError: String?,
    onRequestFolder: () -> Unit,
) {
    val isSafPath = currentGraphPath.startsWith("saf://")
    if (isSafPath) {
        PermissionRecoveryScreenThemed(fileSystem.getLibraryDisplayName(), folderPickError, onRequestFolder)
    } else {
        FirstLaunchSetupScreenThemed(folderPickError, onRequestFolder)
    }
}

/**
 * Registers (idempotently) and activates [currentGraphPath].
 */
@Composable
internal fun InitializeGraphFromPath(graphManager: GraphManager, currentGraphPath: String) {
    LaunchedEffect(currentGraphPath) {
        if (currentGraphPath.isNotEmpty() && graphManager.getActiveGraphInfo()?.id != DEMO_GRAPH_ID) {
            val graphId = graphManager.addGraph(currentGraphPath)
            graphManager.switchGraph(graphId)
        }
    }
}

@Composable
private fun ObservePermissionRevocationOnResume(
    fileSystem: FileSystem,
    permissionGranted: () -> Boolean,
    onPermissionGrantedChange: (Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPermissionGranted by rememberUpdatedState(permissionGranted)
    val currentOnPermissionGrantedChange by rememberUpdatedState(onPermissionGrantedChange)

    DisposableEffect(lifecycleOwner) {
        var wasGrantedBeforePause = currentPermissionGranted()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasGrantedBeforePause = currentPermissionGranted()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasGrantedBeforePause) {
                        val stillGranted = fileSystem.hasStoragePermission()
                        if (!stillGranted) {
                            currentOnPermissionGrantedChange(false)
                        }
                    }
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

/** Shown when the user has explicitly removed their only graph — see [StelekitApp]'s call site. */
@Composable
private fun EmptyGraphScreen(
    onCreateGraph: (() -> Unit)?,
    errorMessage: String?,
    onTryDemo: () -> Unit,
) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        dev.stapler.stelekit.ui.screens.EmptyGraphStateScreen(
            onCreateGraph = onCreateGraph,
            onTryDemo = onTryDemo,
            errorMessage = errorMessage
        )
    }
}

/**
 * Shown when the user has explicitly removed their only graph.
 */
@Composable
internal fun emptyGraphGate(
    graphManager: GraphManager,
    fileSystem: FileSystem,
    scope: CoroutineScope,
    activeGraphId: dev.stapler.stelekit.model.GraphId?,
): Boolean {
    val graphsExplicitlyEmptied by graphManager.graphsExplicitlyEmptied.collectAsState()
    if (!(graphsExplicitlyEmptied && activeGraphId == null)) return false

    var emptyStateError by remember { mutableStateOf<String?>(null) }
    val onEmptyGraphError: (String?) -> Unit = { emptyStateError = it }
    EmptyGraphScreen(
        onCreateGraph = buildCreateGraphHandler(fileSystem, graphManager, scope, onEmptyGraphError),
        errorMessage = emptyStateError,
        onTryDemo = { scope.launch { tryLoadDemoGraph(graphManager, onEmptyGraphError) } },
    )
    return true
}
