// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.launch

/** Copy shown under "Browse…" before a folder has been picked (`design/ux.md` Surface 1 wireframe). */
private const val BROWSE_DEFAULT_SUBTITLE = "Pick a folder on your device"

/**
 * Stable test tags for the two pinned rows — used by [UnifiedLocationPicker]'s own tests and by
 * integration tests in Epics 2.2/2.3 that embed this composable.
 */
const val UnifiedLocationPickerAppStorageRowTag = "unified-location-picker-app-storage-row"
const val UnifiedLocationPickerBrowseRowTag = "unified-location-picker-browse-row"

/**
 * A user's in-progress choice inside [UnifiedLocationPicker], before "Next" resolves it to a
 * concrete [StorageLocation]. Kept distinct from [StorageLocation] because "App storage" only needs
 * a graph id at resolve time (see [resolve]), while "Browse…" already carries a resolved
 * [StorageLocation] the moment the native picker succeeds.
 */
private sealed interface PickerSelection {
    data object AppStorage : PickerSelection
    data class Browsed(val location: StorageLocation) : PickerSelection
}

private fun PickerSelection.resolve(graphId: String): StorageLocation = when (this) {
    PickerSelection.AppStorage -> StorageLocation.AppOwned(graphId)
    is PickerSelection.Browsed -> location
}

private fun StorageLocation.displayLabel(): String = when (this) {
    is StorageLocation.HostFolder -> displayName
    is StorageLocation.SafFolder -> treeUri
    is StorageLocation.DirectAccessFolder -> realPath
    is StorageLocation.AppOwned -> BROWSE_DEFAULT_SUBTITLE
}

/**
 * Runs the platform-supplied "Browse…" picker and folds its result into the next selection state.
 * A `null` result (native picker cancelled) only clears an existing *Browse* selection — it never
 * clobbers an already-chosen "App storage" row (design/ux.md Surface 1 error table).
 */
private suspend fun resolveBrowseSelection(
    current: PickerSelection?,
    onBrowseRequested: suspend () -> StorageLocation?,
): PickerSelection? {
    val picked = onBrowseRequested()
    return when {
        picked != null -> PickerSelection.Browsed(picked)
        current is PickerSelection.Browsed -> null
        else -> current
    }
}

/**
 * Single shared storage-location picker for every creation/relocate surface (Epic 2.1) — one
 * implementation per platform, per requirements.md's "no more than one picker implementation"
 * constraint. Renders a pinned "App storage" row first, always visible, then a "Browse…" row when
 * [platformCapabilities] is true (native folder browse available: always true on Android;
 * conditional on Web per `FileSystem.supportsNativeDirectoryPicker` — omitted entirely, not shown
 * disabled, on Firefox/Safari per `design/ux.md` Surface 1).
 *
 * No row is pre-selected on open, per `research/ux.md` §5's "no default action, must actively
 * choose" pattern — [onConfirm] can only fire once the user taps a row.
 *
 * This composable never calls a platform folder-picker API itself: [onBrowseRequested] is supplied
 * by the platform-specific caller (wired in Epics 2.2/2.3), since only the caller knows whether a
 * picked location should resolve to [StorageLocation.SafFolder] (Android) or
 * [StorageLocation.HostFolder] (Web). A `null` result means the user cancelled the native picker —
 * the "Browse…" row simply reverts to unselected, no error shown, per `design/ux.md`'s
 * error-and-edge-case table.
 *
 * @param appStorageSubtitle the exact platform copy for the "App storage" row's subtitle
 * (`design/ux.md` §2 / plan.md Task 2.1.1b) — supplied by the caller since only it knows whether it
 * is running on Android or Web.
 * @param onBrowseClicked invoked synchronously, in the same Compose click-handler call stack, the
 * instant the "Browse…" row is tapped — *before* [onBrowseRequested] runs inside a coroutine
 * launch. Exists because [onBrowseRequested] is a `suspend` lambda dispatched through that launch,
 * which on wasmJs can lose the browser's "transient user activation" window before
 * `window.showDirectoryPicker()` is ever called (`stack.md` §3,
 * [dev.stapler.stelekit.platform.showDirectoryPickerPromise]'s doc comment). The wasmJs caller
 * wires this to [dev.stapler.stelekit.platform.FileSystem.requestDirectoryPickerNow], which is a
 * synchronous no-op on every other platform — safe to leave as the default no-op there.
 */
@Composable
fun UnifiedLocationPicker(
    title: String,
    graphId: String,
    appStorageSubtitle: String,
    platformCapabilities: Boolean,
    onBrowseRequested: suspend () -> StorageLocation?,
    onConfirm: (StorageLocation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onBrowseClicked: () -> Unit = {},
) {
    var selection by remember { mutableStateOf<PickerSelection?>(null) }
    val scope = rememberCoroutineScope()
    val browseSubtitle = (selection as? PickerSelection.Browsed)?.location?.displayLabel()
        ?: BROWSE_DEFAULT_SUBTITLE

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            LocationPickerRows(
                appStorageSubtitle = appStorageSubtitle,
                browseSubtitle = browseSubtitle,
                platformCapabilities = platformCapabilities,
                selection = selection,
                onSelectAppStorage = { selection = PickerSelection.AppStorage },
                onBrowseClick = {
                    // Must run before scope.launch, not inside it — see onBrowseClicked's doc.
                    onBrowseClicked()
                    scope.launch { selection = resolveBrowseSelection(selection, onBrowseRequested) }
                },
            )
        },
        confirmButton = {
            Button(
                onClick = { selection?.resolve(graphId)?.let(onConfirm) },
                enabled = selection != null,
            ) { Text("Next") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LocationPickerRows(
    appStorageSubtitle: String,
    browseSubtitle: String,
    platformCapabilities: Boolean,
    selection: PickerSelection?,
    onSelectAppStorage: () -> Unit,
    onBrowseClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        LocationPickerRow(
            label = "App storage",
            subtitle = appStorageSubtitle,
            selected = selection == PickerSelection.AppStorage,
            testTag = UnifiedLocationPickerAppStorageRowTag,
            onClick = onSelectAppStorage,
        )
        if (platformCapabilities) {
            LocationPickerRow(
                label = "Browse…",
                subtitle = browseSubtitle,
                selected = selection is PickerSelection.Browsed,
                testTag = UnifiedLocationPickerBrowseRowTag,
                onClick = onBrowseClick,
            )
        }
    }
}

@Composable
private fun LocationPickerRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            // mergeDescendants so a screen reader announces label + subtitle as one unit (AC5),
            // matching this repo's existing row-selection convention (GitSetupScreen.kt's
            // Step1CloneMode / FolderSyncStatusBadge.kt) but with role = Role.Button since these
            // rows resolve immediately to a value rather than toggling within a persistent group.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
