// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource

/**
 * The strings of the constraint UI (Stories 4.2.2 and 4.2.3): the auto-constrain toast, the toggle and its chip, the
 * `WarningNotice` banner row, the Constraints tab, the Constrain flyout, the glyph overlay and the conflict card. Part of the
 * string table (`ui/sketch/v2/strings/`), like [SketchMigrationStrings]; the Constrain menu labels themselves stay in
 * [SketchStrings.Constrain]. The reasons an item is disabled stay in `SketchReasonText` (the one source of those).
 */
object SketchConstraintStrings {
    /** The `AddedConstraint` toast of auto-constrain (UX-39): "Constraint added." with Undo; the kinds are in its Details. */
    object Toast {
        const val KEY = "AddedConstraint"
        const val SUMMARY = "Constraint added."
        const val SHORT = "Added"
        const val UNDO = "Undo"

        /** "Added Coincident" for one kind; "Added Coincident (4), Horizontal (2)" when several were added and some repeat. */
        fun details(kinds: List<String>): String {
            val counted = kinds.groupingBy { it }.eachCount()
            return "Added " + counted.entries.joinToString(", ") { (name, n) -> if (n == 1) name else "$name ($n)" }
        }
    }

    /** The auto-constrain toggle and its one-shot suppression chip (Task 4.2.2c, UX-82). */
    object AutoConstrain {
        const val TOGGLE = "Auto-constrain while drawing"
        const val TOGGLE_ON = "Auto-constrain while drawing, on"
        const val TOGGLE_OFF = "Auto-constrain while drawing, off"
        const val STATE_ON = "on"
        const val STATE_OFF = "off"
        const val FREE_DRAW_CHIP = "Free draw"
        const val FREE_DRAW_NAME = "Free draw: the next shape is not constrained"
    }

    /** The fixed strings of the `WarningNotice` banner row (chrome table row 5). */
    object WarningNotice : BannerStringSource {
        const val SUMMARY = "Constraint conflict."
        const val SHORT_TITLE = "Clash"
        const val PRIMARY = "Show"
        const val ACCESSIBLE_NAME = "Show the constraint conflict"
        const val DETAILS_ITEM = "Details"
        const val DISMISS_ITEM = "Dismiss"

        override fun summary(row: BannerRowId): String = owned(row, SUMMARY)

        override fun shortTitle(row: BannerRowId): String = owned(row, SHORT_TITLE)

        override fun primaryLabel(row: BannerRowId): String = owned(row, PRIMARY)

        override fun moreItems(row: BannerRowId): List<String> = owned(row, listOf(DETAILS_ITEM, DISMISS_ITEM))

        override fun primaryAccessibleName(row: BannerRowId): String = owned(row, ACCESSIBLE_NAME)

        private fun <T> owned(row: BannerRowId, value: T): T {
            require(row == BannerRowId.WarningNotice) { "this table owns only the WarningNotice row, not $row" }
            return value
        }
    }

    /** The Constraints tab, the Constrain flyout, the glyph overlay and the conflict card (UX-30 to UX-37, UX-83, UX-84, UX-106). */
    object Panel {
        const val TAB = "Constraints"
        const val EMPTY = "No constraints yet. Select a line, circle or point, then choose Constrain."
        const val CONSTRAIN = "Constrain"
        const val CONSTRAIN_SEMANTICS = "Constrain menu"
        const val DETAILS = "Details"
        const val DELETE = "Delete"
        const val MAKE_REFERENCE = "Make reference"
        const val MAKE_DRIVING = "Make driving"
        const val STATE_OK = "OK"
        const val STATE_REDUNDANT = "Redundant"
        const val STATE_CONFLICT = "Conflict"
        const val GLYPH_OK = "✓"
        const val GLYPH_REDUNDANT = "⚠"
        const val GLYPH_CONFLICT = "✖"
        const val SHOW_GLYPHS = "Show constraint glyphs"
        const val GLYPHS_REGION = "Constraint glyphs"
        const val BADGE_LIST = "Constraints here"
        const val NOT_SOLVED = "Couldn't solve these constraints. Nothing changed."
        const val REMOVE_NEW = "Remove new constraint"
        const val REPLACE_EXISTING = "Replace existing"
        const val SHOW_CONFLICTS = "Show conflicts"
        const val REMOVE_CONFLICTING = "Remove conflicting constraint"
        const val RADIUS = "Radius"
        const val DIAMETER = "Diameter"

        fun overConstrained(conflicts: Int): String = "Over-constrained: $conflicts ${if (conflicts == 1) "conflict" else "conflicts"}"

        fun conflict(added: String, other: String, element: String): String = "$added conflicts with $other on $element"

        fun rowText(label: String, value: String?, targets: String, stateText: String?): String =
            listOfNotNull(label, value, stateText?.let { "($it)" }, targets.ifEmpty { null }).joinToString(" ")

        fun rowSemantics(text: String, stateText: String): String = "$text, $stateText"

        fun badgeSemantics(count: Int): String = "$count constraints, open the list"

        fun elementBadgeSemantics(element: String, count: Int): String = "$element, $count constraints"

        fun kindLabel(kind: ConstraintKind): String = when (kind) {
            ConstraintKind.Coincident -> SketchStrings.Constrain.COINCIDENT
            ConstraintKind.Horizontal -> SketchStrings.Constrain.HORIZONTAL
            ConstraintKind.Vertical -> SketchStrings.Constrain.VERTICAL
            ConstraintKind.Parallel -> SketchStrings.Constrain.PARALLEL
            ConstraintKind.Perpendicular -> SketchStrings.Constrain.PERPENDICULAR
            ConstraintKind.Equal -> SketchStrings.Constrain.EQUAL
            ConstraintKind.Tangent -> SketchStrings.Constrain.TANGENT
            ConstraintKind.Symmetric -> SketchStrings.Constrain.SYMMETRIC
            ConstraintKind.Fixed -> SketchStrings.Constrain.FIXED
            ConstraintKind.Distance -> SketchStrings.Constrain.DISTANCE
            ConstraintKind.Angle -> SketchStrings.Constrain.ANGLE
            ConstraintKind.Radius -> RADIUS
            ConstraintKind.Diameter -> DIAMETER
        }

        /** The short mark drawn next to the geometry; a dimension shows its value instead (see the glyph overlay). */
        fun glyphOf(kind: ConstraintKind): String = when (kind) {
            ConstraintKind.Coincident -> "•"
            ConstraintKind.Horizontal -> "H"
            ConstraintKind.Vertical -> "V"
            ConstraintKind.Parallel -> "∥"
            ConstraintKind.Perpendicular -> "⟂"
            ConstraintKind.Equal -> "="
            ConstraintKind.Tangent -> "◯"
            ConstraintKind.Symmetric -> "⇋"
            ConstraintKind.Fixed -> "▣"
            ConstraintKind.Distance, ConstraintKind.Angle, ConstraintKind.Radius, ConstraintKind.Diameter -> "↔"
        }
    }

    /** What a technical sheet says when its v1 projection no longer matches the named dimensions (Story 4.2.4). */
    object Projection {
        const val SHOW_ELEMENTS = "Show elements"

        /** [amount] is the difference in inches without the unit, for example `1/16`. */
        fun offBy(name: String, amount: String): String = "$name is off by $amount in"

        fun notUsed(count: Int): String = "$count ${if (count == 1) "element" else "elements"} not used for dimensions"
    }
}
