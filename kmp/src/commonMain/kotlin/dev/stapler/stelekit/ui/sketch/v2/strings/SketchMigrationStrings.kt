// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure

/**
 * The strings of the migration and enablement UI (Story 1.4.6): the Upgrade chip and post-conversion banner rows, the
 * toasts, the dialogs, the mode control, the no-editor and pending screens and the web boot-refusal screen. It is part of
 * the string table (`ui/sketch/v2/strings/`): every composable and model reads text from here, the consent sentences from
 * [SketchConsentCopy]. Rows and tables follow ux.md section 14 and the chrome table of section 1.
 */
object SketchMigrationStrings {
    /** The fixed strings of the `UpgradeChip` and `PostConversion` banner rows (chrome table rows 8 and 12). */
    object Banners : BannerStringSource {
        const val UPGRADE_SUMMARY = "Upgrade blocks other devices."
        const val UPGRADE_PRIMARY = "Start"
        const val UPGRADE_DETAILS = "Details"
        const val UPGRADE_NOT_NOW = "Not now"
        const val UPGRADE_KEEP_CLASSIC = "Keep this sketch classic"
        const val UPGRADE_ACCESSIBLE = "Start the upgrade"
        const val POST_SUMMARY = "Upgraded. Backup saved."
        const val POST_SHORT = "Upgrade done"
        const val POST_PRIMARY = "Learn more"
        const val POST_DISMISS = "Dismiss"

        override fun summary(row: BannerRowId): String = when (row) {
            BannerRowId.UpgradeChip -> UPGRADE_SUMMARY
            BannerRowId.PostConversion -> POST_SUMMARY
            else -> unowned(row)
        }

        /** Null for the consent row: a sheet replaces it where the summary wraps past the row's lines. */
        override fun shortTitle(row: BannerRowId): String? = when (row) {
            BannerRowId.UpgradeChip -> null
            BannerRowId.PostConversion -> POST_SHORT
            else -> unowned(row)
        }

        override fun primaryLabel(row: BannerRowId): String = when (row) {
            BannerRowId.UpgradeChip -> UPGRADE_PRIMARY
            BannerRowId.PostConversion -> POST_PRIMARY
            else -> unowned(row)
        }

        override fun moreItems(row: BannerRowId): List<String> = when (row) {
            BannerRowId.UpgradeChip -> listOf(UPGRADE_DETAILS, UPGRADE_NOT_NOW, UPGRADE_KEEP_CLASSIC)
            BannerRowId.PostConversion -> listOf(POST_DISMISS)
            else -> unowned(row)
        }

        private fun unowned(row: BannerRowId): Nothing = error("this table owns only the UpgradeChip and PostConversion rows, not $row")
    }

    /** The toasts of the migration flow (toast table of ux.md section 1). */
    object Toasts {
        const val ENGINE_CHANGE_KEY = "EngineChange"
        const val ENGINE_CHANGE = "Applies next time you open it."
        const val ENGINE_CHANGE_ACTION = "Reopen"
        const val CLASSIC_KEY = "ClassicEditor"
        const val CLASSIC_FIXED_KEY = "ClassicEditorFixed"
        const val CLASSIC = "Created as classic."
        const val CLASSIC_CHANGE = "Change"
        const val CLASSIC_LEARN_MORE = "Learn more"
        const val NOTICES_UPGRADE_AVAILABLE = "Upgrade available"
    }

    /** The first-write dialog (Task 1.4.6a). */
    object FirstWrite {
        const val CONVERT_TITLE = "Use the new editor?"
        const val NEW_SHEET_TITLE = "Create a sketch with the new editor?"
        const val CHECKBOX = "I understand"
        const val CHECK_HINT = "Check I understand to continue."
        const val CONVERT_AND_OPEN = "Convert and open"
        const val OPEN_CLASSIC = "Open classic"
        const val CREATE_AND_OPEN = "Create and open"
        const val CREATE_CLASSIC = "Create classic sketch"
        const val CREATED_CLASSIC_CHOSEN = "Created with the classic editor because you chose it."
    }

    /** The upgrade consent sheet (Task 1.4.6i). */
    object ConsentSheet {
        const val TITLE = "Upgrade this sketch?"
        const val ANNOUNCEMENT = "Upgrade this sketch?, dialog"
        const val START = "Start"
        const val NOT_NOW = "Not now"
        const val KEEP_CLASSIC = "Keep this sketch classic"
    }

    /** The conversion-failure dialog (UX-66, ux.md 14.4). */
    object Failure {
        const val TITLE = "Couldn't upgrade this sketch"
        const val BODY = "Your original sketch wasn't changed."
        const val COPY_DETAILS = "Copy details"
        const val OPEN_LEGACY = "Open in legacy editor"
        const val TRY_AGAIN = "Try again"
        const val CLOSE = "Close"

        /** The reason in words first and the code second (D-98). */
        fun reason(failure: ConversionFailure): String = "Reason: ${words(failure)} (${failure.token})"

        fun words(failure: ConversionFailure): String = when (failure) {
            ConversionFailure.HasTechnicalData -> "this build does not upgrade projects with dimensions or zones"
            ConversionFailure.NotFound -> "the sketch could not be found"
            ConversionFailure.StorageUnavailable -> "this device can't save new-editor sketches"
            ConversionFailure.CorruptSidecar -> "the saved project files could not be read"
            ConversionFailure.NewerSchema -> "the project was saved by a newer version of SteleKit"
            ConversionFailure.CannotLift -> "the sketch has content the new editor can't carry over"
            ConversionFailure.Encode, ConversionFailure.Write -> "the new copy could not be saved"
            ConversionFailure.Verify -> "the new copy did not read back correctly"
            ConversionFailure.NotReady -> "upgrading isn't available in this build yet"
            ConversionFailure.Deferred -> "a sync is running, so the upgrade was postponed"
        }
    }

    /** Settings > Sketches > New editor (Task 1.4.6h). */
    object Mode {
        const val HEADING = "New editor (this graph, on this device)"
        const val GROUP_NAME = "New editor"
        const val OFF = "Off"
        const val NEW_PROJECTS_ONLY = "New sketch projects only"
        const val ALL_SKETCHES = "All sketches (Upgrade)"
        const val OFF_LINE = "New sketches are classic. Sketches already in the new editor open read-only."
        const val OFF_LINE_UNAVAILABLE = "New sketches are classic."
        const val NEW_PROJECTS_ONLY_LINE =
            "New projects use the new editor, and so do projects that already hold only new-editor sketches. Other existing " +
                "projects stay classic, and so does any sketch you add to one."
        const val ALL_SKETCHES_LINE =
            "New sketches in any project use the new editor; each classic sketch gets an Upgrade offer, and a backup is kept."
        const val LEARN_WHAT_CHANGES = "Learn what changes"
        const val TEMPORARY_GRAPH = "This graph is open temporarily. New-editor sketches here are lost when you close or reload the page."
        const val UNAVAILABLE = "unavailable"
        const val TRY_THE_NEW_EDITOR = "Try the new editor"
        const val UPGRADE_MENU_ENTRY = "Upgrade to the new editor"
        const val CANCEL = "Cancel"
        const val BROWSER_STORAGE = "Browser storage"
    }

    /** The no-editor screen (Task 1.4.6j, ux.md 14.7) and the pending screen (UX-146). */
    object NoEditor {
        const val HEADING_NEWER = "Update SteleKit to view this sketch"
        const val HEADING_STORAGE = "This sketch can't be opened here"
        const val HEADING_TRANSIENT = "This sketch can't be opened right now"
        const val BODY_NEWER = "This sketch was saved by a newer version of SteleKit. Nothing was changed. Your other notes are fine."
        const val NOTHING_EXPORTED = "Nothing can be exported from here in this version."
        const val COPY_DETAILS = "Copy details"
        const val CHECK_FOR_UPDATES = "Check for updates"
        const val EXPORT_LAST_BACKUP = "Export last backup"
        const val HOW_TO_OPEN = "How to open it"
        const val RELOAD = "Reload"
        const val CLOSE = "Close"
        const val ID_NEWER_SCHEMA = "NewerSchema"
        const val ID_PLATFORM_UNSUPPORTED = "PlatformUnsupported"
        const val ID_NO_STORE = "NoStore"
        const val ID_CHECKING = "Checking"
        const val ID_AVAILABLE = "Available"
        const val PENDING_HEADING = "Checking this browser's storage..."
        const val PENDING_LONGER = "This is taking longer than usual."
        const val HOW_TO_OPEN_PAGE_TITLE = "Where new-editor sketches open"
        const val HOW_TO_OPEN_PAGE =
            "New-editor sketches open in the desktop and Android apps and in browsers that can save them safely. " +
                "This page lists no export; nothing can be exported from a browser that cannot open the sketch."

        /** The lines [Copy details] copies: names and versions only, never sketch content. */
        fun details(sketch: String, project: String, schema: String, platform: String, reasonId: String): String =
            "Sketch: $sketch\nProject: $project\nSchema: $schema\nPlatform: $platform\nReason: $reasonId"
    }

    /** The boot-refusal screen of the web build (ux.md 14.9; the `boot-refusal` block). */
    object BootRefusal {
        const val HEADING = "Graph open in another tab"
        const val MESSAGE =
            "This graph is already open in another browser tab. Switch to that tab, or close it and this page reloads by itself."
        const val MESSAGE_NO_RETRY =
            "This graph is already open in another browser tab. Switch to that tab, or close it and reload this page."
        const val STATUS_WAITING = "Waiting for the other tab to close."
        const val STATUS_FREE = "The graph is free. Reloading..."
        const val ACTION = "Reload"
        const val ACTION_ACCESSIBLE_NAME = "Reload this page"
        const val SECONDARY_ACTION = "Open temporarily"
        const val SECONDARY_ACCESSIBLE_NAME = "Open a temporary graph instead"
        const val SECONDARY_TARGET = "?mode=ephemeral"
    }
}
