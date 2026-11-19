// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.failureReason
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.migration.ConversionFailureDialog
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstV2WriteDialog
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteAction
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteDialogModel
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteOutcome
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.StartDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The modal the route shows over whatever editor is up. */
sealed interface EntryDialog {
    /** The first-write dialog before a v2 write of a project: [target] is the classic sheet to convert or the new sketch to create. */
    class FirstWrite(val model: FirstWriteDialogModel, val target: EditorOpening) : EntryDialog

    class Failure(val failure: ConversionFailure, val target: EditorOpening.ClassicEditor) : EntryDialog
}

/** The route's state setters, as one object so the steps hold no composition state of their own. */
class EntryHooks(
    val onBack: () -> Unit,
    val setOpening: (EditorOpening?) -> Unit,
    val setDialog: (EntryDialog?) -> Unit,
    val setBusy: (Boolean) -> Unit,
    val setConverted: (SheetUuid?) -> Unit,
    val reopen: () -> Unit,
    val legacyFlush: LegacyFlushHandle,
)

/**
 * The click handlers behind the creation and conversion UI: each runs one step of the flow on the route's scope and reports
 * through [EntryHooks]. The order is fixed: the dialog is confirmed (acknowledgement stored, browser asked to persist) before the
 * first v2 write, and a conversion flushes the classic editor and lets it leave the composition before it touches the sheet.
 */
class EntrySteps(
    private val actions: V2EntryActions,
    private val opener: V2EditorOpener,
    private val metrics: TextMetricsProvider,
    private val hooks: EntryHooks,
) {
    fun newSheetDialog(opening: EditorOpening.NewSheetNeedsConfirm): EntryDialog =
        EntryDialog.FirstWrite(actions.firstWriteModel(FirstWriteVariant.NewSheet, opening.projectName, null).copy(form = opening.form), opening)

    /** The chip's Start: the acknowledgement decides between the dialog and an immediate conversion. */
    fun start(scope: CoroutineScope, classic: EditorOpening.ClassicEditor) {
        scope.launch {
            when (actions.start(classic.slug)) {
                is StartDecision.Withdrawn -> hooks.reopen()
                StartDecision.NeedsFirstWriteDialog -> hooks.setDialog(EntryDialog.FirstWrite(actions.firstWriteModel(FirstWriteVariant.Convert, classic.projectName, classic.sheet), classic))
                StartDecision.ConvertNow -> convert(classic)
            }
        }
    }

    /** The user pressed a button of the first-write dialog. */
    fun answer(scope: CoroutineScope, dialog: EntryDialog.FirstWrite, action: FirstWriteAction) {
        val outcome = dialog.model.resolve(action) ?: return
        hooks.setDialog(null)
        scope.launch {
            when (val target = dialog.target) {
                is EditorOpening.NewSheetNeedsConfirm -> answerNew(target, outcome)
                is EditorOpening.ClassicEditor -> if (outcome == FirstWriteOutcome.ConvertAndOpen) {
                    actions.confirmFirstWrite(outcome, target.slug)
                    convert(target)
                }
                else -> Unit
            }
        }
    }

    private suspend fun answerNew(target: EditorOpening.NewSheetNeedsConfirm, outcome: FirstWriteOutcome) {
        when (outcome) {
            FirstWriteOutcome.CreateAndOpen -> {
                hooks.setBusy(true)
                actions.confirmFirstWrite(outcome, target.slug)
                hooks.setOpening(opener.createAndOpen(target.projectUuid, metrics))
                hooks.setBusy(false)
            }
            FirstWriteOutcome.CreateClassic -> hooks.setOpening(EditorOpening.Legacy)
            FirstWriteOutcome.Cancelled -> hooks.onBack()
            else -> Unit
        }
    }

    /** Converts the classic sheet of [classic]; on success the route reopens it as v2 with the post-conversion banner. */
    suspend fun convert(classic: EditorOpening.ClassicEditor) {
        val sheet = classic.sheet ?: return
        hooks.setBusy(true)
        hooks.legacyFlush.flush()
        withFrameNanos { }
        when (val result = actions.convert(classic.projectUuid, sheet)) {
            is ConversionOutcome.Converted, ConversionOutcome.AlreadyConverted -> {
                hooks.setConverted(sheet)
                hooks.reopen()
            }
            is ConversionOutcome.RequiresConfirmation -> {
                hooks.setBusy(false)
                hooks.setDialog(EntryDialog.FirstWrite(actions.firstWriteModel(FirstWriteVariant.Convert, classic.projectName, sheet), classic))
            }
            is ConversionOutcome.Failed, ConversionOutcome.NotReady, ConversionOutcome.Deferred ->
                result.failureReason()?.let { failed(classic, it) }
        }
    }

    private fun failed(classic: EditorOpening.ClassicEditor, reason: ConversionFailure) {
        hooks.setBusy(false)
        hooks.setDialog(EntryDialog.Failure(reason, classic))
    }
}

/** The route's modals: the first-write dialog, the conversion-failure dialog and the confirmed restore with its result. */
@Composable
internal fun EntryDialogs(dialog: EntryDialog?, steps: EntrySteps?, scope: CoroutineScope, restore: RestoreBackupAction?, hooks: EntryHooks) {
    when (dialog) {
        is EntryDialog.FirstWrite -> FirstV2WriteDialog(
            dialog.model,
            onCheckedChange = { hooks.setDialog(EntryDialog.FirstWrite(dialog.model.withChecked(it), dialog.target)) },
            onAction = { steps?.answer(scope, dialog, it) },
        )
        is EntryDialog.Failure -> FailureDialog(dialog, steps, scope, hooks)
        null -> Unit
    }
    if (restore != null) {
        val state by restore.state.collectAsState()
        RestoreBackupHost(restore, state)
        RestoreResult(state) {
            restore.dismiss()
            hooks.reopen()
        }
    }
}

@Composable
private fun FailureDialog(dialog: EntryDialog.Failure, steps: EntrySteps?, scope: CoroutineScope, hooks: EntryHooks) {
    val clipboard = LocalClipboardManager.current
    ConversionFailureDialog(
        failure = dialog.failure,
        onCopyDetails = { clipboard.setText(AnnotatedString(dialog.failure.token)) },
        onOpenLegacy = { hooks.setDialog(null) },
        onTryAgain = {
            hooks.setDialog(null)
            scope.launch { steps?.convert(dialog.target) }
        },
        onClose = { hooks.setDialog(null) },
    )
}
