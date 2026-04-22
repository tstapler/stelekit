package dev.stapler.stelekit.db

import app.cash.sqldelight.TransactionWithoutReturn
import app.cash.sqldelight.db.QueryResult

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
class RestrictedDatabaseQueries(private val queries: SteleDatabaseQueries) {

    // Exposed for read-only SELECT access. Never call write methods (INSERT/UPDATE/DELETE/UPSERT)
    // via this reference — use the annotated methods below instead.
    val rawQueries: SteleDatabaseQueries get() = queries


    // ── Transactions ──────────────────────────────────────────────────────────

    @DirectSqlWrite
    fun transaction(noEnclosing: Boolean = false, body: TransactionWithoutReturn.() -> Unit) =
        queries.transaction(noEnclosing, body)

    // ── Block writes ──────────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertBlock(
        uuid: String,
        page_uuid: String,
        parent_uuid: String?,
        left_uuid: String?,
        content: String,
        level: Long,
        position: Long,
        created_at: Long,
        updated_at: Long,
        properties: String?,
        version: Long,
        content_hash: String?,
        block_type: String,
    ): QueryResult<Long> = queries.insertBlock(
        uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
        created_at, updated_at, properties, version, content_hash, block_type,
    )

    @DirectSqlWrite
    fun updateBlockParent(parent_uuid: String?, uuid: String): QueryResult<Long> =
        queries.updateBlockParent(parent_uuid, uuid)

    @DirectSqlWrite
    fun updateBlockParentPositionAndLevel(
        parent_uuid: String?,
        position: Long,
        level: Long,
        uuid: String,
    ): QueryResult<Long> = queries.updateBlockParentPositionAndLevel(parent_uuid, position, level, uuid)

    @DirectSqlWrite
    fun updateBlockHierarchy(
        parent_uuid: String?,
        left_uuid: String?,
        position: Long,
        level: Long,
        uuid: String,
    ): QueryResult<Long> = queries.updateBlockHierarchy(parent_uuid, left_uuid, position, level, uuid)

    @DirectSqlWrite
    fun updateBlockPositionOnly(position: Long, uuid: String): QueryResult<Long> =
        queries.updateBlockPositionOnly(position, uuid)

    @DirectSqlWrite
    fun updateBlockContent(content: String, updated_at: Long, uuid: String): QueryResult<Long> =
        queries.updateBlockContent(content, updated_at, uuid)

    @DirectSqlWrite
    fun updateBlockLevelOnly(level: Long, uuid: String): QueryResult<Long> =
        queries.updateBlockLevelOnly(level, uuid)

    @DirectSqlWrite
    fun updateBlockLeftUuid(left_uuid: String?, uuid: String): QueryResult<Long> =
        queries.updateBlockLeftUuid(left_uuid, uuid)

    @DirectSqlWrite
    fun updateBlockProperties(properties: String?, uuid: String): QueryResult<Long> =
        queries.updateBlockProperties(properties, uuid)

    @DirectSqlWrite
    fun deleteBlockByUuid(uuid: String): QueryResult<Long> =
        queries.deleteBlockByUuid(uuid)

    @DirectSqlWrite
    fun deleteBlockChildren(parent_uuid: String?): QueryResult<Long> =
        queries.deleteBlockChildren(parent_uuid)

    @DirectSqlWrite
    fun deleteBlocksByPageUuid(page_uuid: String): QueryResult<Long> =
        queries.deleteBlocksByPageUuid(page_uuid)

    @DirectSqlWrite
    fun deleteAllBlocks(): QueryResult<Long> =
        queries.deleteAllBlocks()

    // ── Page writes ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertPage(
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
    ): QueryResult<Long> = queries.insertPage(
        uuid, name, namespace, file_path, created_at, updated_at,
        properties, version, is_favorite, is_journal, journal_date, is_content_loaded,
    )

    @DirectSqlWrite
    fun updatePage(
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
    ): QueryResult<Long> = queries.updatePage(
        namespace, file_path, updated_at, properties, version,
        is_favorite, is_journal, journal_date, is_content_loaded, uuid,
    )

    @DirectSqlWrite
    fun updatePageName(name: String, uuid: String): QueryResult<Long> =
        queries.updatePageName(name, uuid)

    @DirectSqlWrite
    fun updatePageProperties(properties: String?, uuid: String): QueryResult<Long> =
        queries.updatePageProperties(properties, uuid)

    @DirectSqlWrite
    fun updatePageFavorite(is_favorite: Long?, uuid: String): QueryResult<Long> =
        queries.updatePageFavorite(is_favorite, uuid)

    @DirectSqlWrite
    fun deletePageByUuid(uuid: String): QueryResult<Long> =
        queries.deletePageByUuid(uuid)

    @DirectSqlWrite
    fun deleteAllPages(): QueryResult<Long> =
        queries.deleteAllPages()

    // ── Reference writes ──────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertBlockReference(from_block_uuid: String, to_block_uuid: String, created_at: Long): QueryResult<Long> =
        queries.insertBlockReference(from_block_uuid, to_block_uuid, created_at)

    @DirectSqlWrite
    fun deleteBlockReference(from_block_uuid: String, to_block_uuid: String): QueryResult<Long> =
        queries.deleteBlockReference(from_block_uuid, to_block_uuid)

    // ── Plugin data writes ────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertPluginData(
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
        value_: String,
        created_at: Long,
        updated_at: Long?,
    ): QueryResult<Long> = queries.insertPluginData(plugin_id, entity_type, entity_uuid, key, value_, created_at, updated_at)

    @DirectSqlWrite
    fun updatePluginData(
        value_: String,
        updated_at: Long?,
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
    ): QueryResult<Long> = queries.updatePluginData(value_, updated_at, plugin_id, entity_type, entity_uuid, key)

    @DirectSqlWrite
    fun upsertPluginData(
        plugin_id: String,
        entity_type: String,
        entity_uuid: String,
        key: String,
        value_: String,
        created_at: Long,
        updated_at: Long?,
    ): QueryResult<Long> = queries.upsertPluginData(plugin_id, entity_type, entity_uuid, key, value_, created_at, updated_at)

    @DirectSqlWrite
    fun deletePluginData(plugin_id: String, entity_type: String, entity_uuid: String, key: String): QueryResult<Long> =
        queries.deletePluginData(plugin_id, entity_type, entity_uuid, key)

    @DirectSqlWrite
    fun deletePluginDataByPlugin(plugin_id: String): QueryResult<Long> =
        queries.deletePluginDataByPlugin(plugin_id)

    @DirectSqlWrite
    fun deletePluginDataByEntity(entity_type: String, entity_uuid: String): QueryResult<Long> =
        queries.deletePluginDataByEntity(entity_type, entity_uuid)

    // ── Histogram writes ──────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertHistogramBucketIfAbsent(operation_name: String, bucket_ms: Long, recorded_at: Long): QueryResult<Long> =
        queries.insertHistogramBucketIfAbsent(operation_name, bucket_ms, recorded_at)

    @DirectSqlWrite
    fun incrementHistogramBucketCount(recorded_at: Long, operation_name: String, bucket_ms: Long): QueryResult<Long> =
        queries.incrementHistogramBucketCount(recorded_at, operation_name, bucket_ms)

    @DirectSqlWrite
    fun deleteOldHistogramRows(recorded_at: Long): QueryResult<Long> =
        queries.deleteOldHistogramRows(recorded_at)

    // ── Debug flag writes ─────────────────────────────────────────────────────

    @DirectSqlWrite
    fun upsertDebugFlag(key: String, value_: Long, updated_at: Long): QueryResult<Long> =
        queries.upsertDebugFlag(key, value_, updated_at)

    // ── Metadata writes ───────────────────────────────────────────────────────

    @DirectSqlWrite
    fun upsertMetadata(key: String, value_: String): QueryResult<Long> =
        queries.upsertMetadata(key, value_)

    // ── Operation log writes ──────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertOperation(
        op_id: String,
        session_id: String,
        seq: Long,
        op_type: String,
        entity_uuid: String?,
        page_uuid: String?,
        payload: String,
        created_at: Long,
    ): QueryResult<Long> = queries.insertOperation(op_id, session_id, seq, op_type, entity_uuid, page_uuid, payload, created_at)

    @DirectSqlWrite
    fun upsertLogicalClock(session_id: String, seq: Long): QueryResult<Long> =
        queries.upsertLogicalClock(session_id, seq)

    @DirectSqlWrite
    fun deleteOperationsBefore(session_id: String, seq: Long): QueryResult<Long> =
        queries.deleteOperationsBefore(session_id, seq)

    // ── Migration changelog writes ────────────────────────────────────────────

    @DirectSqlWrite
    fun insertMigrationRecord(
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
    ): QueryResult<Long> = queries.insertMigrationRecord(
        id, graph_id, description, checksum, applied_at, execution_ms,
        status, applied_by, execution_order, changes_applied, error_message,
    )

    @DirectSqlWrite
    fun updateMigrationStatus(
        status: String,
        error_message: String?,
        execution_ms: Long,
        changes_applied: Long,
        id: String,
        graph_id: String,
    ): QueryResult<Long> = queries.updateMigrationStatus(status, error_message, execution_ms, changes_applied, id, graph_id)

    @DirectSqlWrite
    fun deleteMigrationRecord(id: String, graph_id: String): QueryResult<Long> =
        queries.deleteMigrationRecord(id, graph_id)

    @DirectSqlWrite
    fun updateMigrationChecksum(checksum: String, id: String, graph_id: String): QueryResult<Long> =
        queries.updateMigrationChecksum(checksum, id, graph_id)

    // ── Span writes ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    fun insertSpan(
        trace_id: String,
        span_id: String,
        parent_span_id: String,
        name: String,
        start_epoch_ms: Long,
        end_epoch_ms: Long,
        duration_ms: Long,
        attributes_json: String,
        status_code: String,
    ): QueryResult<Long> = queries.insertSpan(trace_id, span_id, parent_span_id, name, start_epoch_ms, end_epoch_ms, duration_ms, attributes_json, status_code)

    @DirectSqlWrite
    fun deleteSpansOlderThan(end_epoch_ms: Long): QueryResult<Long> =
        queries.deleteSpansOlderThan(end_epoch_ms)

    @DirectSqlWrite
    fun deleteExcessSpans(limit: Long): QueryResult<Long> =
        queries.deleteExcessSpans(limit)

    @DirectSqlWrite
    fun deleteAllSpans(): QueryResult<Long> =
        queries.deleteAllSpans()

    // ── UUID migration writes ─────────────────────────────────────────────────

    @DirectSqlWrite
    fun updateBlockUuidForMigration(uuid: String, uuid_: String): QueryResult<Long> =
        queries.updateBlockUuidForMigration(uuid, uuid_)

    @DirectSqlWrite
    fun updateParentUuidForMigration(parent_uuid: String?, parent_uuid_: String?): QueryResult<Long> =
        queries.updateParentUuidForMigration(parent_uuid, parent_uuid_)

    @DirectSqlWrite
    fun updateLeftUuidForMigration(left_uuid: String?, left_uuid_: String?): QueryResult<Long> =
        queries.updateLeftUuidForMigration(left_uuid, left_uuid_)

    @DirectSqlWrite
    fun updateBlockReferencesFromForMigration(from_block_uuid: String, from_block_uuid_: String): QueryResult<Long> =
        queries.updateBlockReferencesFromForMigration(from_block_uuid, from_block_uuid_)

    @DirectSqlWrite
    fun updateBlockReferencesToForMigration(to_block_uuid: String, to_block_uuid_: String): QueryResult<Long> =
        queries.updateBlockReferencesToForMigration(to_block_uuid, to_block_uuid_)

    @DirectSqlWrite
    fun updatePropertiesBlockUuidForMigration(block_uuid: String, block_uuid_: String): QueryResult<Long> =
        queries.updatePropertiesBlockUuidForMigration(block_uuid, block_uuid_)
}
