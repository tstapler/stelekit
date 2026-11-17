// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.projectbridge.ModeDisabled
import dev.stapler.stelekit.projectbridge.SessionConfirmation
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.isAvailable
import dev.stapler.stelekit.sketch.v2.runtime.session.AckLevel
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** The three confirmations that turn the new editor on (Task 1.4.6c), each with [Cancel] [Turn on]. */
enum class EnableVariant {
    On,
    NewProjectsOnly,
    ReadOnlyUseNew,
    ;

    val copy: SketchConsentCopy.Confirmation
        get() = when (this) {
            On -> SketchConsentCopy.EnableOn
            NewProjectsOnly -> SketchConsentCopy.EnableNewProjectsOnly
            ReadOnlyUseNew -> SketchConsentCopy.ReadOnlyUseNew
        }
}

/** What choosing a mode does. Nothing here writes; the host stores the mode after a confirmed [Confirm]. */
sealed interface ModeChange {
    /** The already-selected mode: no dialog, nothing written. */
    data object NoOp : ModeChange

    /** Store [mode] at once: narrowing, Off, or a mode this device's level already covers. */
    data class Store(val mode: V2Mode) : ModeChange

    /** Ask first; Cancel stores nothing and leaves the level unchanged. */
    data class Confirm(val variant: EnableVariant, val mode: V2Mode) : ModeChange

    /** The option cannot be chosen; [reason] is shown beside it. */
    data class Disabled(val reason: ModeDisabled) : ModeChange
}

/** The recurrence rules of the flag-enable confirmation (U4; UX-76): a per-device [AckLevel] decides, never the project. */
object ModeChangePlanner {
    fun plan(current: V2Mode, target: V2Mode, level: AckLevel, capabilities: V2Capabilities): ModeChange {
        if (target == current) return ModeChange.NoOp
        capabilities.disabledReason(target)?.let { return ModeChange.Disabled(it) }
        if (target.ordinal < current.ordinal || level.covers(target)) return ModeChange.Store(target)
        return ModeChange.Confirm(if (target == V2Mode.On) EnableVariant.On else EnableVariant.NewProjectsOnly, target)
    }

    /**
     * The classic sketch menu item "Try the new editor" (D-97, D-110): present only while the STORED mode is Off and neither the
     * build nor the platform is capped. It opens Settings with focus on the mode group and stores, shows and asks nothing.
     */
    fun tryTheNewEditorVisible(stored: V2Mode, capabilities: V2Capabilities): Boolean =
        stored == V2Mode.Off && capabilities.disabledReason(V2Mode.NewProjectsOnly) == null

    /**
     * The read-only banner's [Use new]: ALWAYS its own confirmation, at any level, storing `NewProjectsOnly`. Null where the
     * build or platform cannot turn the mode on: the banner then has no primary action (D-99).
     */
    fun readOnlyUseNew(capabilities: V2Capabilities): ModeChange.Confirm? =
        if (capabilities.disabledReason(V2Mode.NewProjectsOnly) == null) ModeChange.Confirm(EnableVariant.ReadOnlyUseNew, V2Mode.NewProjectsOnly) else null
}

/** The warning form the first-write dialog shows: by reference right after the warning was confirmed in Settings during THIS app session. */
val SessionConfirmation.warningForm: SketchConsentCopy.WarningForm
    get() = if (confirmed) SketchConsentCopy.WarningForm.Reference else SketchConsentCopy.WarningForm.Full

/** Whether the graph reaches durable storage; a graph opened temporarily keeps its sketches for the session only (UX-139). */
enum class GraphLifetime { Persistent, Temporary }

/** One radio of the mode control: its label, its explanatory line (also its accessible description) and why it is disabled. */
data class ModeOption(
    val mode: V2Mode,
    val label: String,
    val line: String,
    val selected: Boolean,
    val disabledReason: String?,
) {
    val enabled: Boolean get() = disabledReason == null

    /** A disabled radio stays focusable; its reason is visible text and part of its description. */
    val accessibleDescription: String get() = disabledReason?.let { "$line $it" } ?: line
}

/** Settings > Sketches > New editor: the radiogroup, the temporary-graph line and the reason the rest of the section is disabled. */
data class ModeControlModel(
    val heading: String,
    val groupName: String,
    val options: List<ModeOption>,
    val temporaryGraphLine: String?,
    val restDisabledReason: String?,
) {
    fun option(mode: V2Mode): ModeOption = options.first { it.mode == mode }

    companion object {
        /** [stored] is the mode the user chose (not the effective one); [graph] says whether it was opened temporarily. */
        fun build(stored: V2Mode, capabilities: V2Capabilities, graph: GraphLifetime = GraphLifetime.Persistent): ModeControlModel {
            val storageOk = capabilities.storage.isAvailable
            val labels = listOf(
                V2Mode.Off to (SketchMigrationStrings.Mode.OFF to if (storageOk) SketchMigrationStrings.Mode.OFF_LINE else SketchMigrationStrings.Mode.OFF_LINE_UNAVAILABLE),
                V2Mode.NewProjectsOnly to (SketchMigrationStrings.Mode.NEW_PROJECTS_ONLY to SketchMigrationStrings.Mode.NEW_PROJECTS_ONLY_LINE),
                V2Mode.On to (SketchMigrationStrings.Mode.ALL_SKETCHES to SketchMigrationStrings.Mode.ALL_SKETCHES_LINE),
            )
            return ModeControlModel(
                heading = SketchMigrationStrings.Mode.HEADING,
                groupName = SketchMigrationStrings.Mode.GROUP_NAME,
                options = labels.map { (mode, text) ->
                    ModeOption(mode, text.first, text.second, mode == stored, capabilities.disabledReason(mode)?.let(SketchReasonText::disabled))
                },
                temporaryGraphLine = if (graph == GraphLifetime.Temporary) SketchMigrationStrings.Mode.TEMPORARY_GRAPH else null,
                restDisabledReason = if (storageOk) null else SketchReasonText.v2StorageUnavailable(capabilities.storage),
            )
        }
    }
}
