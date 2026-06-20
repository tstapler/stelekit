package dev.stapler.stelekit.db

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import app.cash.sqldelight.db.SqlDriver

/**
 * Wraps [SteleDatabaseQueries] and gates every mutating method behind [DirectSqlWrite].
 *
 * Callers that only read may use [SteleDatabaseQueries] directly. Callers that write must
 * hold a [RestrictedDatabaseQueries] and opt in with @OptIn(DirectSqlWrite::class) at the
 * call site, or route writes through [DatabaseWriteActor].
 *
 * Keep this file in sync with SteleDatabase.sq: every new INSERT/UPDATE/DELETE/UPSERT query
 * needs a corresponding forwarding stub annotated [DirectSqlWrite].
 */
class RestrictedDatabaseQueries(
    private val queries: SteleDatabaseQueries,
    private val driver: SqlDriver? = null,
) {

    // Exposed for read-only SELECT access. Never call write methods (INSERT/UPDATE/DELETE/UPSERT)
    // via this reference — use the annotated methods below instead.
    val rawQueries: SteleDatabaseQueries get() = queries


    // ── Transactions ──────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun transaction(noEnclosing: Boolean = false, body: suspend SuspendingTransactionWithoutReturn.() -> Unit) =
        queries.transaction(noEnclosing, body)

    // ── Block writes ──────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertBlock(
        uuid: String,
        page_uuid: String,
        parent_uuid: String?,
        left_uuid: String?,
        content: String,
        level: Long,
        position: String,
        created_at: Long,
        updated_at: Long,
        properties: String?,
        version: Long,
        content_hash: String?,
        block_type: String,
    ): Long = queries.insertBlock(
        uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
        created_at, updated_at, properties, version, content_hash, block_type,
    )

    @DirectSqlWrite
    suspend fun updateBlockForSave(
        page_uuid: String,
        parent_uuid: String?,
        left_uuid: String?,
        content: String,
        level: Long,
        position: String,
        updated_at: Long,
        properties: String?,
        version: Long,
        content_hash: String?,
        block_type: String,
        uuid: String,
    ): Long = queries.updateBlockForSave(
        page_uuid, parent_uuid, left_uuid, content, level, position,
        updated_at, properties, version, content_hash, block_type, uuid,
    )

    @DirectSqlWrite
    suspend fun updateBlockParent(parent_uuid: String?, uuid: String): Long =
        queries.updateBlockParent(parent_uuid, uuid)

    @DirectSqlWrite
    suspend fun updateBlockParentPositionAndLevel(
        parent_uuid: String?,
        position: String,
        level: Long,
        uuid: String,
    ): Long = queries.updateBlockParentPositionAndLevel(parent_uuid, position, level, uuid)

    @DirectSqlWrite
    suspend fun updateBlockHierarchy(
        parent_uuid: String?,
        left_uuid: String?,
        position: String,
        level: Long,
        uuid: String,
    ): Long = queries.updateBlockHierarchy(parent_uuid, left_uuid, position, level, uuid)

    @DirectSqlWrite
    suspend fun updateBlockPositionOnly(position: String, uuid: String): Long =
        queries.updateBlockPositionOnly(position, uuid)

    @DirectSqlWrite
    suspend fun shiftRootBlockPositionsFrom(page_uuid: String, fromPosition: String): Long =
        queries.shiftRootBlockPositionsFrom(page_uuid, fromPosition)

    @DirectSqlWrite
    suspend fun shiftChildBlockPositionsFrom(parent_uuid: String, fromPosition: String): Long =
        queries.shiftChildBlockPositionsFrom(parent_uuid, fromPosition)

    @DirectSqlWrite
    suspend fun updateBlockContent(content: String, updated_at: Long, content_hash: String?, uuid: String): Long =
        queries.updateBlockContent(content, updated_at, content_hash, uuid)

    @DirectSqlWrite
    suspend fun updateBlockFull(
        page_uuid: String,
        parent_uuid: String?,
        left_uuid: String?,
        content: String,
        level: Long,
        position: String,
        updated_at: Long,
        properties: String?,
        content_hash: String?,
        block_type: String,
        uuid: String,
    ): Long = queries.updateBlockFull(
        page_uuid, parent_uuid, left_uuid, content, level, position,
        updated_at, properties, content_hash, block_type, uuid,
    )

    @DirectSqlWrite
    suspend fun updateBlockLevelOnly(level: Long, uuid: String): Long =
        queries.updateBlockLevelOnly(level, uuid)

    @DirectSqlWrite
    suspend fun updateBlockLeftUuid(left_uuid: String?, uuid: String): Long =
        queries.updateBlockLeftUuid(left_uuid, uuid)

    @DirectSqlWrite
    suspend fun updateBlockProperties(properties: String?, uuid: String): Long =
        queries.updateBlockProperties(properties, uuid)

    @DirectSqlWrite
    suspend fun deleteBlockByUuid(uuid: String): Long =
        queries.deleteBlockByUuid(uuid)

    @DirectSqlWrite
    suspend fun deleteBlockChildren(parent_uuid: String?): Long =
        queries.deleteBlockChildren(parent_uuid)

    @DirectSqlWrite
    suspend fun deleteBlocksByPageUuid(page_uuid: String): Long =
        queries.deleteBlocksByPageUuid(page_uuid)

    @DirectSqlWrite
    suspend fun deleteBlocksByPageUuids(page_uuids: Collection<String>): Long =
        queries.deleteBlocksByPageUuids(page_uuids)

    @DirectSqlWrite
    suspend fun deleteAllBlocks(): Long =
        queries.deleteAllBlocks()

    // ── Page writes ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertPage(
        uuid: String,
        name: String,
        namespace: String?,
        file_path: String?,
        created_at: Long,
        updated_at: Long,
        properties: String?,
        version: Long,
        is_favorite: Long?,
        is_journal: Long?,
        journal_date: String?,
        is_content_loaded: Long,
    ): Long = queries.insertPage(
        uuid, name, namespace, file_path, created_at, updated_at,
        properties, version, is_favorite, is_journal, journal_date, is_content_loaded,
    )

    @DirectSqlWrite
    suspend fun updatePage(
        namespace: String?,
        file_path: String?,
        updated_at: Long,
        properties: String?,
        version: Long,
        is_favorite: Long?,
        is_journal: Long?,
        journal_date: String?,
        is_content_loaded: Long,
        uuid: String,
    ): Long = queries.updatePage(
        namespace, file_path, updated_at, properties, version,
        is_favorite, is_journal, journal_date, is_content_loaded, uuid,
    )

    @DirectSqlWrite
    suspend fun updatePageName(name: String, uuid: String): Long =
        queries.updatePageName(name, uuid)

    @DirectSqlWrite
    suspend fun updatePageProperties(properties: String?, uuid: String): Long =
        queries.updatePageProperties(properties, uuid)

    @DirectSqlWrite
    suspend fun updatePageFavorite(is_favorite: Long?, uuid: String): Long =
        queries.updatePageFavorite(is_favorite, uuid)

    @DirectSqlWrite
    suspend fun deletePageByUuid(uuid: String): Long =
        queries.deletePageByUuid(uuid)

    @DirectSqlWrite
    suspend fun deleteAllPages(): Long =
        queries.deleteAllPages()

    // ── Reference writes ──────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertBlockReference(from_block_uuid: String, to_block_uuid: String, created_at: Long): Long =
        queries.insertBlockReference(from_block_uuid, to_block_uuid, created_at)

    @DirectSqlWrite
    suspend fun deleteBlockReference(from_block_uuid: String, to_block_uuid: String): Long =
        queries.deleteBlockReference(from_block_uuid, to_block_uuid)

    // ── Plugin data writes ────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertPluginData(
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
        value_: String,
        created_at: Long,
        updated_at: Long?,
    ): Long = queries.insertPluginData(plugin_id, entity_type, entity_uuid, key, value_, created_at, updated_at)

    @DirectSqlWrite
    suspend fun updatePluginData(
        value_: String,
        updated_at: Long?,
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
    ): Long = queries.updatePluginData(value_, updated_at, plugin_id, entity_type, entity_uuid, key)

    @DirectSqlWrite
    suspend fun upsertPluginData(
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
        value_: String,
        created_at: Long,
        updated_at: Long?,
    ): Long = queries.upsertPluginData(plugin_id, entity_type, entity_uuid, key, value_, created_at, updated_at)

    @DirectSqlWrite
    suspend fun deletePluginData(plugin_id: String, entity_type: String, entity_uuid: String, key: String): Long =
        queries.deletePluginData(plugin_id, entity_type, entity_uuid, key)

    @DirectSqlWrite
    suspend fun deletePluginDataByPlugin(plugin_id: String): Long =
        queries.deletePluginDataByPlugin(plugin_id)

    @DirectSqlWrite
    suspend fun deletePluginDataByEntity(entity_type: String, entity_uuid: String): Long =
        queries.deletePluginDataByEntity(entity_type, entity_uuid)

    // ── Metadata writes ───────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun upsertMetadata(key: String, value_: String): Long =
        queries.upsertMetadata(key, value_)

    // ── Operation log writes ──────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertOperation(
        op_id: String,
        session_id: String,
        seq: Long,
        op_type: String,
        entity_uuid: String?,
        page_uuid: String?,
        payload: String,
        created_at: Long,
    ): Long = queries.insertOperation(op_id, session_id, seq, op_type, entity_uuid, page_uuid, payload, created_at)

    @DirectSqlWrite
    suspend fun upsertLogicalClock(session_id: String, seq: Long): Long =
        queries.upsertLogicalClock(session_id, seq)

    @DirectSqlWrite
    suspend fun deleteOperationsBefore(session_id: String, seq: Long): Long =
        queries.deleteOperationsBefore(session_id, seq)

    // ── Migration changelog writes ────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertMigrationRecord(
        id: String,
        graph_id: String,
        description: String,
        checksum: String,
        applied_at: Long,
        execution_ms: Long,
        status: String,
        applied_by: String,
        execution_order: Long,
        changes_applied: Long,
        error_message: String?,
    ): Long = queries.insertMigrationRecord(
        id, graph_id, description, checksum, applied_at, execution_ms,
        status, applied_by, execution_order, changes_applied, error_message,
    )

    @DirectSqlWrite
    suspend fun updateMigrationStatus(
        status: String,
        error_message: String?,
        execution_ms: Long,
        changes_applied: Long,
        id: String,
        graph_id: String,
    ): Long = queries.updateMigrationStatus(status, error_message, execution_ms, changes_applied, id, graph_id)

    @DirectSqlWrite
    suspend fun deleteMigrationRecord(id: String, graph_id: String): Long =
        queries.deleteMigrationRecord(id, graph_id)

    @DirectSqlWrite
    suspend fun updateMigrationChecksum(checksum: String, id: String, graph_id: String): Long =
        queries.updateMigrationChecksum(checksum, id, graph_id)

    // ── UUID migration writes ─────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun updateBlockUuidForMigration(uuid: String, uuid_: String): Long =
        queries.updateBlockUuidForMigration(uuid, uuid_)

    @DirectSqlWrite
    suspend fun updateParentUuidForMigration(parent_uuid: String?, parent_uuid_: String?): Long =
        queries.updateParentUuidForMigration(parent_uuid, parent_uuid_)

    @DirectSqlWrite
    suspend fun updateLeftUuidForMigration(left_uuid: String?, left_uuid_: String?): Long =
        queries.updateLeftUuidForMigration(left_uuid, left_uuid_)

    @DirectSqlWrite
    suspend fun updateBlockReferencesFromForMigration(from_block_uuid: String, from_block_uuid_: String): Long =
        queries.updateBlockReferencesFromForMigration(from_block_uuid, from_block_uuid_)

    @DirectSqlWrite
    suspend fun updateBlockReferencesToForMigration(to_block_uuid: String, to_block_uuid_: String): Long =
        queries.updateBlockReferencesToForMigration(to_block_uuid, to_block_uuid_)

    @DirectSqlWrite
    suspend fun updatePropertiesBlockUuidForMigration(block_uuid: String, block_uuid_: String): Long =
        queries.updatePropertiesBlockUuidForMigration(block_uuid, block_uuid_)

    // ── Visit tracking ────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertPageVisitIfAbsent(page_uuid: String, last_visited_at: Long): Long =
        queries.insertPageVisitIfAbsent(page_uuid, last_visited_at)

    @DirectSqlWrite
    suspend fun updatePageVisit(last_visited_at: Long, page_uuid: String): Long =
        queries.updatePageVisit(last_visited_at, page_uuid)

    // ── Maintenance ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun pragmaWalCheckpointTruncate() = queries.pragmaWalCheckpointTruncate()

    @DirectSqlWrite
    suspend fun recomputeBacklinkCountForPage(name: String): Long =
        queries.recomputeBacklinkCountForPage(name)

    @DirectSqlWrite
    suspend fun setPageBacklinkCount(name: String, count: Long): Long =
        queries.setPageBacklinkCount(count, name)

    // ── Wikilink reference index writes ───────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertWikilinkReference(block_uuid: String, page_name: String): Long =
        queries.insertWikilinkReference(block_uuid, page_name)

    /**
     * Inserts multiple wikilink references for [blockUuid] in a single multi-row INSERT OR IGNORE
     * statement per chunk. Each chunk is at most [MAX_WIKILINK_BATCH_SIZE] pairs so the total
     * bind-variable count stays below SQLite's SQLITE_MAX_VARIABLE_NUMBER (999 on Android API<30).
     *
     * Uses raw [SqlDriver.execute] because SQLDelight .sq files cannot express variable-arity
     * VALUES clauses. [driver] may be null only in unit tests that construct the repository
     * without a driver; in that case this method falls back to the per-row path.
     */
    @DirectSqlWrite
    suspend fun insertWikilinkReferencesBatch(blockUuid: String, pageNames: Collection<String>) {
        if (pageNames.isEmpty()) return
        if (driver == null) {
            // Fallback: no driver available (unit-test construction without driver arg).
            for (name in pageNames) queries.insertWikilinkReference(blockUuid, name)
            return
        }
        pageNames.chunked(MAX_WIKILINK_BATCH_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(", ") { "(?, ?)" }
            val sql = "INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES $placeholders"
            // identifier = null disables statement caching — required for variable-length SQL.
            // bindString indices are 0-based in SQLDelight 2.x SqlPreparedStatement.
            // The synchronous JDBC driver returns QueryResult.Value immediately; no .await() needed.
            driver.execute(null, sql, chunk.size * 2) {
                chunk.forEachIndexed { i, name ->
                    bindString(i * 2, blockUuid)
                    bindString(i * 2 + 1, name)
                }
            }
        }
    }

    companion object {
        /** floor(999 / 2) — each pair consumes 2 bind params; stays below SQLITE_MAX_VARIABLE_NUMBER. */
        const val MAX_WIKILINK_BATCH_SIZE = 499
    }

    @DirectSqlWrite
    suspend fun deleteWikilinkReferencesForBlock(block_uuid: String): Long =
        queries.deleteWikilinkReferencesForBlock(block_uuid)

    @DirectSqlWrite
    suspend fun deleteWikilinkReferencesForPageName(page_name: String): Long =
        queries.deleteWikilinkReferencesForPageName(page_name)

    @DirectSqlWrite
    suspend fun recomputeBacklinkCountFromIndex(name: String): Long =
        queries.recomputeBacklinkCountFromIndex(name)

    @DirectSqlWrite
    suspend fun recomputeBacklinkCountsForPages(names: Collection<String>): Long =
        queries.recomputeBacklinkCountsForPages(names)

    @DirectSqlWrite
    suspend fun updateWikilinkPageNameForRename(newName: String, oldName: String): Long =
        queries.updateWikilinkPageNameForRename(newName, oldName)

    // ── Git config writes ─────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertOrReplaceGitConfig(
        graph_id: String,
        repo_root: String,
        wiki_subdir: String,
        remote_name: String,
        remote_branch: String,
        auth_type: String,
        ssh_key_path: String?,
        ssh_key_passphrase_key: String?,
        https_token_key: String?,
        poll_interval_minutes: Long,
        auto_commit: Long,
        commit_message_template: String,
    ) = queries.insertOrReplaceGitConfig(
        graph_id, repo_root, wiki_subdir, remote_name, remote_branch,
        auth_type, ssh_key_path, ssh_key_passphrase_key, https_token_key,
        poll_interval_minutes, auto_commit, commit_message_template,
    )

    @DirectSqlWrite
    suspend fun deleteGitConfig(graph_id: String) = queries.deleteGitConfig(graph_id)

    // ── Image annotation writes ───────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertImageAnnotation(
        uuid: String,
        block_uuid: String,
        page_uuid: String,
        graph_path: String,
        file_path: String,
        thumbnail_path: String?,
        source: String,
        source_uri: String?,
        captured_at_ms: Long?,
        imported_at_ms: Long,
        calibration_method: String,
        pixels_per_meter: Double,
        calibration_confidence_pct: Long,
        unit: String,
        tags: String,
        lat_lng: String?,
        altitude_m: Double?,
        bearing_deg: Double?,
        pitch_deg: Double?,
        roll_deg: Double?,
        focal_length_mm: Double?,
        focal_length_35mm_eq: Double?,
        camera_make: String?,
        camera_model: String?,
    ): Long = queries.insertImageAnnotation(
        uuid, block_uuid, page_uuid, graph_path, file_path, thumbnail_path,
        source, source_uri, captured_at_ms, imported_at_ms,
        calibration_method, pixels_per_meter, calibration_confidence_pct,
        unit, tags, lat_lng, altitude_m, bearing_deg, pitch_deg, roll_deg,
        focal_length_mm, focal_length_35mm_eq, camera_make, camera_model,
    )

    @DirectSqlWrite
    suspend fun updateImageAnnotation(
        block_uuid: String,
        page_uuid: String,
        graph_path: String,
        file_path: String,
        thumbnail_path: String?,
        source: String,
        source_uri: String?,
        captured_at_ms: Long?,
        imported_at_ms: Long,
        calibration_method: String,
        pixels_per_meter: Double,
        calibration_confidence_pct: Long,
        unit: String,
        tags: String,
        lat_lng: String?,
        altitude_m: Double?,
        bearing_deg: Double?,
        pitch_deg: Double?,
        roll_deg: Double?,
        focal_length_mm: Double?,
        focal_length_35mm_eq: Double?,
        camera_make: String?,
        camera_model: String?,
        uuid: String,
    ): Long = queries.updateImageAnnotation(
        block_uuid, page_uuid, graph_path, file_path, thumbnail_path,
        source, source_uri, captured_at_ms, imported_at_ms,
        calibration_method, pixels_per_meter, calibration_confidence_pct,
        unit, tags, lat_lng, altitude_m, bearing_deg, pitch_deg, roll_deg,
        focal_length_mm, focal_length_35mm_eq, camera_make, camera_model,
        uuid,
    )

    @DirectSqlWrite
    suspend fun deleteImageAnnotation(uuid: String): Long =
        queries.deleteImageAnnotation(uuid)

    // ── Measurement annotation writes ─────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertMeasurementAnnotation(
        uuid: String,
        image_uuid: String,
        annotation_type: String,
        normalized_points: String,
        value_meters: Double?,
        value_display: String?,
        label: String?,
        color_hex: String,
        ble_device_id: String?,
    ): Long = queries.insertMeasurementAnnotation(
        uuid, image_uuid, annotation_type, normalized_points,
        value_meters, value_display, label, color_hex, ble_device_id,
    )

    @DirectSqlWrite
    suspend fun deleteMeasurementsForImage(image_uuid: String): Long =
        queries.deleteMeasurementsForImage(image_uuid)

    @DirectSqlWrite
    suspend fun deleteMeasurementAnnotation(uuid: String): Long =
        queries.deleteMeasurementAnnotation(uuid)

    // ── Asset index writes ────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertAsset(
        uuid: String,
        file_path: String,
        relative_path: String,
        media_type: String,
        subfolder: String,
        tags: String,
        auto_labels: String,
        ocr_text: String?,
        cloud_description: String?,
        page_uuids: String,
        size_bytes: Long,
        imported_at_ms: Long,
        content_hash: String?,
    ): Long = queries.insertAsset(
        uuid, file_path, relative_path, media_type, subfolder, tags, auto_labels,
        ocr_text, cloud_description, page_uuids, size_bytes, imported_at_ms, content_hash,
    )

    @DirectSqlWrite
    suspend fun updateAssetFilePath(filePath: String, relativePath: String, uuid: String): Long =
        queries.updateAssetFilePath(filePath, relativePath, uuid)

    @DirectSqlWrite
    suspend fun updateAssetTags(tags: String, uuid: String): Long =
        queries.updateAssetTags(tags, uuid)

    @DirectSqlWrite
    suspend fun updateAssetAutoLabels(autoLabels: String, mlTagsSource: String, uuid: String): Long =
        queries.updateAssetAutoLabels(autoLabels, mlTagsSource, uuid)

    @DirectSqlWrite
    suspend fun updateAssetOcrText(ocrText: String?, uuid: String): Long =
        queries.updateAssetOcrText(ocrText, uuid)

    @DirectSqlWrite
    suspend fun updateAssetCloudDescription(cloudDescription: String?, mlTagsSource: String, uuid: String): Long =
        queries.updateAssetCloudDescription(cloudDescription, mlTagsSource, uuid)

    @DirectSqlWrite
    suspend fun markAssetMlProcessed(attemptedAt: Long, uuid: String): Long =
        queries.markAssetMlProcessed(attemptedAt, uuid)

    @DirectSqlWrite
    suspend fun markAssetMlFailed(attemptedAt: Long, uuid: String): Long =
        queries.markAssetMlFailed(attemptedAt, uuid)

    @DirectSqlWrite
    suspend fun updateAssetPageUuids(pageUuids: String, uuid: String): Long =
        queries.updateAssetPageUuids(pageUuids, uuid)

    @DirectSqlWrite
    suspend fun deleteAsset(uuid: String): Long =
        queries.deleteAsset(uuid)

    // ── Pending asset move writes (WAL) ───────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertPendingMove(
        asset_uuid: String,
        old_file_path: String,
        new_file_path: String,
        old_relative_path: String,
        new_relative_path: String,
        created_at_ms: Long,
    ): Long = queries.insertPendingMove(
        asset_uuid, old_file_path, new_file_path, old_relative_path, new_relative_path, created_at_ms,
    )

    @DirectSqlWrite
    suspend fun deletePendingMove(id: Long): Long =
        queries.deletePendingMove(id)

    // SELECT — not a write, no @DirectSqlWrite needed
    fun lastInsertRowId(): Long = queries.selectLastInsertRowId().executeAsOne()

    @DirectSqlWrite
    suspend fun insertAssetOrIgnore(
        uuid: String,
        file_path: String,
        relative_path: String,
        media_type: String,
        subfolder: String,
        tags: String,
        auto_labels: String,
        ocr_text: String?,
        cloud_description: String?,
        page_uuids: String,
        size_bytes: Long,
        imported_at_ms: Long,
        content_hash: String?,
    ): Long = queries.insertAssetOrIgnore(
        uuid, file_path, relative_path, media_type, subfolder, tags, auto_labels,
        ocr_text, cloud_description, page_uuids, size_bytes, imported_at_ms, content_hash,
    )
}
