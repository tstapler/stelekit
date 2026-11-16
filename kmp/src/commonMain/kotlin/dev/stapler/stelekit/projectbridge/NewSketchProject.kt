// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.SketchSheetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The `NewSketchProject` predicate for an EXISTING project (requirements.md "Flag modes"): it holds at least one sheet,
 * every sheet's FILE has a v2 header, and the sheet list is complete (the bounded query reports no further page). A
 * project created by the very create action is new without asking and never reaches this class.
 *
 * Cost (D-91, D-95, D-106): one model-only read ([ProjectSidecarStore.readModel]), then one prefix read of at most
 * [SheetCodec.HEADER_PROBE_BYTES] bytes per sheet, stopping at the first sheet that is not a v2 header. The file probed
 * is the one the model's `sheetRef` NAMES, never the database mirror's guess. Run it once per create action, and only
 * where the store is available (this returns false when it is not).
 */
class NewSketchProjectPredicate(
    private val store: ProjectSidecarStore,
    private val sheets: SketchSheetRepository,
) {
    suspend fun qualifies(project: ProjectUuid, slug: String): Boolean = withContext(PlatformDispatcher.IO) {
        if (!store.isAvailable()) return@withContext false
        val page = sheets.getSheets(project).first().getOrNull() ?: return@withContext false
        if (page.hasMore || page.items.isEmpty()) return@withContext false
        val model = (store.readModel(slug) as? SidecarRead.Present)?.files?.get(SidecarCodec.MODEL_PATH)
            ?: return@withContext false
        val files = ModelSheetRefs.sheetFiles(model) ?: return@withContext false
        page.items.all { sheet ->
            val rel = files[sheet.uuid.value] ?: return@all false
            val prefix = store.readSheetPrefix(slug, rel, SheetCodec.HEADER_PROBE_BYTES) ?: return@all false
            SheetCodec.classifyHeader(prefix) == HeaderVerdict.V2
        }
    }
}

/**
 * The legacy editor's second line of defence (review C5): whether a sheet's FILE is schema 2, decided from the file
 * header the model's `sheetRef` names. The database mirror may say 1 after a reset, so it is never the arbiter.
 */
class SheetFileSchemaProbe(private val store: ProjectSidecarStore) {
    suspend fun isV2(slug: String, sheet: dev.stapler.stelekit.model.SheetUuid): Boolean {
        val model = (store.readModel(slug) as? SidecarRead.Present)?.files?.get(SidecarCodec.MODEL_PATH) ?: return false
        val rel = ModelSheetRefs.sheetFiles(model)?.get(sheet.value) ?: return false
        val prefix = store.readSheetPrefix(slug, rel, SheetCodec.HEADER_PROBE_BYTES) ?: return false
        return SheetCodec.classifyHeader(prefix) == HeaderVerdict.V2
    }
}
