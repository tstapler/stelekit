// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.LocalBundleLoader
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarBehindGuard
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories

/**
 * The project bundle a technical sheet's panel reads and edits. A save grafts ONLY the panel's data (the named dimensions and
 * this sheet's zones) onto a freshly loaded bundle, so a sheet saved meanwhile is never overwritten by a stale copy; the files
 * and the database rows are written under the slug lock, the same way a sheet save does.
 */
class ProjectTechnicalData(
    private val repos: SketchRepositories,
    private val store: ProjectSidecarStore,
    private val slugLock: SidecarSlugLock,
    private val writeActor: DatabaseWriteActor?,
    private val slug: String,
    private val project: ProjectUuid,
    private val sheet: SheetUuid,
) : TechnicalDataSource {
    override suspend fun load(): ProjectBundle? = slugLock.withSlug(slug) { current()?.second }

    override suspend fun save(bundle: ProjectBundle): Boolean = slugLock.withSlug(slug) {
        val (files, local) = current() ?: return@withSlug false
        if (SidecarBehindGuard.isBehind(local.project, files)) return@withSlug false
        val merged = graft(local, bundle)
        merged == local || persist(merged)
    }

    /** The files on disk and the bundle built from them and the database, or null when either cannot be read. */
    private suspend fun current(): Pair<Map<String, ByteArray>, ProjectBundle>? {
        val files = when (val read = store.read(slug)) {
            is SidecarRead.Present -> read.files
            SidecarRead.Absent -> emptyMap()
            else -> return null
        }
        val stored = repos.projects.loadProject(project).getOrNull()?.project ?: return null
        val bundle = LocalBundleLoader.load(repos, stored, files).getOrNull() ?: return null
        return files to bundle
    }

    private fun graft(local: ProjectBundle, edited: ProjectBundle): ProjectBundle {
        val zones = edited.sheets.firstOrNull { it.uuid == sheet }?.zones
        return local.copy(
            dimensions = edited.dimensions,
            sheets = local.sheets.map { if (it.uuid == sheet && zones != null) it.copy(zones = zones) else it },
        )
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun persist(merged: ProjectBundle): Boolean {
        val modelFiles = SidecarCodec.encode(merged)
        val others = merged.sheetPayloads.mapNotNull { (uuid, payload) ->
            (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 }
        }.toMap()
        if (store.writeNow(slug, modelFiles - others.keys, others) != SidecarWriteOutcome.Written) return false
        val hash = SidecarCodec.bundleHash(modelFiles)
        if (write { repos.projects.replaceProjectContents(project, merged) }.isLeft()) return false
        val fresh = repos.projects.loadProject(project).getOrNull()?.project ?: return false
        return write { repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }.isRight()
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> = writeActor?.execute { op() } ?: op()
}
