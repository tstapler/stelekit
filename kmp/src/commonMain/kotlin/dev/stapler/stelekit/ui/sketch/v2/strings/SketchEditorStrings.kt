// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource

/**
 * The strings of the editor host (Story 6.2.0): the toasts a tool or a refused edit raises, the save and conflict banner rows
 * and the reasons shown instead of an editor. Part of the string table (`ui/sketch/v2/strings/`).
 */
object SketchEditorStrings {
    object Toasts {
        const val KEY_LOCKED_SKIPPED = "tool.locked-skipped"
        const val KEY_LOCKED_NOT_ERASED = "tool.locked-not-erased"
        const val KEY_ZOOM_FOR_HANDLES = "tool.zoom-for-handles"
        const val KEY_NOTHING_DRAWN = "tool.nothing-drawn"
        const val KEY_REFUSED = "edit.refused"
        const val KEY_CONFLICT = "edit.conflict"

        const val ZOOM_FOR_HANDLES = "Zoom in to use handles"
        const val NOTHING_DRAWN = "Nothing drawn"
        const val EDIT_CONFLICT = "That change no longer fits the sketch. Try again."
        const val EDIT_FAILED = "That change couldn't be made."

        fun lockedSkipped(count: Int) = if (count == 1) "1 locked element was left alone" else "$count locked elements were left alone"
        fun lockedNotErased(count: Int) = if (count == 1) "1 locked element wasn't erased" else "$count locked elements weren't erased"
    }

    object Banners : BannerStringSource {
        const val SAVE_FAILED_SUMMARY = "Couldn't save this sketch. Your work is kept here."
        const val SAVE_FAILED_SHORT = "Not saved"
        const val SAVE_RETRY = "Retry"
        const val SAVE_CONFLICT_SUMMARY = "This sketch changed on disk while you were editing."
        const val SAVE_CONFLICT_SHORT = "Sketch changed"
        const val KEEP_MINE = "Keep mine"
        const val USE_THEIRS = "Use theirs"
        const val READ_ONLY_SUMMARY = "This sketch is read-only."
        const val READ_ONLY_SHORT = "Read-only"
        const val LEASE_SUMMARY = "This sketch is open in another tab."
        const val LEASE_SHORT = "Open elsewhere"
        const val DISMISS = "Dismiss"

        override fun summary(row: BannerRowId): String = when (row) {
            BannerRowId.SaveFailed -> SAVE_FAILED_SUMMARY
            BannerRowId.SaveConflict -> SAVE_CONFLICT_SUMMARY
            BannerRowId.ReadOnly -> READ_ONLY_SUMMARY
            BannerRowId.LeaseConflict -> LEASE_SUMMARY
            BannerRowId.WarningNotice -> SketchConstraintStrings.WarningNotice.summary(row)
            BannerRowId.UpgradeChip, BannerRowId.PostConversion -> SketchMigrationStrings.Banners.summary(row)
            BannerRowId.CoachMark -> SketchStrings.CoachMark.summary(row)
            else -> row.name
        }

        override fun shortTitle(row: BannerRowId): String? = when (row) {
            BannerRowId.SaveFailed -> SAVE_FAILED_SHORT
            BannerRowId.SaveConflict -> SAVE_CONFLICT_SHORT
            BannerRowId.ReadOnly -> READ_ONLY_SHORT
            BannerRowId.LeaseConflict -> LEASE_SHORT
            BannerRowId.WarningNotice -> SketchConstraintStrings.WarningNotice.shortTitle(row)
            BannerRowId.UpgradeChip, BannerRowId.PostConversion -> SketchMigrationStrings.Banners.shortTitle(row)
            BannerRowId.CoachMark -> SketchStrings.CoachMark.shortTitle(row)
            else -> row.name
        }

        override fun primaryLabel(row: BannerRowId): String = when (row) {
            BannerRowId.SaveFailed -> SAVE_RETRY
            BannerRowId.SaveConflict -> KEEP_MINE
            BannerRowId.ReadOnly, BannerRowId.LeaseConflict -> DISMISS
            BannerRowId.WarningNotice -> SketchConstraintStrings.WarningNotice.primaryLabel(row)
            BannerRowId.UpgradeChip, BannerRowId.PostConversion -> SketchMigrationStrings.Banners.primaryLabel(row)
            BannerRowId.CoachMark -> SketchStrings.CoachMark.primaryLabel(row)
            else -> DISMISS
        }

        override fun moreItems(row: BannerRowId): List<String> = when (row) {
            BannerRowId.SaveConflict -> listOf(USE_THEIRS)
            BannerRowId.WarningNotice -> SketchConstraintStrings.WarningNotice.moreItems(row)
            BannerRowId.UpgradeChip, BannerRowId.PostConversion -> SketchMigrationStrings.Banners.moreItems(row)
            BannerRowId.CoachMark -> SketchStrings.CoachMark.moreItems(row)
            else -> emptyList()
        }
    }

    object Screens {
        const val LOADING = "Opening sketch"
        const val LOAD_FAILED = "This sketch couldn't be opened."
        const val BACK = "Back"
    }
}
