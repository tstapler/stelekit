// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.split

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.LocalBundleLoader
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionPolicy
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/** Why a split did not happen. In every case the source sheet is unchanged on disk. */
enum class SplitFailure(val token: String) {
    NothingSelected("nothing-selected"),
    UnknownElement("unknown-element"),
    NotFound("not-found"),

    /** The source is not a v2 sheet: a split is only reachable from an editable v2 sheet. */
    NotV2("not-v2"),
    HasTechnicalData("technical-data"),
    StorageUnavailable("storage-unavailable"),
    CorruptSidecar("corrupt-sidecar"),

    /** A git sync is in flight; the next trigger retries. Nothing was changed. */
    Deferred("deferred"),
    Encode("encode"),
    Write("write"),
}

sealed interface SplitOutcome {
    /** [clearedBindings] bindings to unselected elements were cleared; [droppedConstraints] spanned both sides. */
    data class Split(
        val newSheet: SheetUuid,
        val moved: Int,
        val clearedBindings: Int,
        val carriedConstraints: Int,
        val droppedConstraints: Int,
    ) : SplitOutcome

    data class Failed(val reason: SplitFailure, val detail: String = "") : SplitOutcome
}

/** The steps of the protocol, each a crash boundary. */
enum class SplitStep { NewSheetStaged, SourceAndModelWritten, RowsWritten }

/** Test seam: called after each step completes; a test throws from it to simulate the process dying there. */
fun interface SplitCrashPoint {
    fun after(step: SplitStep)

    companion object {
        val None = SplitCrashPoint { }
    }
}

/**
 * "Split into a new sketch" across two sheet files (Story 2.2.4, engineering gap 7). History is per sheet and the saver is
 * single-writer, so there is no cross-sheet undo; instead, under ONE [SidecarSlugLock] hold:
 *
 * 1. the new sheet is staged as an unreferenced `sheets/<new>.v2.json`;
 * 2. the source's new bytes and the model are written in one store call (the store writes sheet files, then the model last,
 *    which is the ONE atomic rename that adds the new `sheetRef` and updates the source `fileSha256` together: the commit point);
 * 3. the database rows follow through `DatabaseWriteActor`.
 *
 * A crash before step 2 leaves the project reading the old source and a staged unreferenced file the next write sweeps away;
 * a crash after it leaves both sheets present and the rows are repaired by the next reconcile. The source's own `HistoryV2`
 * holds the delete as one step ([SplitPlan.deleteMoved]); this class never removes a sheet because of an undo.
 */
class SheetSplitter(
    private val repos: SketchRepositories,
    private val store: ProjectSidecarStore,
    private val slugLock: SidecarSlugLock,
    private val writeActor: DatabaseWriteActor? = null,
    private val log: SketchLog = SketchLog(),
    private val limits: SceneLimits = SceneLimits.Default,
    private val technicalDataEditable: Boolean = ConversionPolicy.TECHNICAL_DATA_EDITABLE,
    private val crashPoint: SplitCrashPoint = SplitCrashPoint.None,
) {
    /** Moves [selected] out of [source] (the document of [sourceSheet]) into the new sheet [newSheet] of the same project. */
    suspend fun split(
        projectUuid: ProjectUuid,
        sourceSheet: SheetUuid,
        source: SketchDocumentV2,
        selected: Set<ElementId>,
        newSheet: SheetUuid,
    ): SplitOutcome {
        val plan = SplitPlanner.plan(source, selected, newSheet.value, limits).fold({ return refusal(it) }, { it })
        val loaded = repos.projects.loadProject(projectUuid).getOrNull() ?: return failed(SplitFailure.NotFound, "project")
        val slug = loaded.project.slug
        return slugLock.withSlug(slug) { splitLocked(projectUuid, slug, sourceSheet, newSheet, plan) }
            .also { log.migrateOutcome("split-${(it as? SplitOutcome.Failed)?.reason?.token ?: "done"}") }
    }

    private fun refusal(r: SplitRefusal): SplitOutcome = when (r) {
        SplitRefusal.NothingSelected -> failed(SplitFailure.NothingSelected)
        is SplitRefusal.UnknownElement -> failed(SplitFailure.UnknownElement)
        SplitRefusal.DoesNotFit -> failed(SplitFailure.Encode, "does-not-fit")
    }

    private suspend fun splitLocked(
        projectUuid: ProjectUuid,
        slug: String,
        sourceSheet: SheetUuid,
        newSheet: SheetUuid,
        plan: SplitPlan,
    ): SplitOutcome {
        val disk = when (val read = store.read(slug)) {
            SidecarRead.Unavailable -> return failed(SplitFailure.StorageUnavailable)
            is SidecarRead.Corrupt -> return failed(SplitFailure.CorruptSidecar)
            SidecarRead.Absent -> null
            is SidecarRead.Present -> read.files
        }
        val project = repos.projects.loadProject(projectUuid).getOrNull()?.project ?: return failed(SplitFailure.NotFound, "project")
        val local = LocalBundleLoader.load(repos, project, disk.orEmpty()).getOrNull() ?: return failed(SplitFailure.Write, "local bundle")
        val row = local.sheets.firstOrNull { it.uuid == sourceSheet } ?: return failed(SplitFailure.NotFound, "sheet")
        if (row.schemaVersion != SheetPaths.SCHEMA_V2) return failed(SplitFailure.NotV2)
        if (ConversionPolicy.refusal(local, row, technicalDataEditable) != null) return failed(SplitFailure.HasTechnicalData)

        val sourceBytes = SheetCodecV2.encode(plan.sourceAfter, limits).getOrNull() ?: return failed(SplitFailure.Encode, "source")
        val newBytes = SheetCodecV2.encode(plan.newDocument, limits).getOrNull() ?: return failed(SplitFailure.Encode, "new sheet")
        val newPath = SheetPaths.pathFor(newSheet, SheetPaths.SCHEMA_V2)

        val staged = (disk ?: SidecarCodec.encode(local)) + (newPath to newBytes)
        stop(store.writeNow(slug, staged))?.let { return it }
        crashPoint.after(SplitStep.NewSheetStaged)

        val (updatedSource, created, bundle) = rebuild(local, row, newSheet, plan, sourceBytes, newBytes)
        val modelFiles = SidecarCodec.encode(bundle)
        val others = bundle.sheetPayloads.filterKeys { it != sourceSheet && it != newSheet }
            .mapNotNull { (uuid, payload) -> (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 } }.toMap()
        stop(store.writeNow(slug, modelFiles - others.keys, others))?.let { return it }
        crashPoint.after(SplitStep.SourceAndModelWritten)

        return finishDatabase(bundle.project, updatedSource, created, SidecarCodec.bundleHash(modelFiles), plan)
            .also { crashPoint.after(SplitStep.RowsWritten) }
    }

    private class Rebuilt(val source: SketchSheet, val created: SketchSheet, val bundle: ProjectBundle) {
        operator fun component1() = source
        operator fun component2() = created
        operator fun component3() = bundle
    }

    private fun rebuild(local: ProjectBundle, row: SketchSheet, newSheet: SheetUuid, plan: SplitPlan, sourceBytes: ByteArray, newBytes: ByteArray): Rebuilt {
        val updatedSource = row.copy(layers = V2ToV1.projectScene(plan.sourceAfter.scene).layers.map { it.encode() })
        val created = row.copy(
            uuid = newSheet,
            position = (local.sheets.maxOfOrNull { it.position } ?: 0L) + 1,
            layers = V2ToV1.projectScene(plan.newDocument.scene).layers.map { it.encode() },
            schemaVersion = SheetPaths.SCHEMA_V2,
        )
        val bundle = local.copy(
            sheets = local.sheets.map { if (it.uuid == row.uuid) updatedSource else it } + created,
            sheetPayloads = local.sheetPayloads +
                (row.uuid to SheetPayload.V2Opaque.of(sourceBytes)) + (newSheet to SheetPayload.V2Opaque.of(newBytes)),
        )
        return Rebuilt(updatedSource, created, bundle)
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun finishDatabase(project: SketchProject, source: SketchSheet, created: SketchSheet, hash: String, plan: SplitPlan): SplitOutcome {
        val token = repos.projects.loadProject(project.uuid).getOrNull()?.token ?: return failed(SplitFailure.Write, "project token")
        write { repos.sheets.saveSheet(source, token) }.onLeft { return failed(SplitFailure.Write, "source row") }
        write { repos.sheets.saveSheet(created, token) }.onLeft { return failed(SplitFailure.Write, "new row") }
        val fresh = repos.projects.loadProject(project.uuid).getOrNull()?.project ?: project
        write { repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }
            .onLeft { return failed(SplitFailure.Write, "project base") }
        return SplitOutcome.Split(created.uuid, plan.movedIds.size, plan.clearedBindings, plan.carriedConstraints, plan.droppedConstraints)
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        writeActor?.execute { op() } ?: op()

    private fun stop(outcome: SidecarWriteOutcome): SplitOutcome? = when (outcome) {
        SidecarWriteOutcome.Written -> null
        SidecarWriteOutcome.Deferred -> failed(SplitFailure.Deferred)
        SidecarWriteOutcome.Unavailable -> failed(SplitFailure.StorageUnavailable)
        SidecarWriteOutcome.StaleOpaque -> failed(SplitFailure.Write, "a sheet file changed")
        is SidecarWriteOutcome.Failed -> failed(SplitFailure.Write, "store")
    }

    private fun failed(reason: SplitFailure, detail: String = ""): SplitOutcome.Failed = SplitOutcome.Failed(reason, detail)
}
