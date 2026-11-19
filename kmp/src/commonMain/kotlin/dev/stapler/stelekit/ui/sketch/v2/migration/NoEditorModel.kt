// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** The actions of the no-editor screen (ux.md 14.7 table), in the order the table lists them. */
enum class NoEditorAction(val label: String) {
    CopyDetails(SketchMigrationStrings.NoEditor.COPY_DETAILS),
    CheckForUpdates(SketchMigrationStrings.NoEditor.CHECK_FOR_UPDATES),
    ExportLastBackup(SketchMigrationStrings.NoEditor.EXPORT_LAST_BACKUP),
    HowToOpen(SketchMigrationStrings.NoEditor.HOW_TO_OPEN),
    Reload(SketchMigrationStrings.NoEditor.RELOAD),
    Close(SketchMigrationStrings.NoEditor.CLOSE),
}

/**
 * The shared host of the newer-schema and storage-unavailable states (UX-134, UX-138; D-117): a level-1 [heading], the
 * [paragraphs] and the [actions] in tab order. The screen is a region named by its heading, not a dialog.
 */
data class NoEditorModel(val heading: String, val paragraphs: List<String>, val actions: List<NoEditorAction>, val reasonId: String) {
    companion object {
        /** [hasBackup] is true when the project holds a `backup/<sheet>.v1.json`; [Export last backup] is hidden, not disabled, without one. */
        fun of(reason: NoEditorReason, hasBackup: Boolean): NoEditorModel = when (reason) {
            NoEditorReason.NewerSchema -> NoEditorModel(
                SketchMigrationStrings.NoEditor.HEADING_NEWER,
                listOf(SketchMigrationStrings.NoEditor.BODY_NEWER),
                listOfNotNull(
                    NoEditorAction.CopyDetails,
                    NoEditorAction.CheckForUpdates,
                    NoEditorAction.ExportLastBackup.takeIf { hasBackup },
                    NoEditorAction.Close,
                ),
                SketchMigrationStrings.NoEditor.ID_NEWER_SCHEMA,
            )
            is NoEditorReason.StorageUnavailable -> storage(reason.storage)
        }

        private fun storage(storage: V2Storage): NoEditorModel {
            val transient = storage is V2Storage.Browser &&
                (storage.reason == StorageReason.PreloadFailed || storage.reason == StorageReason.CheckTimedOut)
            val paragraphs = listOf(SketchReasonText.v2StorageUnavailable(storage), SketchMigrationStrings.NoEditor.NOTHING_EXPORTED)
            return if (transient) {
                NoEditorModel(
                    SketchMigrationStrings.NoEditor.HEADING_TRANSIENT,
                    paragraphs,
                    listOf(NoEditorAction.Reload, NoEditorAction.CopyDetails, NoEditorAction.Close),
                    reasonIdOf(storage),
                )
            } else {
                NoEditorModel(
                    SketchMigrationStrings.NoEditor.HEADING_STORAGE,
                    paragraphs,
                    listOf(NoEditorAction.CopyDetails, NoEditorAction.HowToOpen, NoEditorAction.Close),
                    reasonIdOf(storage),
                )
            }
        }

        private fun reasonIdOf(storage: V2Storage): String = when (storage) {
            is V2Storage.Browser -> storage.reason.name
            V2Storage.PlatformUnsupported -> SketchMigrationStrings.NoEditor.ID_PLATFORM_UNSUPPORTED
            V2Storage.NoStore -> SketchMigrationStrings.NoEditor.ID_NO_STORE
            V2Storage.Checking -> SketchMigrationStrings.NoEditor.ID_CHECKING
            V2Storage.Available -> SketchMigrationStrings.NoEditor.ID_AVAILABLE
        }
    }
}

/** What [NoEditorAction.CopyDetails] copies: names and versions and the reason id, never sketch content. */
data class NoEditorDetails(val sketchName: String, val projectName: String, val schema: String, val platform: String) {
    fun text(model: NoEditorModel): String = SketchMigrationStrings.NoEditor.details(sketchName, projectName, schema, platform, model.reasonId)
}

/** The Pending screen (UX-146): a heading and [Close]; after [LONGER_AFTER_SECONDS] a second line says it takes longer. */
data class PendingModel(val heading: String, val longerLine: String?) {
    companion object {
        const val LONGER_AFTER_SECONDS = 5

        /** After 10 s the status is `Unavailable(CheckTimedOut)` and the router replaces this screen; it is not decided here. */
        fun at(elapsedSeconds: Int): PendingModel = PendingModel(
            SketchMigrationStrings.NoEditor.PENDING_HEADING,
            SketchMigrationStrings.NoEditor.PENDING_LONGER.takeIf { elapsedSeconds >= LONGER_AFTER_SECONDS },
        )
    }
}

/** "Check for updates": an update-check seam does not exist, so the fallback opens the releases page through [openUrl]. */
fun interface UpdateCheckPort {
    fun check()
}

class ReleasesPageUpdateCheck(private val openUrl: (String) -> Unit) : UpdateCheckPort {
    override fun check() = openUrl(RELEASES_URL)

    companion object {
        const val RELEASES_URL = "https://github.com/tstapler/stelekit/releases"
    }
}

/**
 * "Export last backup" (UX-79, UX-132): hands the newest `backup/<sheet>.v1.json` of one project to the platform file-save seam.
 * It never writes to the sidecar. [backupDir] is the project's `backup/` directory. Bytes are read through
 * [FileSystem.readFileBytesSuspend] because the web build does not preload `backup/` (D-143).
 */
class LastBackupExporter(private val fs: FileSystem, private val backupDir: String) {
    private fun candidates(): List<String> =
        fs.listFiles(backupDir).filter { it.endsWith(SUFFIX) && !it.endsWith(BEFORE_RESTORE) }

    /** False hides the action. */
    fun hasBackup(): Boolean = candidates().isNotEmpty()

    /** The newest backup by modification time (a missing time counts as oldest), then by name; null when none. */
    fun newest(): String? = candidates().maxWithOrNull(compareBy({ fs.getLastModifiedTime("$backupDir/$it") ?: 0L }, { it }))

    /** Saves the newest backup; returns the picked path, or null when there is none, it cannot be read or the user cancelled. */
    suspend fun export(): String? {
        val name = newest() ?: return null
        val bytes = fs.readFileBytesSuspend("$backupDir/$name") ?: return null
        val target = fs.pickSaveFileAsync(name, MIME) ?: return null
        return if (fs.writeFileBytes(target, bytes)) target else null
    }

    private companion object {
        const val SUFFIX = ".v1.json"
        const val BEFORE_RESTORE = ".v2-before-restore.json"
        const val MIME = "application/json"
    }
}
