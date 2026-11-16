// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.convert

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.CorruptReason
import dev.stapler.stelekit.projectbridge.LocalBundleLoader
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetCodecV1
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSheetRepository
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import kotlinx.coroutines.flow.first

/** Why a restore did not happen. Nothing is lost in any case: the sheet stays readable as it was. */
enum class RestoreFailure(val token: String) {
    NotFound("not-found"),

    /** This platform cannot store sidecar files, so nothing can be restored here (D-104). */
    StorageUnavailable("storage-unavailable"),
    CorruptSidecar("corrupt-sidecar"),

    /** The sidecar was written by a newer build: this build must not write anything under it (UX-68). */
    NewerSchema("newer-schema"),
    Write("write"),
}

sealed interface RestoreOutcome {
    /** [keptCopy] is the project-relative path of the new-editor version that was kept (`backup/<uuid>.v2-before-restore.json`). */
    data class Restored(val sheet: SheetUuid, val keptCopy: String) : RestoreOutcome

    /** The sheet is still a v1 sheet; nothing was written. */
    data object NotConverted : RestoreOutcome

    /** `backup/<uuid>.v1.json` is absent or is not a v1 sheet; nothing was changed. */
    data object BackupMissing : RestoreOutcome

    /** A git sync is in flight; the partial state is crash-safe and the next attempt resumes. Nothing is parked. */
    data object Deferred : RestoreOutcome

    data class Failed(val reason: RestoreFailure, val detail: String = "") : RestoreOutcome
}

/** The steps of the protocol, each a crash boundary. */
enum class RestoreStep { V2Kept, V1Staged, ModelFlipped, V2Removed }

/** Test seam: called after each step completes; a test throws from it to simulate the process dying there. */
fun interface RestoreCrashPoint {
    fun after(step: RestoreStep)

    companion object {
        val None = RestoreCrashPoint { }
    }
}

/**
 * Puts a converted sheet back to its original v1 content (Story 6.2.6, ADR-001 rollback). Every step is a crash boundary
 * and the project reads `Present` after each one, because the model keeps naming one valid sheet file until ONE model
 * write flips it:
 *
 * 1. the current v2 file is copied to `backup/<uuid>.v2-before-restore.json`;
 * 2. `sheets/<uuid>.json` is staged from `backup/<uuid>.v1.json` beside the unchanged bundle;
 * 3. one model write names the v1 file, its schema and its sha again;
 * 4. `sheets/<uuid>.v2.json` is deleted, the only remover of a schema-2 file;
 * 5. the database rows follow (`schema_version = 1`).
 *
 * A retry after a crash finishes from whatever the disk holds: a disk already on v1 while the database still says v2 only
 * runs steps 4 and 5. No sha of the original is stored anywhere, so a backup counts as missing when it does not decode as a
 * v1 sheet; the staged file is read back and sha-checked by the store's verified write. The sheet's layer list stays the one
 * conversion projected from the original primitives.
 */
class SketchBackupRestore(
    private val repos: SketchRepositories,
    private val store: ProjectSidecarStore,
    private val slugLock: SidecarSlugLock,
    private val writeActor: DatabaseWriteActor? = null,
    private val log: SketchLog = SketchLog(),
    private val crashPoint: RestoreCrashPoint = RestoreCrashPoint.None,
) {
    /** Restores [sheet] of the sketch project [slug]. Idempotent: a second run on a restored sheet changes nothing. */
    suspend fun restore(slug: String, sheet: SheetUuid): RestoreOutcome =
        slugLock.withSlug(slug) { restoreLocked(slug, sheet).also { log.migrateOutcome("restore-${token(it)}") } }

    private suspend fun restoreLocked(slug: String, sheet: SheetUuid): RestoreOutcome {
        val disk = when (val read = store.read(slug)) {
            SidecarRead.Unavailable -> return failed(RestoreFailure.StorageUnavailable)
            SidecarRead.Absent -> return failed(RestoreFailure.NotFound, "sidecar")
            is SidecarRead.Corrupt ->
                return failed(if (read.reason == CorruptReason.NewerSchema) RestoreFailure.NewerSchema else RestoreFailure.CorruptSidecar)
            is SidecarRead.Present -> read.files
        }
        val onDisk = SidecarCodec.decode(disk).getOrNull() ?: return failed(RestoreFailure.CorruptSidecar)
        val diskRow = onDisk.sheets.firstOrNull { it.uuid == sheet } ?: return failed(RestoreFailure.NotFound, "sheet")
        if (diskRow.schemaVersion == SheetPaths.SCHEMA_V1) {
            val rowStillV2 = databaseSchema(onDisk, sheet) == SheetPaths.SCHEMA_V2
            return if (rowStillV2) resumeFlipped(slug, sheet, onDisk, disk) else RestoreOutcome.NotConverted
        }
        val backup = store.readBackup(slug, sheet.value) ?: return RestoreOutcome.BackupMissing
        val primitives = runCatching { SheetCodecV1.decode(sheet, backup) }.getOrNull() ?: return RestoreOutcome.BackupMissing
        val project = repos.projects.loadProject(onDisk.project.uuid).getOrNull()?.project ?: return failed(RestoreFailure.NotFound, "project")
        val local = LocalBundleLoader.load(repos, project, disk).getOrNull() ?: return failed(RestoreFailure.Write, "local bundle")
        if (local.sheets.firstOrNull { it.uuid == sheet }?.schemaVersion != SheetPaths.SCHEMA_V2) return RestoreOutcome.NotConverted
        val flipped = local.copy(
            sheets = local.sheets.map { if (it.uuid == sheet) it.copy(schemaVersion = SheetPaths.SCHEMA_V1) else it },
            primitives = local.primitives + primitives,
            sheetPayloads = local.sheetPayloads - sheet,
        )
        return execute(slug, sheet, disk, flipped)
    }

    private suspend fun execute(slug: String, sheet: SheetUuid, disk: Map<String, ByteArray>, flipped: ProjectBundle): RestoreOutcome {
        val modelFiles = SidecarCodec.encode(flipped)
        val v1Path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V1)

        disk[SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)]?.let { v2 ->
            store.writeV2BeforeRestore(slug, sheet.value, v2).onLeft { return failed(RestoreFailure.Write, "keep v2") }
        }
        crashPoint.after(RestoreStep.V2Kept)

        when (val staged = store.writeNow(slug, disk + (v1Path to modelFiles.getValue(v1Path)))) {
            SidecarWriteOutcome.Written -> Unit
            else -> return stopped(staged, "stage")
        }
        crashPoint.after(RestoreStep.V1Staged)

        // Other v2 sheets are opaque for this write so it never rewrites them; this sheet's v2 file is unlisted, so never swept.
        val otherV2 = flipped.sheetPayloads.entries.mapNotNull { (uuid, payload) ->
            (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 }
        }.toMap()
        when (val flip = store.writeNow(slug, modelFiles - otherV2.keys, otherV2)) {
            SidecarWriteOutcome.Written -> Unit
            else -> return stopped(flip, "flip")
        }
        crashPoint.after(RestoreStep.ModelFlipped)

        val project = flipped.project
        val synced = project.copy(lastSyncedSidecarHash = SidecarCodec.bundleHash(modelFiles), baseRevision = project.modelRevision)
        return finish(slug, sheet, flipped.copy(project = synced))
    }

    /** A crash after the model write left the disk on v1 while the database row still says v2: only the last two steps remain. */
    private suspend fun resumeFlipped(slug: String, sheet: SheetUuid, onDisk: ProjectBundle, disk: Map<String, ByteArray>): RestoreOutcome {
        val local = repos.projects.loadProject(onDisk.project.uuid).getOrNull()?.project ?: return failed(RestoreFailure.NotFound, "project")
        val adopted = onDisk.project.copy(
            fusionResults = local.fusionResults, sourceHash = local.sourceHash, lastDigestHash = local.lastDigestHash,
            baseRevision = onDisk.project.modelRevision, lastSyncedSidecarHash = SidecarCodec.bundleHash(disk),
        )
        return finish(slug, sheet, onDisk.copy(project = adopted))
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun finish(slug: String, sheet: SheetUuid, bundle: ProjectBundle): RestoreOutcome {
        store.deleteV2Sheet(slug, sheet.value).onLeft { return failed(RestoreFailure.Write, "remove v2") }
        crashPoint.after(RestoreStep.V2Removed)
        write { repos.projects.replaceProjectContents(bundle.project.uuid, bundle) }.onLeft { return failed(RestoreFailure.Write, "database") }
        return RestoreOutcome.Restored(sheet, keptCopyPath(sheet))
    }

    private suspend fun databaseSchema(onDisk: ProjectBundle, sheet: SheetUuid): Long? =
        repos.sheets.getSheets(onDisk.project.uuid, maxOf(onDisk.sheets.size, SketchSheetRepository.DEFAULT_SHEET_LIMIT)).first()
            .getOrNull()?.items?.firstOrNull { it.uuid == sheet }?.schemaVersion

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> = writeActor?.execute { op() } ?: op()

    private fun stopped(outcome: SidecarWriteOutcome, step: String): RestoreOutcome = when (outcome) {
        SidecarWriteOutcome.Deferred -> RestoreOutcome.Deferred
        SidecarWriteOutcome.Unavailable -> failed(RestoreFailure.StorageUnavailable)
        SidecarWriteOutcome.StaleOpaque -> failed(RestoreFailure.Write, "$step: a sheet file changed")
        is SidecarWriteOutcome.Failed -> failed(RestoreFailure.Write, step)
        SidecarWriteOutcome.Written -> error("not a stop")
    }

    private fun failed(reason: RestoreFailure, detail: String = ""): RestoreOutcome.Failed = RestoreOutcome.Failed(reason, detail)

    private fun token(o: RestoreOutcome): String = when (o) {
        is RestoreOutcome.Restored -> "restored"
        RestoreOutcome.NotConverted -> "not-converted"
        RestoreOutcome.BackupMissing -> "backup-missing"
        RestoreOutcome.Deferred -> "deferred"
        is RestoreOutcome.Failed -> o.reason.token
    }

    companion object {
        /** Project-relative path of the kept new-editor copy; the notice and the confirmation name it. */
        fun keptCopyPath(sheet: SheetUuid): String = "backup/${sheet.value}.v2-before-restore.json"
    }
}
