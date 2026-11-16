// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.getOrElse
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import kotlinx.coroutines.flow.first

/** The pulled side of a reconcile: what [SidecarReconciler] found on disk (P). */
sealed interface DiskState {
    data object Absent : DiskState
    data object Corrupt : DiskState
    data class Bundle(val hash: String) : DiskState
}

sealed interface ReconcileAction {
    /** P = L: only the base moves. */
    data class SetBase(val hash: String) : ReconcileAction
    /** Only the pulled side changed: rebuild the database from the files. */
    data object Rebuild : ReconcileAction
    /** Only the local side changed, or the files are missing: write the database state out. */
    data object WriteLocal : ReconcileAction
    /** Both changed: keep the pulled files, keep the local state beside them, surface it. */
    data object Conflict : ReconcileAction
    /** The pulled files are unreadable: keep them untouched and keep the local state beside them. */
    data object KeepCorrupt : ReconcileAction
}

/** The three-way decision of Story 2.5 over L (local bundle hash), B (last synced hash) and P (disk). Pure. */
object ReconcileDecision {
    fun decide(local: String, base: String?, disk: DiskState): ReconcileAction = when (disk) {
        DiskState.Corrupt -> ReconcileAction.KeepCorrupt
        DiskState.Absent -> ReconcileAction.WriteLocal
        is DiskState.Bundle -> when {
            disk.hash == local -> ReconcileAction.SetBase(disk.hash)
            base == null -> ReconcileAction.Conflict
            local == base -> ReconcileAction.Rebuild
            disk.hash == base -> ReconcileAction.WriteLocal
            else -> ReconcileAction.Conflict
        }
    }
}

sealed interface ReconcileOutcome {
    val slug: String
    data class Unchanged(override val slug: String) : ReconcileOutcome
    data class Pushed(override val slug: String) : ReconcileOutcome
    data class Pulled(override val slug: String) : ReconcileOutcome

    /** Both sides changed; the pulled files won and the local state is at [conflictPath]. */
    data class Conflict(override val slug: String, val conflictPath: String?) : ReconcileOutcome

    /** The sidecar is unreadable and was left untouched; the local state is at [conflictPath]. */
    data class CorruptKept(override val slug: String, val reason: CorruptReason, val conflictPath: String?) : ReconcileOutcome
    data class DeviceLocal(override val slug: String) : ReconcileOutcome
    data class Deferred(override val slug: String) : ReconcileOutcome
    data class Failed(override val slug: String, val message: String) : ReconcileOutcome
}

/** What a reconcile pass changed or needs the user to resolve. */
data class ReconcileSummary(val outcomes: List<ReconcileOutcome>) {
    val needsResolution: Boolean get() = outcomes.any { it is ReconcileOutcome.Conflict || it is ReconcileOutcome.CorruptKept }
}

/**
 * Three-way reconcile of each project's database state against its pulled sidecar (Story 2.5). `modelRevision` is
 * never compared: equality is the bundle hash. A pulled sidecar is never overwritten silently, and an unreadable one
 * is never read as absent.
 *
 * On a both-changed conflict the pulled files win and the database is rebuilt from them, with the local state kept
 * in `project-model.conflict-<hash8>.json`. Leaving the database as it was would make the next pass see
 * "only local changed" and overwrite the pulled files.
 */
class SidecarReconciler(
    private val repos: SketchRepositories,
    private val store: ProjectSidecarStore,
    /** Null only in tests without an actor; production writes are serialised through it. */
    private val writeActor: DatabaseWriteActor? = null,
    /** Shared with every other sidecar writer of the graph so no two interleave on one slug. */
    private val slugLock: SidecarSlugLock = SidecarSlugLock(),
) {
    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        writeActor?.execute { op() } ?: op()

    suspend fun reconcileAll(): ReconcileSummary {
        store.flushPending()
        val outcomes = mutableListOf<ReconcileOutcome>()
        val known = HashSet<String>()
        var offset = 0
        while (true) {
            val page = repos.projects.getProjects(PAGE, offset).first().getOrElse { emptyList() }
            for (project in page) {
                known += project.slug
                outcomes += reconcile(project)
            }
            if (page.size < PAGE) break
            offset += PAGE
        }
        for (slug in store.slugsOnDisk().filter { it !in known }) outcomes += importNew(slug)
        return ReconcileSummary(outcomes)
    }

    /**
     * Holds the project's [SidecarSlugLock] across read, plan and write. A push that finds a v2 sheet changed under it
     * ([SidecarWriteOutcome.StaleOpaque]) writes nothing and is re-planned from a fresh read.
     */
    suspend fun reconcile(project: SketchProject): ReconcileOutcome = slugLock.withSlug(project.slug) {
        repeat(MAX_REPLANS) { attempt ->
            // A re-plan starts from the project as the other writer left it (its base hash moved with its write).
            val current = if (attempt == 0) project else repos.projects.loadProject(project.uuid).getOrNull()?.project ?: project
            reconcileOnce(current)?.let { return@withSlug it }
        }
        ReconcileOutcome.Failed(project.slug, "a v2 sheet kept changing during the reconcile")
    }

    /** One read-plan-write pass; null means a v2 sheet changed under the push and the pass must be redone. */
    private suspend fun reconcileOnce(project: SketchProject): ReconcileOutcome? {
        val slug = project.slug
        val read = store.read(slug)
        if (read is SidecarRead.Unavailable) return ReconcileOutcome.DeviceLocal(slug)
        val presentFiles = (read as? SidecarRead.Present)?.files.orEmpty()
        // A v2 sheet whose bytes do not hash to the sha its model names is corrupt: kept, never repaired by a push.
        val corruptReason = (read as? SidecarRead.Corrupt)?.reason ?: if (v2ShaMismatch(presentFiles)) CorruptReason.Truncated else null
        val disk = when {
            corruptReason != null -> DiskState.Corrupt
            read is SidecarRead.Present -> DiskState.Bundle(SidecarCodec.bundleHash(read.files))
            else -> DiskState.Absent
        }
        val local = loadLocal(project, presentFiles).getOrElse {
            return if (corruptReason != null) ReconcileOutcome.CorruptKept(slug, corruptReason, null) else ReconcileOutcome.Failed(slug, it.message)
        }
        val localFiles = SidecarCodec.encode(local)
        val localHash = SidecarCodec.bundleHash(localFiles)
        return when (val action = ReconcileDecision.decide(localHash, project.lastSyncedSidecarHash, disk)) {
            is ReconcileAction.SetBase -> setBase(project, action.hash).let { if (it == null) ReconcileOutcome.Unchanged(slug) else ReconcileOutcome.Failed(slug, it) }
            ReconcileAction.WriteLocal -> push(project, local, localFiles, localHash)
            ReconcileAction.Rebuild -> pull(project, (read as SidecarRead.Present).files, (disk as DiskState.Bundle).hash, conflictPath = null)
            ReconcileAction.Conflict -> {
                val path = store.writeConflict(slug, localFiles).getOrNull()
                pull(project, (read as SidecarRead.Present).files, (disk as DiskState.Bundle).hash, conflictPath = path ?: "")
            }
            ReconcileAction.KeepCorrupt -> {
                val path = store.writeConflict(slug, localFiles).getOrNull()
                ReconcileOutcome.CorruptKept(slug, checkNotNull(corruptReason), path)
            }
        }
    }

    private fun v2ShaMismatch(files: Map<String, ByteArray>): Boolean {
        val named = files[SidecarCodec.MODEL_PATH]?.let { SidecarCodec.v2SheetShas(it) } ?: return false
        return named.any { (path, sha) -> files[path]?.let { ContentHasher.sha256(it) } != sha }
    }

    /**
     * Writes the model and the v1 sheets. A v2 sheet's bytes are never written here (they belong to the saver, the
     * converter and restore): the store only verifies each v2 file still has the sha this pass planned against.
     */
    private suspend fun push(project: SketchProject, local: ProjectBundle, files: Map<String, ByteArray>, hash: String): ReconcileOutcome? {
        val opaque = local.sheets.filter { it.schemaVersion == SheetPaths.SCHEMA_V2 }.associate { s ->
            SheetPaths.pathFor(s.uuid, SheetPaths.SCHEMA_V2) to checkNotNull(local.sheetPayloads[s.uuid] as? SheetPayload.V2Opaque).sha256
        }
        return when (val w = store.write(project.slug, files - opaque.keys, opaque)) {
            SidecarWriteOutcome.Written -> setBase(project, hash)?.let { ReconcileOutcome.Failed(project.slug, it) } ?: ReconcileOutcome.Pushed(project.slug)
            SidecarWriteOutcome.Deferred -> ReconcileOutcome.Deferred(project.slug)
            SidecarWriteOutcome.Unavailable -> ReconcileOutcome.DeviceLocal(project.slug)
            is SidecarWriteOutcome.Failed -> ReconcileOutcome.Failed(project.slug, w.error.message)
            SidecarWriteOutcome.StaleOpaque -> null
        }
    }

    /** Rebuilds from the pulled files; [conflictPath] non-null marks a both-changed conflict ("" when the copy failed). */
    private suspend fun pull(project: SketchProject, files: Map<String, ByteArray>, hash: String, conflictPath: String?): ReconcileOutcome {
        val decoded = SidecarCodec.decode(files).getOrElse {
            return ReconcileOutcome.CorruptKept(project.slug, it, null)
        }
        if (decoded.project.uuid != project.uuid) return ReconcileOutcome.Failed(project.slug, "sidecar belongs to another project")
        val rebuilt = decoded.copy(project = adopt(decoded.project, local = project, hash = hash))
        return write { @OptIn(DirectRepositoryWrite::class) repos.projects.replaceProjectContents(project.uuid, rebuilt) }.fold(
            { ReconcileOutcome.Failed(project.slug, it.message) },
            { if (conflictPath == null) ReconcileOutcome.Pulled(project.slug) else ReconcileOutcome.Conflict(project.slug, conflictPath.ifEmpty { null }) },
        )
    }

    private suspend fun importNew(slug: String): ReconcileOutcome = slugLock.withSlug(slug) { importNewLocked(slug) }

    private suspend fun importNewLocked(slug: String): ReconcileOutcome {
        val read = store.read(slug)
        if (read is SidecarRead.Corrupt) return ReconcileOutcome.CorruptKept(slug, read.reason, null)
        if (read !is SidecarRead.Present) return ReconcileOutcome.Unchanged(slug)
        val decoded = SidecarCodec.decode(read.files).getOrElse { return ReconcileOutcome.CorruptKept(slug, it, null) }
        if (decoded.project.slug != slug) return ReconcileOutcome.Failed(slug, "sidecar slug does not match its directory")
        val hash = SidecarCodec.bundleHash(read.files)
        val project = decoded.project.copy(baseRevision = decoded.project.modelRevision, lastSyncedSidecarHash = hash)
        return write { @OptIn(DirectRepositoryWrite::class) repos.projects.replaceProjectContents(project.uuid, decoded.copy(project = project)) }.fold(
            { ReconcileOutcome.Failed(slug, it.message) },
            { ReconcileOutcome.Pulled(slug) },
        )
    }

    /** Moves B (and the revision it was taken at) without touching the editor's revision token. */
    private suspend fun setBase(project: SketchProject, hash: String): String? {
        if (project.lastSyncedSidecarHash == hash) return null
        val fresh = repos.projects.loadProject(project.uuid).getOrNull()?.project ?: project
        return write { @OptIn(DirectRepositoryWrite::class) repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }
            .leftOrNull()?.message
    }

    private fun adopt(fromFile: SketchProject, local: SketchProject, hash: String) = fromFile.copy(
        fusionResults = local.fusionResults, sourceHash = local.sourceHash, lastDigestHash = local.lastDigestHash,
        baseRevision = fromFile.modelRevision, lastSyncedSidecarHash = hash,
    )

    private suspend fun loadLocal(project: SketchProject, sidecarFiles: Map<String, ByteArray>): Either<DomainError, ProjectBundle> =
        LocalBundleLoader.load(repos, project, sidecarFiles)

    private companion object {
        const val MAX_REPLANS = 3
        const val PAGE = 100
    }
}
