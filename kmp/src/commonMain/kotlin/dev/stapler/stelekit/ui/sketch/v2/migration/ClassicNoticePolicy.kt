// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.ui.sketch.ClassicNoticeReason
import dev.stapler.stelekit.ui.sketch.ClassicReason
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.SketchNotice
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** How a New-sketch-projects-only project is classic, as the `NewSketchProject` predicate found it. */
enum class NpoProjectState { ClassicSheets, Empty, Unreadable }

/** The outcome of one "sketch created as classic" event. */
sealed interface ClassicNoticeDecision {
    /** Only a silent entry in the Notices list: the stored mode is Off, or this toast was already shown. */
    data object Silent : ClassicNoticeDecision

    /** The toast; [fixed] when Settings cannot change the outcome, so the action is [Learn more] and not [Change]. */
    data class Toast(val reason: ClassicNoticeReason, val notice: SketchNotice, val details: String, val fixed: Boolean) : ClassicNoticeDecision
}

/** Where a sheet was created: the graph, the project and (when readable) every project that exists, for pruning the ledger. */
data class ClassicNoticeScope(val graphId: String, val slug: String, val existingSlugs: Set<String>?)

/** Which reason applies, the recurrence rule and the toast row (Task 1.4.6e; UX-77, UX-131, D-96, D-101). */
object ClassicNoticePolicy {
    /** The single reason a toast names: the first applicable one in precedence order. */
    fun firstApplicable(applicable: Set<ClassicNoticeReason>): ClassicNoticeReason? =
        ClassicNoticeReason.entries.firstOrNull { it in applicable }

    /** Maps the router's [ClassicReason] to the toast's closed set; [npo] says how a not-new project is classic. */
    fun reasonOf(reason: ClassicReason, npo: NpoProjectState = NpoProjectState.ClassicSheets): ClassicNoticeReason = when (reason) {
        ClassicReason.ModeOff -> ClassicNoticeReason.ModeOff
        ClassicReason.BuildCapped -> ClassicNoticeReason.BuildCapped
        ClassicReason.TechnicalData -> ClassicNoticeReason.TechnicalData
        is ClassicReason.Storage -> ClassicNoticeReason.Platform
        ClassicReason.ProjectNotNew -> when (npo) {
            NpoProjectState.ClassicSheets -> ClassicNoticeReason.NpoClassicSheets
            NpoProjectState.Empty -> ClassicNoticeReason.NpoEmptyProject
            NpoProjectState.Unreadable -> ClassicNoticeReason.NpoUnreadable
        }
    }

    /**
     * Decides and, for a toast, records it in [ledger]. [stored] is the mode the user chose (not the effective one): Off shows
     * no toast at all, every other stored mode shows it once per (graph, project, reason) per device.
     */
    fun decide(
        stored: V2Mode,
        reason: ClassicNoticeReason,
        capabilities: V2Capabilities,
        ledger: ClassicNoticeLedger,
        scope: ClassicNoticeScope,
    ): ClassicNoticeDecision {
        if (stored == V2Mode.Off || ledger.hasShown(scope.graphId, scope.slug, reason.name)) return ClassicNoticeDecision.Silent
        ledger.record(scope.graphId, scope.slug, reason.name, scope.existingSlugs)
        val fixed = !canChange(stored, reason, capabilities)
        val key = if (fixed) SketchMigrationStrings.Toasts.CLASSIC_FIXED_KEY else SketchMigrationStrings.Toasts.CLASSIC_KEY
        val action = if (fixed) SketchMigrationStrings.Toasts.CLASSIC_LEARN_MORE else SketchMigrationStrings.Toasts.CLASSIC_CHANGE
        val notice = SketchNotice(key, BannerTier.Info, SketchMigrationStrings.Toasts.CLASSIC, action)
        return ClassicNoticeDecision.Toast(reason, notice, SketchReasonText.classicDetails(reason, capabilities.storage), fixed)
    }

    /** [Change] is offered only where choosing All sketches (Upgrade) in Settings would change the outcome. */
    fun canChange(stored: V2Mode, reason: ClassicNoticeReason, capabilities: V2Capabilities): Boolean =
        stored == V2Mode.NewProjectsOnly && reason.settingsCanChange && capabilities.disabledReason(V2Mode.On) == null
}

/** The `EngineChange` toast: the mode changed while the editor was open; the open editor is neither swapped nor closed (UX-69). */
object EngineChangeNotice {
    val notice = SketchNotice(
        SketchMigrationStrings.Toasts.ENGINE_CHANGE_KEY,
        BannerTier.Info,
        SketchMigrationStrings.Toasts.ENGINE_CHANGE,
        SketchMigrationStrings.Toasts.ENGINE_CHANGE_ACTION,
    )
}
