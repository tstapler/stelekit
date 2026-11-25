// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.LocalBundleLoader
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarBehindGuard
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchSearchRow
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.merge.MergeReport
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import dev.stapler.stelekit.sketch.v2.search.SketchSearchText

/** One encoded sheet: its bytes, their sha and the layer mirror the model carries. */
internal class Encoded(val bytes: ByteArray, val sha: String, val layers: List<String>, val search: SketchSearchText? = null)

internal sealed interface Persist {
    data object Written : Persist
    data object Deferred : Persist
    data class Conflict(val diskSha: String?) : Persist
    data class Failed(val reason: SaveFailure) : Persist

    /** The file had changed under the saver and the element-level merge of both versions was written (ADR-008); [doc] is what is now on disk. */
    class Merged(val doc: SketchDocumentV2, val encoded: Encoded, val report: MergeReport) : Persist

    /** The store refused the write because this tab no longer holds the sketch writer lease (D-142). */
    data object LeaseLost : Persist
}

/**
 * What the persister needs to merge a changed file instead of reporting a conflict: [base] yields the document whose bytes hash to the
 * saver's `baseSha` (null when unknown, which means no merge), [ours] is the scene being saved, [encode] writes a merged scene.
 */
internal class MergeAttempt(val base: () -> SketchDocumentV2?, val ours: SketchDocumentV2, val encode: (SketchDocumentV2) -> Encoded?)

/**
 * The locked read-check-write of [SheetSaver]: compares the file on disk with the sha the saver last saw, writes the sheet,
 * the model and the database row, and moves the reconcile base. A v2 sheet that changed under the write
 * ([SidecarWriteOutcome.StaleOpaque]) is re-planned from a fresh read.
 */
internal class SheetPersister(private val target: SheetTarget, private val ports: SheetSaverPorts) {
    private val sheetPath = SheetPaths.pathFor(target.sheet, SheetPaths.SCHEMA_V2)

    /** The bytes of this sheet's file on disk, or null when it is absent or the sidecar cannot be read. */
    suspend fun readDiskBytes(): ByteArray? = (ports.store.read(target.slug) as? SidecarRead.Present)?.files?.get(sheetPath)

    suspend fun persist(encoded: Encoded, baseSha: String?, newRow: SketchSheet? = null, merge: MergeAttempt? = null): Persist = ports.slugLock.withSlug(target.slug) {
        repeat(REPLAN_ATTEMPTS) {
            when (val attempt = persistOnce(encoded, baseSha, newRow, merge)) {
                null -> Unit
                else -> return@withSlug attempt
            }
        }
        Persist.Failed(SaveFailure.Write)
    }

    /** Null means a v2 sheet changed under the write and the pass must be redone. */
    private suspend fun persistOnce(requested: Encoded, baseSha: String?, newRow: SketchSheet?, merge: MergeAttempt?): Persist? {
        val read = ports.store.read(target.slug)
        val files = when (read) {
            SidecarRead.Unavailable -> return Persist.Failed(SaveFailure.Unavailable)
            is SidecarRead.Corrupt -> return Persist.Conflict(null)
            SidecarRead.Absent -> emptyMap()
            is SidecarRead.Present -> read.files
        }
        val check = SheetSaveGuard.check(baseSha, files[sheetPath], requested.sha)
        // A changed file is merged, never overwritten; on any doubt (no base, unreadable file, refused merge) the caller keeps a conflict copy.
        val merged = if (check is BaseCheck.Changed) mergeWithDisk(merge, files[sheetPath]) ?: return Persist.Conflict(check.diskSha) else null
        val encoded = merged?.encoded ?: requested
        val loaded = ports.repos.projects.loadProject(target.projectUuid).getOrNull() ?: return Persist.Failed(SaveFailure.Write)
        // The model on disk moved past this device's last reconcile: a push from here would overwrite what a pull brought.
        if (SidecarBehindGuard.isBehind(loaded.project, files)) return Persist.Deferred
        val stored = LocalBundleLoader.load(ports.repos, loaded.project, files + (sheetPath to encoded.bytes)).getOrNull()
            ?: return Persist.Failed(SaveFailure.Write)
        // A sheet that is not in the database yet is added here, so its file and the model land before its row does.
        val local = if (newRow != null && stored.sheets.none { it.uuid == target.sheet }) stored.copy(sheets = stored.sheets + newRow) else stored
        val row = local.sheets.firstOrNull { it.uuid == target.sheet } ?: return Persist.Failed(SaveFailure.Write)
        val updatedRow = row.copy(schemaVersion = SheetPaths.SCHEMA_V2, layers = encoded.layers)
        val bundle = local.copy(
            sheets = local.sheets.map { if (it.uuid == target.sheet) updatedRow else it },
            sheetPayloads = local.sheetPayloads + (target.sheet to SheetPayload.V2Opaque.of(encoded.bytes)),
        )
        val modelFiles = SidecarCodec.encode(bundle)
        val others = bundle.sheetPayloads.filterKeys { it != target.sheet }
            .mapNotNull { (uuid, payload) -> (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 } }.toMap()
        return when (val outcome = ports.store.writeNow(target.slug, modelFiles - others.keys, others)) {
            SidecarWriteOutcome.Written -> finishWrite(loaded.project, updatedRow, SidecarCodec.bundleHash(modelFiles), encoded.search).let { done ->
                if (done == Persist.Written && merged != null) merged else done
            }
            SidecarWriteOutcome.Deferred -> Persist.Deferred
            SidecarWriteOutcome.StaleOpaque -> null
            SidecarWriteOutcome.Unavailable -> Persist.Failed(SaveFailure.Unavailable)
            is SidecarWriteOutcome.Failed -> failureOf(outcome.error)
        }
    }

    private fun mergeWithDisk(merge: MergeAttempt?, diskBytes: ByteArray?): Persist.Merged? {
        if (merge == null || diskBytes == null) return null
        val base = merge.base() ?: return null
        val theirs = SheetCodecV2.decode(diskBytes).getOrNull() ?: return null
        val result = SceneMerge.merge(base, merge.ours, theirs).getOrNull() ?: return null
        val encoded = merge.encode(result.doc) ?: return null
        return Persist.Merged(result.doc, encoded, result.report)
    }

    private fun failureOf(error: DomainError): Persist =
        if (error is DomainError.FileSystemError.WriterLeaseNotHeld) Persist.LeaseLost else Persist.Failed(SaveFailure.Write)

    /** The files are written: mirror the sheet row and move the reconcile base so the next reconcile reads `Unchanged`. */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun finishWrite(project: SketchProject, row: SketchSheet, hash: String, search: SketchSearchText?): Persist {
        val token = ports.repos.projects.loadProject(project.uuid).getOrNull()?.token ?: return Persist.Failed(SaveFailure.Write)
        val rowResult = write { ports.repos.sheets.saveSheet(row, token) }
        if (rowResult.isLeft()) return Persist.Failed(SaveFailure.Write)
        val fresh = ports.repos.projects.loadProject(project.uuid).getOrNull()?.project ?: project
        val baseResult = write { ports.repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }
        if (baseResult.isLeft()) return Persist.Failed(SaveFailure.Write)
        indexSearchText(project, search)
        return Persist.Written
    }

    /** Best effort: the text index is a cache the next backfill repairs, so a failed write never fails the save. */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun indexSearchText(project: SketchProject, search: SketchSearchText?) {
        if (search == null) return
        val row = SketchSearchRow(project.uuid.value, target.sheet.value, search.body, search.hash, ports.clockMillis())
        write { ports.repos.searchText.save(row) }
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        ports.writeActor?.execute { op() } ?: op()

    private companion object {
        const val REPLAN_ATTEMPTS = 3
    }
}
