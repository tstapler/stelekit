// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.BrowserPersistence
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.UnsupportedBrowserPersistence
import dev.stapler.stelekit.projectbridge.EngineChangeBus
import dev.stapler.stelekit.projectbridge.ProjectBridgeDeps
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.SessionConfirmation
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.isAvailable
import dev.stapler.stelekit.projectbridge.v2Storage
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.ui.sketch.BrowserStorageUx
import dev.stapler.stelekit.ui.sketch.v2.BackupsSectionModel
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings
import kotlinx.coroutines.launch

/** What the sketch settings section reads and writes; every field is a seam of the project bridge. */
class SketchSettingsPorts(
    val settings: ProjectBridgeSettings,
    val graphId: () -> String?,
    val capabilities: () -> V2Capabilities,
    val acks: ProjectAckStore,
    val quality: QualitySignals,
    val persistence: BrowserPersistence,
    val session: SessionConfirmation,
    val engineChange: EngineChangeBus,
    /** The Backups row and its Delete backups action; null shows neither. */
    val backups: BackupsSectionModel? = null,
)

/** The settings section's ports from the project bridge; null where the bridge keeps no consent records (nothing to show). */
fun ProjectBridgeDeps.sketchSettingsPorts(graphId: () -> String?): SketchSettingsPorts? {
    val acks = acks ?: return null
    val quality = quality ?: return null
    return SketchSettingsPorts(
        settings = settings,
        graphId = graphId,
        capabilities = { V2Capabilities.of(sidecarStore().v2Storage()) },
        acks = acks,
        quality = quality,
        persistence = browserPersistence,
        session = sessionConfirmation,
        engineChange = engineChange,
        backups = GraphBackups(sidecarStore).model(),
    )
}

object SketchSettingsTags {
    const val BROWSER_ROW = "sketch.settings.browser-storage"
    const val BROWSER_ASK = "sketch.settings.browser-storage.ask"
    const val LEARN_DIALOG = "sketch.settings.learn"
}

/**
 * Settings > Sketches (Task 6.2.0c): the "New editor" radio group over [ModeChangePlanner], the confirmation a mode needs, the web
 * "Browser storage" row and the local signals readout. A mode is stored only after the planner's verdict (a confirmation for a
 * widening, none for a narrowing); when [editorOpen] the open editor is told through [EngineChangeBus] and is neither swapped
 * nor closed (UX-69).
 */
@Composable
fun SketchSettingsHost(ports: SketchSettingsPorts, editorOpen: Boolean, modifier: Modifier = Modifier) {
    val graph = ports.graphId() ?: return
    var stored by remember(graph) { mutableStateOf(ports.settings.v2Mode(graph)) }
    var confirm by remember { mutableStateOf<ModeChange.Confirm?>(null) }
    var learn by remember { mutableStateOf(false) }
    val capabilities = ports.capabilities()
    val apply = { mode: V2Mode, confirmed: Boolean ->
        ports.settings.setV2Mode(graph, mode)
        if (confirmed) {
            ports.acks.raiseLevel(mode)
            ports.session.confirmedHere()
        }
        stored = mode
        if (EngineChangeFlow({}, {}).onModeChanged(if (editorOpen) EditorOpen.Yes else EditorOpen.No) != null) ports.engineChange.signal()
    }
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SketchModeSetting(
            ModeControlModel.build(stored, capabilities),
            onChoose = { target ->
                when (val plan = ModeChangePlanner.plan(stored, target, ports.acks.level, capabilities)) {
                    is ModeChange.Store -> apply(plan.mode, false)
                    is ModeChange.Confirm -> confirm = plan
                    ModeChange.NoOp, is ModeChange.Disabled -> Unit
                }
            },
            onLearn = { learn = true },
        )
        BrowserStorage(ports.persistence)
        SketchSignalsReadout(SignalsReadoutModel(ports.quality.adoption(), ports.quality.counts(), stored, ports.quality.recentSessions(), ports.quality.recentSolves()))
        if (capabilities.storage.isAvailable) ports.backups?.let { BackupsSection(it) }
    }
    confirm?.let { plan ->
        EnableV2Confirmation(plan.variant, onConfirm = { confirm = null; apply(plan.mode, true) }, onCancel = { confirm = null }, onLearn = { learn = true })
    }
    if (learn) LearnWhatChanges { learn = false }
}

@Composable
private fun LearnWhatChanges(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(SketchMigrationStrings.Mode.LEARN_WHAT_CHANGES, Modifier.testTag(SketchSettingsTags.LEARN_DIALOG)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { EnableVariant.On.copy.paragraphs.forEach { Text(it) } } },
        confirmButton = { TextButton(onClick = onClose) { Text(SketchMigrationStrings.Failure.CLOSE) } },
    )
}

/** The web-only "Browser storage" row; absent on every platform whose persistence port is [UnsupportedBrowserPersistence]. */
@Composable
private fun BrowserStorage(persistence: BrowserPersistence) {
    if (persistence === UnsupportedBrowserPersistence) return
    var state by remember { mutableStateOf(PersistenceState.NotRequested) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val row = if (asking) BrowserStorageUx.askingRow() else BrowserStorageUx.row(state)
    Column(Modifier.testTag(SketchSettingsTags.BROWSER_ROW), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SketchMigrationStrings.Mode.BROWSER_STORAGE)
        Text(row.label)
        row.statusLine?.let { Text(it) }
        row.actionLabel?.let { label ->
            TextButton(
                onClick = {
                    asking = true
                    scope.launch {
                        state = persistence.request()
                        asking = false
                    }
                },
                modifier = Modifier.testTag(SketchSettingsTags.BROWSER_ASK),
            ) { Text(label) }
        }
    }
}
