// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSheetRepository
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import kotlinx.coroutines.flow.first

/** One sheet as the v1 consumers see it. [droppedCount] is the v2 elements v1 cannot hold; it is 0 for a v1 sheet. */
class LoadedSheetDocument(val document: SketchDocument, val droppedCount: Int)

/**
 * The one place a consumer of a sheet's drawing (journal preview, CSV and SVG export, the digest's geometry read) gets
 * it, dispatching per sheet on `schema_version` (Story 1.4.5): a v1 sheet reads its `sketch_primitives` rows, a v2 sheet
 * reads the in-memory projection of its sidecar file and never those rows, so a converted sheet cannot show stale data.
 * Mixed projects work because the choice is per sheet. Without a [projection] a v2 sheet is `Left`.
 */
class SketchDocumentSource(
    private val repos: SketchRepositories,
    private val projection: SheetProjectionSource? = null,
) {
    suspend fun load(slug: String, sheet: SketchSheet): Either<DomainError, LoadedSheetDocument> =
        if (sheet.schemaVersion == SheetPaths.SCHEMA_V2) loadV2(slug, sheet) else loadV1(sheet)

    private suspend fun loadV1(sheet: SketchSheet): Either<DomainError, LoadedSheetDocument> = either {
        val stored = mutableListOf<dev.stapler.stelekit.model.SketchPrimitive>()
        var offset = 0
        while (true) {
            val page = repos.sheets.getPrimitives(sheet.uuid, SketchSheetRepository.MAX_PRIMITIVE_PAGE, offset).first().bind()
            stored += page
            if (page.size < SketchSheetRepository.MAX_PRIMITIVE_PAGE) break
            offset += page.size
        }
        val scene = stored.mapNotNull { it.toScenePrimitive() }.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: s }
        LoadedSheetDocument(SketchDocument(scene, ZoneSet.fromJson(sheet.zones), sheet.toSheetProps()), droppedCount = 0)
    }

    private suspend fun loadV2(slug: String, sheet: SketchSheet): Either<DomainError, LoadedSheetDocument> {
        val source = projection ?: return DomainError.DatabaseError.ReadFailed("no projection source for a v2 sheet").left()
        return either {
            val projected = source.project(slug, sheet.uuid).bind()
            var notAdded = 0
            val scene = projected.primitives.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: s.also { notAdded++ } }
            val props = SheetProps.fromStored(sheet.kind, sheet.viewDirection, sheet.scaleSixteenthsPerUnit, projected.layers.map { it.encode() })
            LoadedSheetDocument(SketchDocument(scene, ZoneSet.fromJson(sheet.zones), props), projected.droppedCount + notAdded)
        }
    }
}
