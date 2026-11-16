// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.RetentionPolicy
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val RESTORE_CONFIRM_TAG = "sketch.restore.confirm"
const val RESTORE_CANCEL_TAG = "sketch.restore.cancel"
const val DELETE_BACKUPS_CONFIRM_TAG = "sketch.backups.delete.confirm"
const val DELETE_BACKUPS_CANCEL_TAG = "sketch.backups.delete.cancel"

/** The words of the restore and delete-backups dialogs and the `Restored` toast (ux.md 14.6, UX-78, UX-114). */
object RestoreCopy {
    const val ACTION = "Restore original (v1) sheet"
    const val TITLE = "Restore the original sketch?"
    const val BODY =
        "Everything you changed in the new editor since the upgrade will be discarded. A copy of the new-editor version is kept in backup/."
    const val CONFIRM = "Restore original"
    const val CANCEL = "Cancel"

    const val RESTORED_KEY = "Restored"
    const val RESTORED_SUMMARY = "Restored. Copy kept."
    const val RESTORED_ACTION = "Copy path"
    const val RESTORED_DETAILS = "Restored the original sketch. The new-editor version is kept in backup/."

    const val DELETE_ACTION = "Delete backups"
    const val DELETE_TITLE = "Delete backups?"
    const val DELETE_BODY =
        "The original copies of upgraded sketches in this project are deleted. Restore original is not available for them afterwards."

    /** Shown for a build that cannot restore yet; the platform reason of ux.md 14.1 comes first (UX-137). */
    const val BUILD_CAPPED = "Not available in this build yet"
}

/** Whether the Settings rows are usable, hidden or shown disabled with a visible reason (UX-90, UX-137). */
sealed interface RestoreAvailability {
    /** No backup exists, so there is nothing to offer (a sheet created as v2 never has one). */
    data object Hidden : RestoreAvailability
    data class Disabled(val reason: String) : RestoreAvailability
    data object Enabled : RestoreAvailability

    companion object {
        /** `Platform` before `BuildCapped`, the one precedence of every disabled new-editor item; `hasBackup` only decides Hidden. */
        fun of(platformReason: String?, buildCapped: Boolean, hasBackup: Boolean): RestoreAvailability = when {
            platformReason != null -> Disabled(platformReason)
            buildCapped -> Disabled(RestoreCopy.BUILD_CAPPED)
            !hasBackup -> Hidden
            else -> Enabled
        }
    }
}

/** What the restore dialog and notice queue show. */
sealed interface RestoreBackupState {
    data object Idle : RestoreBackupState
    data class Confirming(val slug: String, val sheet: SheetUuid) : RestoreBackupState
    data object Running : RestoreBackupState

    /** The `Restored` toast: summary, [Copy path] and the kept copy path in its Details. */
    data class Done(val notice: SketchNotice, val keptCopy: String, val details: String) : RestoreBackupState

    /** [message] is plain words and names what is still true ("Nothing was changed"). */
    data class Failed(val message: String) : RestoreBackupState
}

/**
 * The confirmed restore of Story 6.2.6: [request] opens the dialog, [cancel] changes nothing, and only [confirm] calls
 * [restore]. Pure state in a [StateFlow]; the host owns the scope (`rememberCoroutineScope` stays in the composable below).
 */
class RestoreBackupAction(private val restore: suspend (slug: String, sheet: SheetUuid) -> RestoreOutcome) {
    private val mutable = MutableStateFlow<RestoreBackupState>(RestoreBackupState.Idle)
    val state: StateFlow<RestoreBackupState> = mutable.asStateFlow()

    fun request(slug: String, sheet: SheetUuid) {
        if (mutable.value is RestoreBackupState.Running) return
        mutable.value = RestoreBackupState.Confirming(slug, sheet)
    }

    fun cancel() {
        if (mutable.value is RestoreBackupState.Confirming) mutable.value = RestoreBackupState.Idle
    }

    fun dismiss() {
        if (mutable.value !is RestoreBackupState.Running) mutable.value = RestoreBackupState.Idle
    }

    suspend fun confirm() {
        val asked = mutable.value as? RestoreBackupState.Confirming ?: return
        mutable.value = RestoreBackupState.Running
        mutable.value = when (val outcome = restore(asked.slug, asked.sheet)) {
            is RestoreOutcome.Restored -> RestoreBackupState.Done(
                SketchNotice(RestoreCopy.RESTORED_KEY, BannerTier.Info, RestoreCopy.RESTORED_SUMMARY, RestoreCopy.RESTORED_ACTION),
                outcome.keptCopy,
                RestoreCopy.RESTORED_DETAILS,
            )
            RestoreOutcome.NotConverted -> RestoreBackupState.Failed("This sketch is already the original version. Nothing was changed.")
            RestoreOutcome.BackupMissing -> RestoreBackupState.Failed("The original copy of this sketch was not found. Nothing was changed.")
            RestoreOutcome.Deferred -> RestoreBackupState.Failed("A sync is running. Nothing was changed; try again in a moment.")
            is RestoreOutcome.Failed -> RestoreBackupState.Failed("Could not restore the original sketch. Your sketch was not lost.")
        }
    }
}

/** Settings > Sketches "Delete backups" (Task 6.2.6c): the size row, and a confirmed delete of `backup/`. */
class BackupsSectionModel(
    private val size: suspend () -> RetentionPolicy.Size,
    private val delete: suspend () -> Either<DomainError, Int>,
) {
    data class Row(val files: Int, val bytes: Long, val text: String)

    private val confirming = MutableStateFlow(false)
    val isConfirming: StateFlow<Boolean> = confirming.asStateFlow()

    suspend fun row(): Row {
        val s = size()
        return Row(s.backupFiles, s.backupBytes, "Backups: ${describe(s.backupFiles, s.backupBytes)}")
    }

    fun requestDelete() { confirming.value = true }

    fun cancel() { confirming.value = false }

    /** Deletes only after [requestDelete]; returns the number of files deleted, or null when nothing was confirmed or it failed. */
    suspend fun confirmDelete(): Int? {
        if (!confirming.value) return null
        confirming.value = false
        return delete().getOrNull()
    }

    companion object {
        private const val BYTES_PER_KB = 1024L
        private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB

        fun describe(files: Int, bytes: Long): String = when {
            files == 0 -> "none"
            else -> "$files ${if (files == 1) "file" else "files"}, ${bytesText(bytes)}"
        }

        private fun bytesText(bytes: Long): String = when {
            bytes >= BYTES_PER_MB -> "${tenths(bytes, BYTES_PER_MB)} MB"
            bytes >= BYTES_PER_KB -> "${tenths(bytes, BYTES_PER_KB)} KB"
            else -> "$bytes B"
        }

        private fun tenths(bytes: Long, unit: Long): String {
            val scaled = bytes * 10 / unit
            return "${scaled / 10}.${scaled % 10}"
        }
    }
}

/** The restore confirmation. [Cancel] takes focus first, so nothing destructive is the default (UX-78). */
@Composable
fun RestoreBackupDialog(onRestore: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(RestoreCopy.TITLE) },
        text = { Text(RestoreCopy.BODY) },
        confirmButton = { Button(onClick = onRestore, modifier = Modifier.testTag(RESTORE_CONFIRM_TAG)) { Text(RestoreCopy.CONFIRM) } },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).testTag(RESTORE_CANCEL_TAG)) { Text(RestoreCopy.CANCEL) }
        },
    )
}

/** The delete-backups confirmation, same shape and same default focus. */
@Composable
fun DeleteBackupsDialog(onDelete: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(RestoreCopy.DELETE_TITLE) },
        text = { Text(RestoreCopy.DELETE_BODY) },
        confirmButton = { Button(onClick = onDelete, modifier = Modifier.testTag(DELETE_BACKUPS_CONFIRM_TAG)) { Text(RestoreCopy.DELETE_ACTION) } },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).testTag(DELETE_BACKUPS_CANCEL_TAG)) { Text(RestoreCopy.CANCEL) }
        },
    )
}

/** Hosts the restore dialog for [action]: shows it while confirming and runs [RestoreBackupAction.confirm] from a click. */
@Composable
fun RestoreBackupHost(action: RestoreBackupAction, state: RestoreBackupState) {
    val scope = rememberCoroutineScope()
    if (state is RestoreBackupState.Confirming) {
        RestoreBackupDialog(onRestore = { scope.launch { action.confirm() } }, onCancel = action::cancel)
    }
}
