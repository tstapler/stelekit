// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource

/**
 * The strings of the web build's save and storage states (ux.md 14.8, Task 1.4.7l): the quota variant of Save failed, the
 * `StillSaving` toast, the save-status names, the durability cues, the Browser storage row and the refusal sentence. Part of
 * the string table (like [SketchImportExportStrings]); `WebStorageUx` composes them and holds the rules. The first-write
 * dialog lines of the same wording table stay in [SketchConsentCopy.WebStorage], their one source.
 */
object SketchWebStorageStrings {
    /** The sentence appended to a dialog line and to the quota Details once the browser has refused to keep the storage (UX-143). */
    const val PERSISTENCE_REFUSED = "Your browser has not promised to keep this site's storage."

    /** The `SaveFailed:Quota` banner row (UX-144): primary Save copy, Retry under More, because Retry cannot succeed on a full store. */
    object QuotaSaveFailed : BannerStringSource {
        const val SUMMARY = "Storage full. Keep tab open."
        const val SHORT_TITLE = "Storage full"
        const val PRIMARY = "Save copy"
        const val RETRY = "Retry"
        const val DETAILS = "Details"
        const val PRIMARY_ACCESSIBLE_NAME = "Save copy of this sketch"

        private const val NO_ROOM =
            "This browser has no room left for this site. Your work is still open in this tab and stays safe only while the tab stays open."
        private const val STEP_COPY = "1. Save a copy now."
        private const val STEP_FREE =
            "2. Free space: close other tabs of SteleKit, delete data of other sites, or delete sketch backups in Settings > Sketches."
        private const val STEP_RETRY = "3. Then choose Retry."

        /** The Details lines in order. */
        val detailLines: List<String> = listOf(NO_ROOM, STEP_COPY, STEP_FREE, STEP_RETRY)

        /** The same lines, closed by the refusal sentence once the browser refused persistence. */
        val detailLinesAfterRefusal: List<String> = detailLines + PERSISTENCE_REFUSED

        override fun summary(row: BannerRowId): String = owned(row, SUMMARY)

        override fun shortTitle(row: BannerRowId): String = owned(row, SHORT_TITLE)

        override fun primaryLabel(row: BannerRowId): String = owned(row, PRIMARY)

        override fun moreItems(row: BannerRowId): List<String> = owned(row, listOf(RETRY, DETAILS))

        override fun primaryAccessibleName(row: BannerRowId): String = owned(row, PRIMARY_ACCESSIBLE_NAME)

        private fun <T> owned(row: BannerRowId, value: T): T {
            require(row == BannerRowId.SaveFailed) { "this table owns only the SaveFailed:Quota variant, not $row" }
            return value
        }
    }

    /** The `StillSaving` toast (UX-145, UX-150): a fixed summary, no primary action, Details and Dismiss under More. */
    object StillSaving {
        const val KEY = "StillSaving"
        const val SUMMARY = "Still saving to this browser."
        const val SHORT_TITLE = "Still saving"
        const val DETAILS = "This browser is still writing your sketch to its storage. Keep this tab open until it says Saved."
    }

    /** Names of the top bar's save status on the web build (block `save-status-names`); the visible label comes first (WCAG 2.5.3). */
    object SaveStatus {
        const val LABEL_SAVED = "Saved"
        const val LABEL_SAVING = "Saving"
        const val NAME_SAVED = "Saved"
        const val NAME_SAVING = "Saving..."
        const val NAME_STILL_SAVING = "Saving... Still saving to this browser."
        const val NAME_SESSION_ONLY = "Saved for this session only"
        const val NAME_PRIVATE_WINDOW = "Saved in this window. A private window may clear it when it closes."
    }

    /** The four forms of a persistent cue, longest first (block `cue-forms`). */
    object Cue {
        /** The key of the toast the cue chip raises, so repeated activations coalesce. */
        const val TOAST_KEY = "DurabilityCue"

        const val PRIVATE_FULL = "Private window: may be cleared."
        const val PRIVATE_MID = "Private window: may clear."
        const val PRIVATE_COMPACT = "Private: may clear."
        const val PRIVATE_CHIP = "Private"
        const val SESSION_FULL = "Temporary graph: lost on close."
        const val SESSION_MID = "Temporary: lost on close."
        const val SESSION_COMPACT = "Temporary: not kept."
        const val SESSION_CHIP = "Temporary"
    }

    /** The rows of Settings > Sketches > Browser storage (block `storage-row`, UX-147). */
    object StorageRow {
        const val KEPT = "Kept"
        const val NOT_GUARANTEED = "Not guaranteed"
        const val ASK = "Ask the browser to keep it"
        const val ASKING = "Asking the browser..."
        const val REFUSED =
            "The browser said no. It decides on its own, often after you use SteleKit more; keep git sync or a linked folder as your backup."
        const val UNSUPPORTED = "This browser can't promise to keep site storage."
    }

    /** The text of `PreloadTooLarge` when the cap is [megabytes] MB (ux.md 14.1 reason table). */
    fun preloadTooLarge(megabytes: Long): String =
        "This project's sketches are too large to open in a browser (over $megabytes MB). Open it on the desktop or Android app."
}
