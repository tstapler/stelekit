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
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.DirectRepositoryWrite

/** One encoded sheet: its bytes, their sha and the layer mirror the model carries. */
internal class Encoded(val bytes: ByteArray, val sha: String, val layers: List<String>)

internal sealed interface Persist {
    data object Written : Persist
    data object Deferred : Persist
    data class Conflict(val diskSha: String?) : Persist
    data class Failed(val reason: SaveFailure) : Persist
}

/**
 * The locked read-check-write of [SheetSaver]: compares the file on disk with the sha the saver last saw, writes the sheet,
 * the model and the database row, and moves the reconcile base. A v2 sheet that changed under the write
 * ([SidecarWriteOutcome.StaleOpaque]) is re-planned from a fresh read.
 */
internal class SheetPersister(private val target: SheetTarget, private val ports: SheetSaverPorts) {
    private val sheetPath = SheetPaths.pathFor(target.sheet, SheetPaths.SCHEMA_V2)

    /** The bytes of this sheet's file on disk, or null when it is absent or the sidecar cannot be read. */
    suspend fun readDiskBytes(): ByteArray? = (ports.store.read(target.slug) as? SidecarRead.Present)?.files?.get(sheetPath)

    suspend fun persist(encoded: Encoded, baseSha: String?): Persist = ports.slugLock.withSlug(target.slug) {
        repeat(REPLAN_ATTEMPTS) {
            when (val attempt = persistOnce(encoded, baseSha)) {
                null -> Unit
                else -> return@withSlug attempt
            }
        }
        Persist.Failed(SaveFailure.Write)
    }

    /** Null means a v2 sheet changed under the write and the pass must be redone. */
    private suspend fun persistOnce(encoded: Encoded, baseSha: String?): Persist? {
        val read = ports.store.read(target.slug)
        val files = when (read) {
            SidecarRead.Unavailable -> return Persist.Failed(SaveFailure.Unavailable)
            is SidecarRead.Corrupt -> return Persist.Conflict(null)
            SidecarRead.Absent -> emptyMap()
            is SidecarRead.Present -> read.files
        }
        val check = SheetSaveGuard.check(baseSha, files[sheetPath], encoded.sha)
        if (check is BaseCheck.Changed) return Persist.Conflict(check.diskSha)
        val loaded = ports.repos.projects.loadProject(target.projectUuid).getOrNull() ?: return Persist.Failed(SaveFailure.Write)
        // The model on disk moved past this device's last reconcile: a push from here would overwrite what a pull brought.
        val base = loaded.project.lastSyncedSidecarHash
        if (base != null && files.isNotEmpty() && SidecarCodec.bundleHash(files) != base) return Persist.Deferred
        val local = LocalBundleLoader.load(ports.repos, loaded.project, files + (sheetPath to encoded.bytes)).getOrNull()
            ?: return Persist.Failed(SaveFailure.Write)
        val row = local.sheets.firstOrNull { it.uuid == target.sheet } ?: return Persist.Failed(SaveFailure.Write)
        val updatedRow = row.copy(schemaVersion = SheetPaths.SCHEMA_V2, layers = encoded.layers)
        val bundle = local.copy(
            sheets = local.sheets.map { if (it.uuid == target.sheet) updatedRow else it },
            sheetPayloads = local.sheetPayloads + (target.sheet to SheetPayload.V2Opaque.of(encoded.bytes)),
        )
        val modelFiles = SidecarCodec.encode(bundle)
        val others = bundle.sheetPayloads.filterKeys { it != target.sheet }
            .mapNotNull { (uuid, payload) -> (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 } }.toMap()
        return when (ports.store.writeNow(target.slug, modelFiles - others.keys, others)) {
            SidecarWriteOutcome.Written -> finishWrite(loaded.project, updatedRow, SidecarCodec.bundleHash(modelFiles))
            SidecarWriteOutcome.Deferred -> Persist.Deferred
            SidecarWriteOutcome.StaleOpaque -> null
            SidecarWriteOutcome.Unavailable -> Persist.Failed(SaveFailure.Unavailable)
            is SidecarWriteOutcome.Failed -> Persist.Failed(SaveFailure.Write)
        }
    }

    /** The files are written: mirror the sheet row and move the reconcile base so the next reconcile reads `Unchanged`. */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun finishWrite(project: SketchProject, row: SketchSheet, hash: String): Persist {
        val token = ports.repos.projects.loadProject(project.uuid).getOrNull()?.token ?: return Persist.Failed(SaveFailure.Write)
        val rowResult = write { ports.repos.sheets.saveSheet(row, token) }
        if (rowResult.isLeft()) return Persist.Failed(SaveFailure.Write)
        val fresh = ports.repos.projects.loadProject(project.uuid).getOrNull()?.project ?: project
        val baseResult = write { ports.repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }
        return if (baseResult.isLeft()) Persist.Failed(SaveFailure.Write) else Persist.Written
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        ports.writeActor?.execute { op() } ?: op()

    private companion object {
        const val REPLAN_ATTEMPTS = 3
    }
}
