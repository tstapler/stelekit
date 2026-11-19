// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.NewSketchProjectPredicate
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.v2Storage
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.CreateFailure
import dev.stapler.stelekit.sketch.v2.runtime.persist.NewSheetCreator
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SheetOrigin
import dev.stapler.stelekit.ui.sketch.ClassicReason
import dev.stapler.stelekit.ui.sketch.ConversionState
import dev.stapler.stelekit.ui.sketch.NewSheetKind
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticePolicy
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeScope
import dev.stapler.stelekit.ui.sketch.v2.migration.NpoProjectState
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings

/** What the entry decider settled for a project that has no v2 sheet yet. */
sealed interface EntryDecision {
    /** Show [opening] as is. */
    class Show(val opening: EditorOpening) : EntryDecision

    /** The user already acknowledged this project: create the v2 sheet now, no dialog. */
    data object CreateNow : EntryDecision
}

/**
 * The classic and new-sheet half of opening a project (Task 6.2.0c): which kind of sheet a brand-new sketch project gets
 * (`SketchEditorRouter.newSheetKind`), whether a classic sheet offers the Upgrade chip (`SketchEditorRouter.route`), and the
 * one place a new v2 sheet is created. It reads and decides; only [createSheet] writes, and only after the caller ran the
 * first-write flow. Without the acknowledgement store it decides nothing, so a build that does not wire it stays classic.
 */
class V2EntryDecider(private val deps: V2EditorDeps) {
    private val acks get() = deps.acks

    /** A project that holds no sheet: new by definition (it was created by the very create action). */
    suspend fun forNewProject(project: SketchProject, store: ProjectSidecarStore): EntryDecision {
        val graph = deps.graphId()
        if (acks == null || graph == null) return EntryDecision.Show(EditorOpening.Legacy)
        val capabilities = V2Capabilities.of(store.v2Storage())
        val stored = storedMode(graph)
        return when (val kind = SketchEditorRouter.newSheetKind(RouteInput(stored, capabilities, SheetSchema.V1), projectIsNew = true)) {
            is NewSheetKind.Classic -> EntryDecision.Show(classicCreated(project, stored, capabilities, graph, kind))
            NewSheetKind.V2 -> if (acks?.isAcknowledged(project.slug) == true) {
                EntryDecision.CreateNow
            } else {
                EntryDecision.Show(EditorOpening.NewSheetNeedsConfirm(project.uuid, project.slug, project.name, deps.warningForm()))
            }
        }
    }

    /**
     * The kind the next sheet of an EXISTING project gets. Under New-projects-only the project counts as new only when every
     * sheet's file is already v2 (the `NewSketchProject` predicate: it reads files, so ask once per create action).
     */
    suspend fun newSheetKind(project: SketchProject, store: ProjectSidecarStore): NewSheetKind {
        val graph = deps.graphId() ?: return NewSheetKind.Classic(ClassicReason.ModeOff)
        val isNew = NewSketchProjectPredicate(store, deps.repos.sheets).qualifies(project.uuid, project.slug)
        return SketchEditorRouter.newSheetKind(RouteInput(storedMode(graph), V2Capabilities.of(store.v2Storage()), SheetSchema.V2), projectIsNew = isNew)
    }

    private fun classicCreated(project: SketchProject, stored: V2Mode, capabilities: V2Capabilities, graph: String, kind: NewSheetKind.Classic): EditorOpening {
        val ledger = deps.classicNotices ?: return EditorOpening.Legacy
        val reason = ClassicNoticePolicy.reasonOf(kind.reason, NpoProjectState.Empty)
        val decision = ClassicNoticePolicy.decide(stored, reason, capabilities, ledger, ClassicNoticeScope(graph, project.slug, null))
        val toast = decision as? ClassicNoticeDecision.Toast ?: return EditorOpening.Legacy
        return EditorOpening.ClassicEditor(project.uuid, project.slug, project.name, null, UpgradeAffordance.None, toast)
    }

    /** A project whose first sheet is classic: the chip when the router offers it, the plain legacy editor otherwise. */
    fun forClassicSheet(project: SketchProject, sheet: SketchSheet, store: ProjectSidecarStore): EditorOpening {
        val graph = deps.graphId()
        if (acks == null || graph == null) return EditorOpening.Legacy
        val conversion = if (acks?.isDeclined(sheet.uuid.value) == true) ConversionState.Declined else ConversionState.NotConverted
        val input = RouteInput(storedMode(graph), V2Capabilities.of(store.v2Storage()), SheetSchema.V1, conversion)
        val affordance = (SketchEditorRouter.route(input) as? RouteOutcome.Legacy)?.upgrade ?: UpgradeAffordance.Chip
        return if (affordance == UpgradeAffordance.None) {
            EditorOpening.Legacy
        } else {
            EditorOpening.ClassicEditor(project.uuid, project.slug, project.name, sheet.uuid, affordance, null)
        }
    }

    /** Creates the project's first sheet as a v2 sheet; counts it for metric 8 as authored. */
    suspend fun createSheet(project: ProjectUuid): Either<CreateFailure, SheetUuid> {
        val store = deps.store() ?: return Either.Left(CreateFailure.Unavailable)
        val ports = SheetSaverPorts(
            deps.repos, store, deps.slugLock, InMemoryDiagnosticsPort(SketchEditorStrings.Banners, deps.limits),
            log = deps.log, writeActor = deps.writeActor, durability = deps.durability, lifecycle = deps.lifecycle,
        )
        return NewSheetCreator(ports, deps.newId, deps.limits).create(project).onRight { deps.quality?.sheetCreated(it.value, SheetOrigin.Authored) }
    }

    private fun storedMode(graph: String): V2Mode = deps.settings?.v2Mode(graph) ?: V2Mode.Off
}
