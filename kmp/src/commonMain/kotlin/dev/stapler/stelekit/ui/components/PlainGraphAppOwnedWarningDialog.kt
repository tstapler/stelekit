// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * ADR-003's exact Android warning copy — single source of truth for both this dialog's Android
 * call site (`App.kt`) and its regression test (`AddGraphAppOwnedTest.kt`, AC9/AC41), so the two
 * can never drift from each other by a stray character.
 */
const val PlainGraphAppOwnedWarningAndroidCopy = "Kept inside SteleKit only — not visible in " +
    "your device's file manager, and permanently deleted if you uninstall the app. There is no " +
    "automatic backup."

/**
 * ADR-003 Amendment's exact Web warning copy — the OPFS-specific consequence (clearing site data,
 * not uninstalling an app) — single source of truth for both Web's call sites (`App.kt`,
 * `GitSetupScreen.kt` never shows this per ux.md's git-clone exemption) and its regression test.
 */
const val PlainGraphAppOwnedWarningWebCopy = "Kept in this browser only — not backed up " +
    "automatically, and lost if you clear site data."

/**
 * Shared dialog shell (ADR-003, `design/ux.md` Surface 11) shown before a **plain (non-git)**
 * graph is created with [dev.stapler.stelekit.model.StorageLocation.AppOwned] — the only storage
 * mode with no backup at all (no git remote, `allowBackup="false"`). Invoked from both Surface 2
 * (Android's new-graph flow, Story 2.2.1) and, later, Surface 2's Web equivalent (Epic 2.3's
 * Story 2.3.3) — [bodyText] and [onExportZip] carry every platform difference so this composable
 * itself never branches on platform.
 *
 * @param bodyText the exact platform copy (ADR-003) — e.g. Android's
 * `"\"$graphName\" will be kept inside SteleKit only — not visible in your device's file manager,
 * and permanently deleted if you uninstall the app. There is no automatic backup."`
 * @param onExportZip triggers the platform's export-and-share flow; null hides the button
 * entirely (only relevant before Epic 2.3 ships Web's own exporter — see
 * [dev.stapler.stelekit.ui.rememberGraphZipExporter]). Returns false (not an exception) on a
 * non-fatal export failure — e.g. share sheet cancelled, disk full — so this dialog can show the
 * inline "Export didn't complete." note without treating it as a crash.
 */
@Composable
fun PlainGraphAppOwnedWarningDialog(
    bodyText: String,
    onExportZip: (suspend () -> Boolean)?,
    onCreateAnyway: () -> Unit,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onGoBack,
        modifier = modifier,
        title = { Text("Before you continue") },
        text = { PlainGraphAppOwnedWarningBody(bodyText, onExportZip) },
        confirmButton = { Button(onClick = onCreateAnyway) { Text("Create anyway") } },
        dismissButton = { TextButton(onClick = onGoBack) { Text("Go back") } },
    )
}

@Composable
private fun PlainGraphAppOwnedWarningBody(bodyText: String, onExportZip: (suspend () -> Boolean)?) {
    var exportInProgress by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        Text(bodyText, style = MaterialTheme.typography.bodyMedium)
        if (onExportZip != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                enabled = !exportInProgress,
                onClick = {
                    exportFailed = false
                    exportInProgress = true
                    scope.launch {
                        exportFailed = !onExportZip()
                        exportInProgress = false
                    }
                },
            ) { Text("Export as .zip") }
            // AC43: never a gate on Create anyway/Go back — a convenience note only.
            if (exportFailed) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Export didn't complete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
