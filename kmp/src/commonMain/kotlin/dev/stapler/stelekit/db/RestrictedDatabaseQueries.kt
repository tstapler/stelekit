package dev.stapler.stelekit.db

import app.cash.sqldelight.SuspendingTransactionWithoutReturn

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
        position: Long,
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
    suspend fun updateBlockParent(parent_uuid: String?, uuid: String): Long =
        queries.updateBlockParent(parent_uuid, uuid)

    @DirectSqlWrite
    suspend fun updateBlockParentPositionAndLevel(
        parent_uuid: String?,
        position: Long,
        level: Long,
        uuid: String,
    ): Long = queries.updateBlockParentPositionAndLevel(parent_uuid, position, level, uuid)

    @DirectSqlWrite
    suspend fun updateBlockHierarchy(
        parent_uuid: String?,
        left_uuid: String?,
        position: Long,
        level: Long,
        uuid: String,
    ): Long = queries.updateBlockHierarchy(parent_uuid, left_uuid, position, level, uuid)

    @DirectSqlWrite
    suspend fun updateBlockPositionOnly(position: Long, uuid: String): Long =
        queries.updateBlockPositionOnly(position, uuid)

    @DirectSqlWrite
    suspend fun updateBlockContent(content: String, updated_at: Long, content_hash: String?, uuid: String): Long =
        queries.updateBlockContent(content, updated_at, content_hash, uuid)

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

    // ── Histogram writes ──────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertHistogramBucketIfAbsent(operation_name: String, bucket_ms: Long, recorded_at: Long): Long =
        queries.insertHistogramBucketIfAbsent(operation_name, bucket_ms, recorded_at)

    @DirectSqlWrite
    suspend fun incrementHistogramBucketCount(recorded_at: Long, operation_name: String, bucket_ms: Long): Long =
        queries.incrementHistogramBucketCount(recorded_at, operation_name, bucket_ms)

    @DirectSqlWrite
    suspend fun deleteOldHistogramRows(recorded_at: Long): Long =
        queries.deleteOldHistogramRows(recorded_at)

    // ── Debug flag writes ─────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun upsertDebugFlag(key: String, value_: Long, updated_at: Long): Long =
        queries.upsertDebugFlag(key, value_, updated_at)

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

    // ── Span writes ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertSpan(
        trace_id: String,
        span_id: String,
        parent_span_id: String,
        name: String,
        start_epoch_ms: Long,
        end_epoch_ms: Long,
        duration_ms: Long,
        attributes_json: String,
        status_code: String,
    ): Long = queries.insertSpan(trace_id, span_id, parent_span_id, name, start_epoch_ms, end_epoch_ms, duration_ms, attributes_json, status_code)

    @DirectSqlWrite
    suspend fun deleteSpansOlderThan(end_epoch_ms: Long): Long =
        queries.deleteSpansOlderThan(end_epoch_ms)

    @DirectSqlWrite
    suspend fun deleteExcessSpans(limit: Long): Long =
        queries.deleteExcessSpans(limit)

    @DirectSqlWrite
    suspend fun deleteAllSpans(): Long =
        queries.deleteAllSpans()

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

    // ── Query stats writes ────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertQueryStatIfAbsent(app_version: String, table_name: String, operation: String, first_seen: Long, last_seen: Long): Long =
        queries.insertQueryStatIfAbsent(app_version, table_name, operation, first_seen, last_seen)

    @DirectSqlWrite
    suspend fun mergeQueryStat(
        calls: Long, errors: Long, total_ms: Long,
        min_ms: Long, max_ms: Long,
        b1: Long, b5: Long, b16: Long, b50: Long, b100: Long, b500: Long, b_inf: Long,
        last_seen: Long,
        app_version: String, table_name: String, operation: String,
    ): Long =
        queries.mergeQueryStat(calls, errors, total_ms, min_ms, max_ms, b1, b5, b16, b50, b100, b500, b_inf, last_seen, app_version, table_name, operation)

    @DirectSqlWrite
    suspend fun deleteQueryStatsForVersion(app_version: String): Long =
        queries.deleteQueryStatsForVersion(app_version)

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
    suspend fun recomputeAllBacklinkCounts(): Long = queries.recomputeAllBacklinkCounts()

    @DirectSqlWrite
    suspend fun recomputeBacklinkCountForPage(name: String): Long =
        queries.recomputeBacklinkCountForPage(name)

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
}
