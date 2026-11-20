// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import arrow.core.left
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
 * The project bundle a technical sheet's panel reads and edits. A save applies ONLY what the panel changed since [load] (the
 * dimensions and this sheet's zones that differ from the loaded copy) onto a freshly read bundle, so another sheet's save, or a
 * pull that arrived meanwhile, is never overwritten by a stale copy. Files and rows are written under the slug lock like a sheet
 * save: the changed rows only, so no other editor's revision token is spent.
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
    private var loaded: ProjectBundle? = null

    override suspend fun load(): ProjectBundle? = slugLock.withSlug(slug) { current()?.second }.also { loaded = it }

    override suspend fun save(bundle: ProjectBundle): Boolean = slugLock.withSlug(slug) {
        val (files, local) = current() ?: return@withSlug false
        if (SidecarBehindGuard.isBehind(local.project, files)) return@withSlug false
        val merged = graft(local, base = loaded ?: local, edited = bundle)
        merged == local || persist(local, merged)
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

    private fun graft(local: ProjectBundle, base: ProjectBundle, edited: ProjectBundle): ProjectBundle {
        val was = base.dimensions.associateBy { it.key }
        val changed = edited.dimensions.filter { was[it.key] != it }.associateBy { it.key }
        val zones = edited.sheets.firstOrNull { it.uuid == sheet }?.zones
        val zonesChanged = zones != null && zones != base.sheets.firstOrNull { it.uuid == sheet }?.zones
        return local.copy(
            dimensions = local.dimensions.map { changed[it.key] ?: it },
            sheets = local.sheets.map { if (it.uuid == sheet && zonesChanged) it.copy(zones = zones!!) else it },
        )
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun persist(local: ProjectBundle, merged: ProjectBundle): Boolean {
        val modelFiles = SidecarCodec.encode(merged)
        val others = merged.sheetPayloads.mapNotNull { (uuid, payload) ->
            (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 }
        }.toMap()
        if (store.writeNow(slug, modelFiles - others.keys, others) != SidecarWriteOutcome.Written) return false
        for (row in merged.dimensions.filter { it !in local.dimensions }) {
            if (write { repos.dimensions.saveDimension(row, tokenOf() ?: return@write DomainError.DatabaseError.WriteFailed("project gone").left()) }.isLeft()) return false
        }
        for (row in merged.sheets.filter { it !in local.sheets }) {
            if (write { repos.sheets.saveSheet(row, tokenOf() ?: return@write DomainError.DatabaseError.WriteFailed("project gone").left()) }.isLeft()) return false
        }
        val fresh = repos.projects.loadProject(project).getOrNull()?.project ?: return false
        val hash = SidecarCodec.bundleHash(modelFiles)
        return write { repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }.isRight()
    }

    private suspend fun tokenOf() = repos.projects.loadProject(project).getOrNull()?.token

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> = writeActor?.execute { op() } ?: op()
}
