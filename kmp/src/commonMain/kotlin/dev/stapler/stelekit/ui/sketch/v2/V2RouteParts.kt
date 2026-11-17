// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.InteractionState
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeConsentGate
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeOfferActions
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeOfferController
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeOfferHost
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** Test tags of the route's own surfaces. */
object RouteTags {
    const val CLASSIC_NOTICE = "sketch.route.classic-notice"
    const val CLASSIC_NOTICE_DISMISS = "sketch.route.classic-notice.dismiss"
    const val ENGINE_CHANGE = "sketch.route.engine-change"
    const val ENGINE_REOPEN = "sketch.route.engine-change.reopen"
    const val RESTORE_ENTRY = "sketch.route.restore"
    const val RESTORED_DONE = "sketch.route.restored.done"
    const val UPGRADE_DETAILS = "sketch.route.upgrade-details"
    const val UPGRADE_DETAILS_CLOSE = "sketch.route.upgrade-details.close"
}

/**
 * The classic editor of a project the new editor could own: the Upgrade chip row above it (a banner row, or the consent sheet
 * where the row would not fit) and the one-time "Created as classic." notice. Neither writes; Start, Not now and Keep classic
 * only call back.
 */
@Composable
internal fun ClassicWithOffer(
    opening: EditorOpening.ClassicEditor,
    actions: V2EntryActions,
    deps: V2EditorDeps,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    legacy: @Composable () -> Unit,
) {
    val offer = actions.offer
    val sheetKey = opening.sheet?.value.orEmpty()
    var tick by remember(opening) { mutableIntStateOf(0) }
    var rowFits by remember(opening) { mutableStateOf(true) }
    var details by remember(opening) { mutableStateOf(false) }
    var notice by remember(opening) { mutableStateOf(opening.createdNotice) }
    LaunchedEffect(opening) {
        offer?.offer(opening.affordance, sheetKey)
        tick += 1
    }
    Column(modifier.fillMaxSize()) {
        notice?.let { ClassicCreatedNotice(it) { notice = null } }
        if (offer != null && actions.chipOffered(opening.affordance)) {
            UpgradeChip(
                UpgradeChipState(opening.slug, sheetKey, tick, rowFits),
                offer, deps,
                UpgradeChipHandlers(onStart, onRowFits = { rowFits = it }, onDetails = { details = true }, onChanged = { tick += 1 }),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { legacy() }
    }
    if (details) UpgradeDetails { details = false }
}

@Composable
private fun UpgradeDetails(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(SketchMigrationStrings.ConsentSheet.TITLE, Modifier.testTag(RouteTags.UPGRADE_DETAILS)) },
        text = { Text(SketchConsentCopy.CHIP_DETAILS) },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag(RouteTags.UPGRADE_DETAILS_CLOSE)) { Text(SketchMigrationStrings.Failure.CLOSE) } },
    )
}

@Composable
private fun ClassicCreatedNotice(toast: ClassicNoticeDecision.Toast, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(RouteTags.CLASSIC_NOTICE), color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(toast.notice.text, Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(RouteTags.CLASSIC_NOTICE_DISMISS)) { Text(SketchMigrationStrings.Failure.CLOSE) }
        }
    }
}

/** The `EngineChange` notice (UX-69): the open editor is not swapped; Reopen saves and reopens it in the editor the router now picks. */
@Composable
internal fun EngineChangeRow(onReopen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag(RouteTags.ENGINE_CHANGE), color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SketchMigrationStrings.Toasts.ENGINE_CHANGE, Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onReopen, modifier = Modifier.testTag(RouteTags.ENGINE_REOPEN)) { Text(SketchMigrationStrings.Toasts.ENGINE_CHANGE_ACTION) }
        }
    }
}

/** The entry that starts the confirmed restore of a converted sheet; shown only while a backup exists. */
@Composable
internal fun RestoreEntry(available: RestoreAvailability, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    when (available) {
        RestoreAvailability.Enabled -> TextButton(onClick = onRestore, modifier = modifier.testTag(RouteTags.RESTORE_ENTRY)) { Text(RestoreCopy.ACTION) }
        is RestoreAvailability.Disabled -> Text(available.reason, modifier.padding(8.dp))
        RestoreAvailability.Hidden -> Unit
    }
}

/** What the restore left: the kept copy's path and the summary, or the plain-words failure. */
@Composable
internal fun RestoreResult(state: RestoreBackupState, onClose: () -> Unit) {
    val text = when (state) {
        is RestoreBackupState.Done -> "${RestoreCopy.RESTORED_SUMMARY} ${state.details} ${state.keptCopy}"
        is RestoreBackupState.Failed -> state.message
        else -> return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(RestoreCopy.ACTION) },
        text = { Text(text, Modifier.testTag(RouteTags.RESTORED_DONE)) },
        confirmButton = { TextButton(onClick = onClose) { Text(SketchMigrationStrings.Failure.CLOSE) } },
    )
}

/** What the chip row reads: the project, the sheet, a revision that moves when the sequencer changed and whether the row fits. */
internal class UpgradeChipState(val slug: String, val sheetKey: String, val revision: Int, val rowFits: Boolean)

internal class UpgradeChipHandlers(val onStart: () -> Unit, val onRowFits: (Boolean) -> Unit, val onDetails: () -> Unit, val onChanged: () -> Unit)

/** The Upgrade chip as the banner slot draws it, plus the consent sheet that replaces the row where its summary would not fit. */
@Composable
private fun UpgradeChip(state: UpgradeChipState, offer: UpgradeOfferController, deps: V2EditorDeps, handlers: UpgradeChipHandlers) {
    val candidate = if (state.revision >= 0) deps.onboarding?.visibleCandidate() else null
    val acks = deps.acks
    if (candidate == null || acks == null) return
    if (state.rowFits) {
        BannerSlotContent(
            candidate,
            BannerActions(
                onPrimary = { handlers.onStart() },
                onMoreItem = { _, label ->
                    when (label) {
                        SketchMigrationStrings.Banners.UPGRADE_NOT_NOW -> offer.notNow()
                        SketchMigrationStrings.Banners.UPGRADE_KEEP_CLASSIC -> offer.keepClassic(state.sheetKey)
                        else -> handlers.onDetails()
                    }
                    handlers.onChanged()
                },
            ),
        )
    }
    val gate = remember(acks) { UpgradeConsentGate(acks, deps.epochDay) }
    UpgradeOfferHost(
        state.slug, gate, InteractionState(),
        UpgradeOfferActions(
            onStart = handlers.onStart,
            onNotNow = { offer.notNow(); handlers.onChanged() },
            onKeepClassic = { offer.keepClassic(state.sheetKey); handlers.onChanged() },
        ),
        onRowFits = handlers.onRowFits,
    )
}
