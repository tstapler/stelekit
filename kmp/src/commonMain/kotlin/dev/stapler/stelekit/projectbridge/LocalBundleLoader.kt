// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.repository.SketchDimensionRepository
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSheetRepository
import kotlinx.coroutines.flow.first

/**
 * Builds the local [ProjectBundle] of a project: the one place the database state and the sidecar's v2 bytes meet.
 * Shared by [SidecarReconciler] and `SheetSaver` so both hash the same bundle.
 */
internal object LocalBundleLoader {
    private const val MAX_SHEETS = 500

    /**
     * The database holds no v2 content, so a v2 sheet's bytes come from [sidecarFiles] (the files read from the local
     * sidecar); a v2 sheet whose file is not there makes the local bundle unbuildable.
     */
    suspend fun load(repos: SketchRepositories, project: SketchProject, sidecarFiles: Map<String, ByteArray>): Either<DomainError, ProjectBundle> {
        val fresh = repos.projects.loadProject(project.uuid).fold({ return it.left() }, { it })?.project ?: project
        val sheetPage = repos.sheets.getSheets(fresh.uuid, MAX_SHEETS).first().fold({ return it.left() }, { it })
        if (sheetPage.hasMore) return DomainError.DatabaseError.ReadFailed("more than $MAX_SHEETS sheets").left()
        val primitives = mutableListOf<SketchPrimitive>()
        val payloads = linkedMapOf<SheetUuid, SheetPayload>()
        for (sheet in sheetPage.items) {
            if (sheet.schemaVersion == SheetPaths.SCHEMA_V2) {
                val bytes = sidecarFiles[SheetPaths.pathFor(sheet.uuid, SheetPaths.SCHEMA_V2)]
                    ?: return DomainError.DatabaseError.ReadFailed("v2 sheet ${sheet.uuid.value} has no sidecar file on this device").left()
                payloads[sheet.uuid] = SheetPayload.V2Opaque.of(bytes)
                continue
            }
            var offset = 0
            while (true) {
                val page = repos.sheets.getPrimitives(sheet.uuid, SketchSheetRepository.MAX_PRIMITIVE_PAGE, offset).first()
                    .fold({ return it.left() }, { it })
                primitives += page
                if (page.size < SketchSheetRepository.MAX_PRIMITIVE_PAGE) break
                offset += page.size
            }
        }
        val dimensions = mutableListOf<SketchDimension>()
        var dOffset = 0
        while (true) {
            val page = repos.dimensions.getDimensions(fresh.uuid, SketchDimensionRepository.MAX_DIMENSION_PAGE, dOffset).first()
                .fold({ return it.left() }, { it })
            dimensions += page
            if (page.size < SketchDimensionRepository.MAX_DIMENSION_PAGE) break
            dOffset += page.size
        }
        return ProjectBundle(fresh, sheetPage.items, primitives, dimensions, payloads).right()
    }
}
