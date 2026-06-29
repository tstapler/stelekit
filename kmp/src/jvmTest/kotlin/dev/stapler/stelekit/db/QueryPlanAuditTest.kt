package dev.stapler.stelekit.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Static index-coverage audit: runs EXPLAIN QUERY PLAN on every SELECT from SteleDatabase.sq
 * against an in-memory SQLite instance and fails CI if any non-allowlisted query performs a
 * heap scan (SCAN <table> with no USING clause — i.e. no index).
 *
 * How to maintain:
 *   - Added a new SELECT to SteleDatabase.sq? → add a matching AuditQuery below AND run the
 *     structural test (`all SELECT queries in SteleDatabase sq are covered by this audit`) to
 *     confirm coverage. The structural test will fail CI automatically if you forget.
 *   - New query is an intentional full scan (analytics, LIKE-based search)? → add to ALLOWLIST.
 *   - New query should use an index but doesn't? → add the index first, then add the query.
 *
 * EXPLAIN QUERY PLAN semantics:
 *   SEARCH <table> USING INDEX … → indexed lookup (good)
 *   SCAN  <table> USING INDEX … → full index traversal (ok — sorted/covering scan)
 *   SCAN  <table>               → heap scan with no index (flagged unless allowlisted)
 *   SCAN  … VIRTUAL TABLE INDEX → FTS5 virtual-table scan (always fine)
 */
class QueryPlanAuditTest {

    private data class AuditQuery(val name: String, val sql: String)

    // ── Queries where a full heap scan is expected / unavoidable ─────────────────────────────
    private val ALLOWLIST = setOf(
        // No WHERE clause — full traversal by design
        "selectAllBlocks", "selectAllBlocksPaginated", "countBlocks",
        "selectAllPagesPaginated", "countPages", "selectPageNameEntries",
        "selectAllMetadata",
        // content LIKE — no index on content; FTS handles production full-text search
        "selectBlocksWithContentLike", "selectBlocksWithContentLikePaginated",
        "countBlocksWithWikilink", "selectBlocksWithWikilink", "countLinkedReferencesForPage",
        // name LIKE — no index covers prefix-wildcard; FTS handles page-name search
        "selectPagesByNameLike", "selectPagesByNameLikePaginated",
        // Aggregate / analytics scans — intentionally full-table
        "selectDuplicateBlockHashes", "selectMostConnectedBlocks", "selectOrphanedBlocks",
        // JOIN where one relation must be fully scanned
        "selectAllBlocksWithPagePath",      // blocks scanned, pages joined by uuid index
        // asset_index LIKE search — tags/auto_labels/ocr_text are unindexed text columns;
        // FTS5 can cover content search but these fields need separate infrastructure
        "searchAssets",
        // COUNT with no WHERE — full traversal of the table
        "countAssets",
        // JSON-array tag search — LIKE on a JSON text column; no structural index possible
        "selectImageAnnotationsByTag",
        // pending_asset_moves is a crash-recovery WAL bounded to <= a handful of rows by design
        "selectAllPendingMoves",
        // SQLite reports "SCAN CONSTANT ROW" for rowid helpers with no FROM clause — not a real scan
        "selectLastInsertRowId",
        // WITH RECURSIVE CTE — SQLite's recursive implementation always scans the CTE working
        // table (SCAN s) and the outer sort result (SCAN subtree). The anchor step uses the
        // unique-index lookup on uuid and the recursive step uses idx_blocks_parent_position
        // when real hierarchical data is present (ANALYZE-verified). The SCAN lines in the plan
        // reflect CTE plumbing, not an unindexed table scan on production data.
        "selectBlockHierarchyRecursive",
        // asset_index LIKE search across multiple unindexed text columns (file_path wildcards,
        // tags JSON, auto_labels, ocr_text); FTS5 can cover structured search but these fields
        // require separate infrastructure
        "selectAssetsBySearchByDateKeyset",
        // is_orphan has no index — orphan browsing is a rare admin path; bounded by LIMIT
        "selectOrphanedAssets", "countOrphanedAssets",
        // tags JSON column has no structural index; full-scan for tag aggregation is acceptable
        // (cold admin / export path)
        "selectAllTagsJson",
        // size_bytes has no index — size-sorted browsing is an uncommon path; add
        // idx_asset_size if it becomes a hot read pattern
        "selectAssetsBySizeKeyset", "selectAssetsByMediaTypeBySizeKeyset",
    )

    // ── All SELECT queries from SteleDatabase.sq, parameters replaced with literals ──────────
    @Suppress("MaxLineLength")
    private val QUERIES = listOf(

        // ── blocks ───────────────────────────────────────────────────────────────────────────
        AuditQuery("selectBlockByUuid",
            "SELECT * FROM blocks WHERE uuid = 'x'"),
        AuditQuery("existsBlockByUuid",
            "SELECT COUNT(*) FROM blocks WHERE uuid = 'x'"),
        AuditQuery("selectAllBlocks",
            "SELECT * FROM blocks ORDER BY uuid"),
        AuditQuery("selectAllBlocksPaginated",
            "SELECT * FROM blocks ORDER BY uuid LIMIT 10 OFFSET 0"),
        AuditQuery("selectAllBlocksPaginatedAfterUuid",
            "SELECT * FROM blocks WHERE uuid > 'x' ORDER BY uuid LIMIT 10"),
        AuditQuery("selectBlockChildren",
            "SELECT * FROM blocks WHERE parent_uuid = 'x' ORDER BY position LIMIT 10 OFFSET 0"),
        AuditQuery("countBlockChildren",
            "SELECT COUNT(*) FROM blocks WHERE parent_uuid = 'x'"),
        AuditQuery("selectBlockSiblings",
            """SELECT * FROM blocks
               WHERE parent_uuid IS (SELECT parent_uuid FROM blocks WHERE uuid = 'x')
               AND page_uuid = (SELECT page_uuid FROM blocks WHERE uuid = 'x')
               AND uuid != 'x'
               ORDER BY position"""),
        AuditQuery("selectRootBlocks",
            "SELECT * FROM blocks WHERE parent_uuid IS NULL AND page_uuid = 'x' ORDER BY position LIMIT 10 OFFSET 0"),
        AuditQuery("countRootBlocks",
            "SELECT COUNT(*) FROM blocks WHERE parent_uuid IS NULL AND page_uuid = 'x'"),
        AuditQuery("selectBlocksByPageUuid",
            "SELECT * FROM blocks WHERE page_uuid = 'x' ORDER BY position LIMIT 10 OFFSET 0"),
        AuditQuery("selectBlocksByPageUuidUnpaginated",
            "SELECT * FROM blocks WHERE page_uuid = 'x' ORDER BY position"),
        AuditQuery("selectBlocksWithContentLike",
            "SELECT * FROM blocks WHERE content LIKE '%test%'"),
        AuditQuery("selectBlocksWithContentLikePaginated",
            "SELECT * FROM blocks WHERE content LIKE '%test%' ORDER BY created_at DESC LIMIT 10 OFFSET 0"),
        AuditQuery("countBlocksByPageUuid",
            "SELECT COUNT(*) FROM blocks WHERE page_uuid = 'x'"),
        AuditQuery("selectBlocksByParentUuidOrdered",
            "SELECT * FROM blocks WHERE parent_uuid = 'x' ORDER BY position"),
        AuditQuery("selectBlocksByParentUuids",
            "SELECT * FROM blocks WHERE parent_uuid IN ('x') ORDER BY parent_uuid, position"),
        AuditQuery("selectBlockHierarchyRecursive",
            """WITH RECURSIVE subtree(
    id, uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
    created_at, updated_at, properties, version, content_hash, block_type, depth
) AS (
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, 0 AS depth
    FROM blocks b
    WHERE b.uuid = 'b0'
    UNION ALL
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, s.depth + 1
    FROM blocks b
    INNER JOIN subtree s ON b.parent_uuid = s.uuid
    WHERE s.depth < 100
)
SELECT * FROM subtree
ORDER BY depth, parent_uuid, position"""),
        AuditQuery("selectRootBlocksByPageUuidOrdered",
            "SELECT * FROM blocks WHERE parent_uuid IS NULL AND page_uuid = 'x' ORDER BY position"),
        AuditQuery("selectBlockByLeftUuid",
            "SELECT * FROM blocks WHERE left_uuid = 'x'"),
        AuditQuery("selectLastChild",
            "SELECT * FROM blocks WHERE parent_uuid = 'x' ORDER BY position DESC LIMIT 1"),
        AuditQuery("countBlocks",
            "SELECT COUNT(*) FROM blocks"),
        AuditQuery("selectBlocksHashByPageUuid",
            "SELECT uuid, content_hash FROM blocks WHERE page_uuid = 'x'"),
        AuditQuery("selectBlocksByUuids",
            "SELECT * FROM blocks WHERE uuid IN ('x')"),
        AuditQuery("selectBlocksByContentHash",
            "SELECT * FROM blocks WHERE content_hash = 'x' ORDER BY created_at"),
        AuditQuery("selectDuplicateBlockHashes",
            "SELECT content_hash, COUNT(*) AS cnt FROM blocks WHERE content_hash IS NOT NULL GROUP BY content_hash HAVING COUNT(*) > 1 ORDER BY cnt DESC LIMIT 10"),

        // ── pages ────────────────────────────────────────────────────────────────────────────
        AuditQuery("selectPageByUuid",
            "SELECT * FROM pages WHERE uuid = 'x'"),
        AuditQuery("selectPageByName",
            "SELECT * FROM pages WHERE name = 'x' LIMIT 1"),
        AuditQuery("existsPageByUuid",
            "SELECT COUNT(*) FROM pages WHERE uuid = 'x'"),
        AuditQuery("existsPageByName",
            "SELECT COUNT(*) FROM pages WHERE name = 'x'"),
        AuditQuery("selectAllPagesPaginated",
            "SELECT * FROM pages ORDER BY name LIMIT 10 OFFSET 0"),
        AuditQuery("selectUnloadedPagesPaginated",
            "SELECT * FROM pages WHERE is_content_loaded = 0 ORDER BY uuid LIMIT 10 OFFSET 0"),
        AuditQuery("countUnloadedPages",
            "SELECT COUNT(*) FROM pages WHERE is_content_loaded = 0"),
        AuditQuery("selectPageNameEntries",
            "SELECT name, is_journal FROM pages"),
        AuditQuery("selectFavoritePages",
            "SELECT * FROM pages WHERE is_favorite = 1 ORDER BY name"),
        AuditQuery("selectPagesByNames",
            "SELECT * FROM pages WHERE name IN ('x')"),
        AuditQuery("selectJournalPagesByDates",
            "SELECT * FROM pages WHERE is_journal = 1 AND journal_date IN ('2024-01-01')"),
        AuditQuery("selectPagesByNamespace",
            "SELECT * FROM pages WHERE namespace = 'x' ORDER BY name LIMIT 10 OFFSET 0"),
        AuditQuery("selectPagesByNamespaceUnpaginated",
            "SELECT * FROM pages WHERE namespace = 'x' ORDER BY name"),
        AuditQuery("countPagesByNamespace",
            "SELECT COUNT(*) FROM pages WHERE namespace = 'x'"),
        AuditQuery("selectRecentlyUpdatedPages",
            "SELECT * FROM pages ORDER BY updated_at DESC LIMIT 10"),
        AuditQuery("selectRecentlyCreatedPages",
            "SELECT * FROM pages ORDER BY created_at DESC LIMIT 10"),
        AuditQuery("countPages",
            "SELECT COUNT(*) FROM pages"),
        AuditQuery("selectJournalPages",
            "SELECT * FROM pages WHERE is_journal = 1 AND journal_date IS NOT NULL ORDER BY journal_date DESC LIMIT 10 OFFSET 0"),
        AuditQuery("selectJournalPageByDate",
            "SELECT * FROM pages WHERE is_journal = 1 AND journal_date = '2024-01-01' LIMIT 1"),
        AuditQuery("selectPagesByNameLike",
            "SELECT * FROM pages WHERE name LIKE '%test%'"),
        AuditQuery("selectPagesByNameLikePaginated",
            "SELECT * FROM pages WHERE name LIKE '%test%' ORDER BY name LIMIT 10 OFFSET 0"),
        AuditQuery("selectPageBacklinkCount",
            "SELECT backlink_count FROM pages WHERE name = 'x'"),
        AuditQuery("selectBacklinkCountsForPages",
            "SELECT name AS page_name, backlink_count FROM pages WHERE uuid IN ('p0', 'p1')"),
        AuditQuery("selectNeighbourPageUuids",
            """SELECT DISTINCT to_b.page_uuid AS page_uuid
               FROM block_references br
               JOIN blocks from_b ON from_b.uuid = br.from_block_uuid
               JOIN blocks to_b   ON to_b.uuid   = br.to_block_uuid
               WHERE from_b.page_uuid = 'p0' AND to_b.page_uuid != 'p0'
               UNION
               SELECT DISTINCT from_b.page_uuid AS page_uuid
               FROM block_references br
               JOIN blocks from_b ON from_b.uuid = br.from_block_uuid
               JOIN blocks to_b   ON to_b.uuid   = br.to_block_uuid
               WHERE to_b.page_uuid = 'p0' AND from_b.page_uuid != 'p0'"""),

        // ── block_references ─────────────────────────────────────────────────────────────────
        AuditQuery("selectOutgoingReferences",
            "SELECT b.* FROM blocks b INNER JOIN block_references br ON b.uuid = br.to_block_uuid WHERE br.from_block_uuid = 'x'"),
        AuditQuery("selectIncomingReferences",
            "SELECT b.* FROM blocks b INNER JOIN block_references br ON b.uuid = br.from_block_uuid WHERE br.to_block_uuid = 'x'"),
        AuditQuery("selectOrphanedBlocks",
            "SELECT b.* FROM blocks b LEFT JOIN block_references br ON b.uuid = br.to_block_uuid WHERE br.id IS NULL"),
        AuditQuery("selectMostConnectedBlocks",
            "SELECT b.*, COUNT(br.id) AS reference_count FROM blocks b LEFT JOIN block_references br ON b.uuid = br.to_block_uuid OR b.uuid = br.from_block_uuid GROUP BY b.uuid ORDER BY reference_count DESC LIMIT 10"),
        AuditQuery("selectBlocksReferencing",
            "SELECT DISTINCT b.* FROM blocks b INNER JOIN block_references br ON b.uuid = br.from_block_uuid WHERE br.to_block_uuid = 'x'"),
        AuditQuery("selectAllBlocksWithPagePath",
            "SELECT b.uuid, b.parent_uuid, b.position, b.content, p.file_path FROM blocks b JOIN pages p ON b.page_uuid = p.uuid WHERE p.file_path IS NOT NULL"),

        // ── plugin_data ──────────────────────────────────────────────────────────────────────
        AuditQuery("selectPluginDataById",
            "SELECT * FROM plugin_data WHERE id = 1"),
        AuditQuery("selectPluginDataByPlugin",
            "SELECT * FROM plugin_data WHERE plugin_id = 'x' ORDER BY created_at"),
        AuditQuery("selectPluginDataByEntity",
            "SELECT * FROM plugin_data WHERE entity_type = 'x' AND entity_uuid = 'x' ORDER BY key"),
        AuditQuery("selectPluginDataByKey",
            "SELECT * FROM plugin_data WHERE plugin_id = 'x' AND key = 'x' ORDER BY entity_type, entity_uuid"),
        AuditQuery("selectPluginDataByPluginAndEntity",
            "SELECT * FROM plugin_data WHERE plugin_id = 'x' AND entity_type = 'x' AND entity_uuid = 'x' ORDER BY key"),
        AuditQuery("countPluginDataByPlugin",
            "SELECT COUNT(*) FROM plugin_data WHERE plugin_id = 'x'"),
        AuditQuery("countPluginDataByEntity",
            "SELECT COUNT(*) FROM plugin_data WHERE entity_type = 'x' AND entity_uuid = 'x'"),
        AuditQuery("existsPluginData",
            "SELECT COUNT(*) FROM plugin_data WHERE plugin_id = 'x' AND entity_type = 'x' AND entity_uuid = 'x' AND key = 'x'"),

        // ── FTS5 virtual tables ──────────────────────────────────────────────────────────────
        AuditQuery("searchBlocksByContentFts",
            """SELECT b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version,
               highlight(blocks_fts, 0, '<em>', '</em>') AS highlight
               FROM blocks_fts bm JOIN blocks b ON b.id = bm.rowid
               WHERE blocks_fts MATCH 'test*' ORDER BY bm25(blocks_fts) LIMIT 10 OFFSET 0"""),
        AuditQuery("searchBlocksByContentFtsInPage",
            """SELECT b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version,
               highlight(blocks_fts, 0, '<em>', '</em>') AS highlight
               FROM blocks_fts bm JOIN blocks b ON b.id = bm.rowid
               WHERE blocks_fts MATCH 'test*' AND b.page_uuid = 'x'
               ORDER BY bm25(blocks_fts) LIMIT 10 OFFSET 0"""),
        AuditQuery("searchBlocksCountFts",
            "SELECT COUNT(*) AS result_count FROM blocks_fts bm WHERE blocks_fts MATCH 'test*'"),
        AuditQuery("searchPagesByNameFts",
            """SELECT p.uuid, p.name, p.namespace, p.file_path, p.created_at, p.updated_at, p.properties, p.version, p.is_favorite, p.is_journal, p.journal_date, p.is_content_loaded,
               highlight(pages_fts, 0, '<em>', '</em>') AS highlight
               FROM pages_fts pf JOIN pages p ON p.rowid = pf.rowid
               WHERE pages_fts MATCH 'test*' ORDER BY bm25(pages_fts) LIMIT 10"""),
        AuditQuery("searchPagesByNameFtsInDateRange",
            """SELECT p.uuid, p.name, p.namespace, p.file_path, p.created_at, p.updated_at,
               p.properties, p.version, p.is_favorite, p.is_journal, p.journal_date, p.is_content_loaded,
               highlight(pages_fts, 0, '<em>', '</em>') AS highlight, bm25(pages_fts) AS bm25_score
               FROM pages_fts pf JOIN pages p ON p.rowid = pf.rowid
               WHERE pages_fts MATCH 'test*' AND p.updated_at >= 0 AND p.updated_at <= 999999999
               ORDER BY bm25(pages_fts) LIMIT 10"""),
        AuditQuery("searchBlocksByContentFtsInDateRange",
            """SELECT b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level,
               b.position, b.created_at, b.updated_at, b.properties, b.version,
               highlight(blocks_fts, 0, '<em>', '</em>') AS highlight, bm25(blocks_fts) AS bm25_score
               FROM blocks_fts bm JOIN blocks b ON b.id = bm.rowid
               WHERE blocks_fts MATCH 'test*' AND b.updated_at >= 0 AND b.updated_at <= 999999999
               ORDER BY bm25(blocks_fts) LIMIT 10 OFFSET 0"""),

        // ── wikilink LIKE queries ────────────────────────────────────────────────────────────
        AuditQuery("countBlocksWithWikilink",
            "SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[TestPage]]%'"),
        AuditQuery("selectBlocksWithWikilink",
            "SELECT * FROM blocks WHERE content LIKE '%[[TestPage]]%' ORDER BY page_uuid, position"),
        AuditQuery("countLinkedReferencesForPage",
            "SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[TestPage]]%'"),

        // ── metadata ─────────────────────────────────────────────────────────────────────────
        // NOTE: perf_histogram_buckets and debug_flags are in TelemetryDatabase (Telemetry.sq),
        // not SteleDatabase — they are audited separately and intentionally absent here.
        AuditQuery("selectMetadata",
            "SELECT value FROM metadata WHERE key = 'x'"),
        AuditQuery("selectAllMetadata",
            "SELECT key, value FROM metadata"),

        // ── operations ───────────────────────────────────────────────────────────────────────
        AuditQuery("selectOperationsBySessionDesc",
            "SELECT * FROM operations WHERE session_id = 'x' ORDER BY seq DESC LIMIT 10"),
        AuditQuery("selectOperationsByPageUuid",
            "SELECT * FROM operations WHERE page_uuid = 'x' ORDER BY seq ASC"),
        AuditQuery("selectOperationsSince",
            "SELECT * FROM operations WHERE session_id = 'x' AND seq > 0 ORDER BY seq ASC"),
        AuditQuery("countOperationPayloadSize",
            "SELECT SUM(LENGTH(payload)) FROM operations WHERE session_id = 'x'"),

        // ── logical_clock ────────────────────────────────────────────────────────────────────
        AuditQuery("selectLogicalClock",
            "SELECT seq FROM logical_clock WHERE session_id = 'x'"),

        // ── migration_changelog ──────────────────────────────────────────────────────────────
        AuditQuery("selectAppliedMigrations",
            "SELECT id, checksum, status FROM migration_changelog WHERE graph_id = 'x' AND status = 'APPLIED' ORDER BY execution_order"),
        AuditQuery("selectMigrationById",
            "SELECT * FROM migration_changelog WHERE id = 'x' AND graph_id = 'x'"),
        AuditQuery("selectRunningMigrations",
            "SELECT * FROM migration_changelog WHERE graph_id = 'x' AND status = 'RUNNING'"),
        AuditQuery("selectAllMigrationsForGraph",
            "SELECT * FROM migration_changelog WHERE graph_id = 'x' ORDER BY execution_order"),

        // NOTE: spans and query_stats are in TelemetryDatabase (Telemetry.sq), not SteleDatabase.
        // Their query-plan audit lives with the telemetry test suite.

        // ── git_config ────────────────────────────────────────────────────────────────────────
        AuditQuery("selectGitConfig",
            "SELECT * FROM git_config WHERE graph_id = 'x'"),

        // ── image_annotations ─────────────────────────────────────────────────────────────────
        AuditQuery("selectAllImageAnnotations",
            "SELECT * FROM image_annotations ORDER BY imported_at_ms DESC"),
        AuditQuery("selectImageAnnotationByUuid",
            "SELECT * FROM image_annotations WHERE uuid = 'ia0'"),
        AuditQuery("selectImageAnnotationsByPage",
            "SELECT * FROM image_annotations WHERE page_uuid = 'p0' ORDER BY imported_at_ms DESC"),
        AuditQuery("selectImageAnnotationsByTag",
            "SELECT * FROM image_annotations WHERE tags LIKE '%\"test\"%' ORDER BY imported_at_ms DESC"),

        // ── measurement_annotations ───────────────────────────────────────────────────────────
        AuditQuery("selectMeasurementsForImage",
            "SELECT * FROM measurement_annotations WHERE image_uuid = 'ia0' ORDER BY rowid"),

        // ── asset_index ────────────────────────────────────────────────────────────────────────
        AuditQuery("selectAssetByUuid",
            "SELECT * FROM asset_index WHERE uuid = 'a0'"),
        AuditQuery("selectAssets",
            "SELECT * FROM asset_index ORDER BY imported_at_ms DESC LIMIT 10 OFFSET 0"),
        AuditQuery("selectAssetsByMediaType",
            "SELECT * FROM asset_index WHERE media_type = 'image/png' ORDER BY imported_at_ms DESC LIMIT 10 OFFSET 0"),
        AuditQuery("searchAssets",
            "SELECT * FROM asset_index WHERE (file_path LIKE '%test%' OR tags LIKE '%test%' OR auto_labels LIKE '%test%' OR ocr_text LIKE '%test%') ORDER BY imported_at_ms DESC LIMIT 10 OFFSET 0"),
        AuditQuery("selectUnprocessedAssets",
            "SELECT * FROM asset_index WHERE ml_processed = 0 AND ml_failed = 0 ORDER BY imported_at_ms ASC LIMIT 10 OFFSET 0"),
        AuditQuery("countUnprocessedAssets",
            "SELECT COUNT(*) FROM asset_index WHERE ml_processed = 0 AND ml_failed = 0"),
        AuditQuery("countAssets",
            "SELECT COUNT(*) FROM asset_index"),
        AuditQuery("selectAssetsByDateKeyset",
            "SELECT * FROM asset_index WHERE imported_at_ms < COALESCE(9999999999999, 9999999999999) ORDER BY imported_at_ms DESC, uuid DESC LIMIT 10"),
        AuditQuery("selectAssetsByMediaTypeByDateKeyset",
            "SELECT * FROM asset_index WHERE media_type = 'image/png' AND imported_at_ms < COALESCE(9999999999999, 9999999999999) ORDER BY imported_at_ms DESC, uuid DESC LIMIT 10"),
        AuditQuery("selectAssetsBySearchByDateKeyset",
            "SELECT * FROM asset_index WHERE (file_path LIKE '%test%' OR tags LIKE '%test%' OR auto_labels LIKE '%test%' OR ocr_text LIKE '%test%') AND imported_at_ms < COALESCE(9999999999999, 9999999999999) ORDER BY imported_at_ms DESC, uuid DESC LIMIT 10"),
        AuditQuery("selectAssetsByNameKeyset",
            "SELECT * FROM asset_index WHERE (file_path > COALESCE('', '') OR (file_path = '' AND uuid > COALESCE('', ''))) ORDER BY file_path ASC, uuid ASC LIMIT 10"),
        AuditQuery("selectAssetsByMediaTypeByNameKeyset",
            "SELECT * FROM asset_index WHERE media_type = 'image/png' AND (file_path > COALESCE('', '') OR (file_path = '' AND uuid > COALESCE('', ''))) ORDER BY file_path ASC, uuid ASC LIMIT 10"),
        AuditQuery("selectAssetsBySizeKeyset",
            "SELECT * FROM asset_index WHERE (size_bytes < COALESCE(9223372036854775807, 9223372036854775807) OR (size_bytes = 9223372036854775807 AND uuid > COALESCE('', ''))) ORDER BY size_bytes DESC, uuid ASC LIMIT 10"),
        AuditQuery("selectAssetsByMediaTypeBySizeKeyset",
            "SELECT * FROM asset_index WHERE media_type = 'image/png' AND (size_bytes < COALESCE(9223372036854775807, 9223372036854775807) OR (size_bytes = 9223372036854775807 AND uuid > COALESCE('', ''))) ORDER BY size_bytes DESC, uuid ASC LIMIT 10"),
        AuditQuery("selectOrphanedAssets",
            "SELECT * FROM asset_index WHERE is_orphan = 1 AND imported_at_ms < COALESCE(9999999999999, 9999999999999) ORDER BY imported_at_ms DESC, uuid DESC LIMIT 10"),
        AuditQuery("countOrphanedAssets",
            "SELECT COUNT(*) FROM asset_index WHERE is_orphan = 1"),
        AuditQuery("selectAllTagsJson",
            "SELECT tags FROM asset_index WHERE tags IS NOT NULL AND tags != '' AND tags != '[]'"),

        // ── page_visits ────────────────────────────────────────────────────────────────────────
        AuditQuery("selectPageVisitsByUuids",
            "SELECT page_uuid, last_visited_at, visit_count FROM page_visits WHERE page_uuid IN ('p0')"),
        AuditQuery("selectPageVisitByUuid",
            "SELECT page_uuid, last_visited_at, visit_count FROM page_visits WHERE page_uuid = 'p0'"),

        // ── wikilink_references ────────────────────────────────────────────────────────────────
        AuditQuery("selectWikilinkPageNamesForBlock",
            "SELECT page_name FROM wikilink_references WHERE block_uuid = 'b0'"),
        AuditQuery("selectWikilinkPageNamesForBlocks",
            "SELECT DISTINCT page_name FROM wikilink_references WHERE block_uuid IN ('b0')"),
        AuditQuery("selectWikilinkPageNamesForPage",
            "SELECT DISTINCT page_name FROM wikilink_references WHERE block_uuid IN (SELECT uuid FROM blocks WHERE page_uuid = 'p0')"),

        // ── pending_asset_moves ────────────────────────────────────────────────────────────────
        AuditQuery("selectAllPendingMoves",
            "SELECT * FROM pending_asset_moves"),

        // ── built-in rowid helper — no FROM clause, no scan possible ──────────────────────
        AuditQuery("selectLastInsertRowId",
            "SELECT last_insert_rowid()"),
    )

    @Test
    fun `no unexpected full table scans`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val sqliteDriver = driver as PooledJdbcSqliteDriver
        val conn = sqliteDriver.getConnection()
        val failures = mutableListOf<String>()
        try {
            conn.createStatement().use { seed ->
                // ── pages: 1000 regular (varying timestamps), 200 unloaded, 50 journal, 10 favorite ──
                repeat(1000) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO pages(uuid,name,namespace,file_path,created_at,updated_at," +
                        "properties,version,is_favorite,is_journal,journal_date,is_content_loaded,backlink_count) " +
                        "VALUES('p$i','Page $i',NULL,NULL,${i * 1000L},${i * 1000L + 500L},NULL,0,0,0,NULL,1,0)"
                    )
                }
                repeat(200) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO pages(uuid,name,namespace,file_path,created_at,updated_at," +
                        "properties,version,is_favorite,is_journal,journal_date,is_content_loaded,backlink_count) " +
                        "VALUES('u$i','Unloaded $i',NULL,NULL,0,0,NULL,0,0,0,NULL,0,0)"
                    )
                }
                // Journal pages — populate idx_pages_journal for realistic statistics
                repeat(50) { i ->
                    val date = "2024-${(i % 12 + 1).toString().padStart(2, '0')}-01"
                    seed.execute(
                        "INSERT OR IGNORE INTO pages(uuid,name,namespace,file_path,created_at,updated_at," +
                        "properties,version,is_favorite,is_journal,journal_date,is_content_loaded,backlink_count) " +
                        "VALUES('j$i','Journal $date',NULL,NULL,${i * 86400000L},${i * 86400000L},NULL,0,0,1,'$date',1,0)"
                    )
                }
                // Favorite pages — populate idx_pages_favorite for realistic statistics
                repeat(10) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO pages(uuid,name,namespace,file_path,created_at,updated_at," +
                        "properties,version,is_favorite,is_journal,journal_date,is_content_loaded,backlink_count) " +
                        "VALUES('f$i','Favorite $i',NULL,NULL,0,0,NULL,0,1,0,NULL,1,0)"
                    )
                }
                // ── blocks ────────────────────────────────────────────────────────────────────
                repeat(5000) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO blocks(uuid,page_uuid,parent_uuid,left_uuid,content," +
                        "level,position,created_at,updated_at,properties,version,content_hash,block_type) " +
                        "VALUES('b$i','p${i % 1000}',NULL,NULL,'content $i',0,${i % 50},${i * 100L},${i * 100L + 50L},NULL,0,'hash$i','bullet')"
                    )
                }
                // ── block_references — needed for selectNeighbourPageUuids ──────────────────
                repeat(100) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO block_references(from_block_uuid,to_block_uuid,created_at) " +
                        "VALUES('b${i % 5000}','b${(i + 1) % 5000}',0)"
                    )
                }
                // ── image_annotations ─────────────────────────────────────────────────────────
                repeat(20) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO image_annotations(" +
                        "uuid,block_uuid,page_uuid,graph_path,file_path,source,imported_at_ms," +
                        "calibration_method,pixels_per_meter,calibration_confidence_pct,unit,tags) " +
                        "VALUES('ia$i','b${i % 5000}','p${i % 1000}','/graph','/img$i.jpg'," +
                        "'FILE',${i * 1000L},'NONE',0.0,0,'METERS','[]')"
                    )
                }
                // ── asset_index ───────────────────────────────────────────────────────────────
                repeat(20) { i ->
                    seed.execute(
                        "INSERT OR IGNORE INTO asset_index(" +
                        "uuid,file_path,relative_path,media_type,subfolder,tags,auto_labels," +
                        "page_uuids,size_bytes,imported_at_ms,ml_processed,ml_failed,ml_tags_source) " +
                        "VALUES('a$i','/assets/file$i.png','assets/file$i.png','image/png','files'," +
                        "'[]','[]','[]',1024,${i * 1000L},0,0,'NONE')"
                    )
                }
                // ── git_config — needed for selectGitConfig ───────────────────────────────────
                seed.execute(
                    "INSERT OR IGNORE INTO git_config(graph_id,repo_root) VALUES('g1','/repo')"
                )
                seed.execute("ANALYZE")
            }
            conn.createStatement().use { stmt ->
                for (q in QUERIES) {
                    val rs = stmt.executeQuery("EXPLAIN QUERY PLAN ${q.sql}")
                    val planLines = buildList {
                        while (rs.next()) add(rs.getString("detail") ?: "")
                    }
                    rs.close()

                    if (q.name in ALLOWLIST) continue

                    val heapScans = planLines.filter { line ->
                        line.startsWith("SCAN ") &&
                            !line.contains("USING") &&
                            !line.contains("VIRTUAL TABLE")
                    }
                    if (heapScans.isNotEmpty()) {
                        failures += buildString {
                            appendLine("  ${q.name}")
                            appendLine("    plan:  $planLines")
                            append("    sql:   ${q.sql.trim().replace(Regex("\\s+"), " ")}")
                        }
                    }
                }
            }
        } finally {
            sqliteDriver.closeConnection(conn)
            driver.close()
        }
        if (failures.isNotEmpty()) {
            fail(buildString {
                appendLine("${failures.size} quer(y/ies) do unexpected full table scans — add an index or add to ALLOWLIST:")
                failures.forEach { appendLine(it) }
            })
        }
    }

    /**
     * Empirically proves the statistics-poisoning root cause behind the SCAN blocks regression.
     *
     * Production failure chain (old code had no unconditional startup ANALYZE):
     *   1. Fresh install (or upgrade where analyze_blocks migration was new):
     *      `analyze_blocks` migration runs `ANALYZE blocks` on an EMPTY table.
     *      SQLite writes NOTHING to sqlite_stat1 — ANALYZE on an empty table produces no rows.
     *   2. User imports their graph: blocks grows to 50 000+ rows.
     *   3. App restarts: MigrationRunner skips `analyze_blocks` (already applied/hash-matched).
     *      No ANALYZE runs. No PRAGMA optimize (old code didn't call it either).
     *   4. sqlite_stat1 still has no entry for blocks → query planner uses default heuristics →
     *      on Android SQLite the planner chooses a full heap SCAN (~1.5 s/query).
     *
     * This test uses raw JDBC (no DriverFactory, no migrations, no unconditional startup ANALYZE)
     * to reproduce the exact pre-fix production state with hard assertions at every step.
     *
     * Hard assertions (deterministic across all SQLite versions):
     *   - sqlite_stat1 has NO entry for blocks after ANALYZE on empty table.
     *   - sqlite_stat1 still has NO entry for blocks after data is loaded (no ANALYZE ran).
     *   - sqlite_stat1 gains correct entries only after explicit ANALYZE on the loaded table.
     *   - EXPLAIN QUERY PLAN uses the composite index after ANALYZE.
     *
     * Soft observation (SQLite-version-dependent):
     *   - Whether the absent statistics cause an actual SCAN depends on the SQLite version's
     *     default heuristics. The JVM SQLite JDBC may use the index anyway; Android SQLite ~3.39
     *     chose SCAN. The statistics state is the deterministic root cause regardless.
     */
    @Test
    fun `analyze_blocks migration poisons statistics on fresh install - no stats means planner has no baseline`() {
        // Use raw JDBC — bypasses DriverFactory's unconditional startup ANALYZE so we can
        // reproduce the exact pre-fix production state at each step.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { ddl ->
                ddl.execute("""
                    CREATE TABLE blocks (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid        TEXT NOT NULL UNIQUE,
                        page_uuid   TEXT NOT NULL,
                        parent_uuid TEXT,
                        position    INTEGER NOT NULL DEFAULT 0,
                        content     TEXT
                    )
                """.trimIndent())
                // The composite indexes that covering_indexes_page_blocks adds.
                ddl.execute("CREATE INDEX idx_blocks_page_position   ON blocks(page_uuid, position)")
                ddl.execute("CREATE INDEX idx_blocks_parent_position ON blocks(parent_uuid, position)")
            }

            fun statRows(): Map<String, String> = conn.createStatement().use { s ->
                try {
                    val rs = s.executeQuery("SELECT idx, stat FROM sqlite_stat1 WHERE tbl = 'blocks'")
                    buildMap { while (rs.next()) put(rs.getString("idx") ?: "", rs.getString("stat") ?: "") }
                        .also { rs.close() }
                } catch (_: Exception) { emptyMap() }
            }

            fun planLines(sql: String): List<String> = conn.createStatement().use { s ->
                val rs = s.executeQuery("EXPLAIN QUERY PLAN $sql")
                buildList { while (rs.next()) add(rs.getString("detail") ?: "") }.also { rs.close() }
            }

            // ── Step 1: analyze_blocks migration — ANALYZE on empty table ─────────────────────
            // This is what runs on a fresh install or when the migration is new.
            conn.createStatement().use { it.execute("ANALYZE blocks") }

            val statsAfterEmptyAnalyze = statRows()
            // KEY FACT: ANALYZE on an empty table writes NOTHING to sqlite_stat1.
            // There is no "0-row" entry — the table is simply absent from sqlite_stat1.
            assertTrue(statsAfterEmptyAnalyze.isEmpty(),
                "ANALYZE on an empty table must write nothing to sqlite_stat1. " +
                "Got: $statsAfterEmptyAnalyze — if this has rows, this SQLite version changed " +
                "behaviour and the root-cause analysis needs updating.")

            // ── Step 2: Graph import — 50 000 blocks, no ANALYZE ─────────────────────────────
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO blocks(uuid,page_uuid,parent_uuid,position,content) VALUES(?,?,NULL,?,?)"
            ).use { ps ->
                repeat(50_000) { i ->
                    ps.setString(1, "bb$i"); ps.setString(2, "pp${i % 1000}")
                    ps.setInt(3, i % 50);   ps.setString(4, "content $i")
                    ps.addBatch()
                    if (i % 1000 == 999) ps.executeBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
            conn.autoCommit = true

            // ── Step 3: Subsequent startup — old code only ran migrations (already applied) ───
            // Neither ANALYZE nor PRAGMA optimize was called by the old startup path.
            // sqlite_stat1 is still empty for blocks.
            val statsAfterDataLoad = statRows()
            assertTrue(statsAfterDataLoad.isEmpty(),
                "After graph import with no ANALYZE, sqlite_stat1 must still have no entry for " +
                "blocks — this is the exact pre-fix state: 50 000 rows, zero statistics. " +
                "Got: $statsAfterDataLoad")

            // ── Soft observation: EXPLAIN QUERY PLAN without any statistics ───────────────────
            // The planner's choice depends on the SQLite version's default cardinality heuristics.
            // Android SQLite ~3.39 chose SCAN blocks (~1.5 s). JVM SQLite may use the index.
            val criticalSql = "SELECT * FROM blocks WHERE page_uuid = 'pp0' ORDER BY position"
            val planWithNoStats = planLines(criticalSql)
            val isScan = planWithNoStats.any { it.startsWith("SCAN blocks") && !it.contains("USING") }
            println(
                "[BlocksScanRegression] Plan WITHOUT statistics on this SQLite version " +
                "(${if (isScan) "SCAN — regression reproduced" else "index used — version avoids SCAN"}): $planWithNoStats"
            )

            // ── Step 4: Fix — ANALYZE blocks after data exists (unconditional startup ANALYZE) ─
            conn.createStatement().use { it.execute("ANALYZE blocks") }

            val statsAfterFix = statRows()
            val estimatedRows = statsAfterFix.values
                .mapNotNull { it.trim().split(" ").firstOrNull()?.toIntOrNull() }
            assertTrue(estimatedRows.any { it > 10_000 },
                "After ANALYZE with 50 000 rows, sqlite_stat1 must show an estimated row count " +
                "> 10 000 for at least one blocks index. Got: $statsAfterFix")

            // ── Hard assertion: fix produces the correct query plan ───────────────────────────
            val planAfterFix = planLines(criticalSql)
            assertTrue(planAfterFix.any { it.contains("USING") },
                "After ANALYZE with real data, the page_uuid lookup must use the composite index. " +
                "Heap scan still detected after fix. Plan: $planAfterFix")
        } finally {
            conn.close()
        }
    }

    /**
     * Structural check: [MigrationRunner.all] must contain an entry named "analyze_blocks"
     * whose statement is "ANALYZE blocks". Removing or renaming this migration would leave
     * existing production databases without statistics after upgrade, causing SCAN blocks
     * on large graphs. The [large blocks table uses composite index after ANALYZE] test
     * validates the behaviour; this test validates the migration is registered.
     */
    @Test
    fun `MigrationRunner all contains analyze_blocks migration for blocks table`() {
        val analyzeEntry = MigrationRunner.all.find { it.name == "analyze_blocks" }
        if (analyzeEntry == null) {
            fail(
                "MigrationRunner.all does not contain an 'analyze_blocks' migration. " +
                "Without it, existing production databases lack SQLite statistics after upgrade " +
                "and the query planner falls back to full heap scans on the blocks table (~9 s/query)."
            )
        }
        val hasAnalyzeStatement = analyzeEntry.statements.any { it.trim().uppercase().startsWith("ANALYZE") }
        if (!hasAnalyzeStatement) {
            fail(
                "The analyze_blocks migration exists but contains no ANALYZE statement: ${analyzeEntry.statements}. " +
                "Add 'ANALYZE blocks' (or 'ANALYZE') to its statements list."
            )
        }
    }

    /**
     * Structural check: every named SELECT query in SteleDatabase.sq must have a corresponding
     * [AuditQuery] entry in [QUERIES], and every [QUERIES] entry must still exist in the schema.
     *
     * This makes it impossible to add a new SELECT query without also auditing its query plan —
     * the test fails at CI time if you forget. Runs only when the `stelekit.sq.file` system
     * property is set (i.e. via Gradle — not in IDE run configurations that omit it; those are
     * covered by the main scan test above).
     */
    @Test
    fun `all SELECT queries in SteleDatabase sq are covered by this audit`() {
        val sqFilePath = System.getProperty("stelekit.sq.file") ?: return
        val sqContent = File(sqFilePath).readText()

        // Named query format in SQLDelight: "queryName:\nSELECT ..."
        // The regex matches a word followed by ':' at line start, then SELECT (or WITH RECURSIVE
        // CTE queries that are read-only) on the next line.
        val selectNamesInSq: Set<String> = Regex(
            """^(\w+):\s*\n\s*(?:SELECT|WITH\s+RECURSIVE)""",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        ).findAll(sqContent).map { it.groupValues[1] }.toSet()

        val queriedNames: Set<String> = QUERIES.map { it.name }.toSet()

        val uncovered = selectNamesInSq - queriedNames
        val orphaned = queriedNames - selectNamesInSq
        val staleAllowlist = ALLOWLIST - queriedNames

        val failures = mutableListOf<String>()
        if (uncovered.isNotEmpty()) failures += buildString {
            appendLine("${uncovered.size} SELECT quer(y/ies) in SteleDatabase.sq not covered by QUERIES:")
            uncovered.sorted().forEach { appendLine("  $it") }
            append(
                "Add an AuditQuery entry to QUERIES (and to ALLOWLIST if a heap scan is " +
                "expected/unavoidable — document why in the ALLOWLIST comment)."
            )
        }
        if (orphaned.isNotEmpty()) failures += buildString {
            appendLine("${orphaned.size} entr(y/ies) in QUERIES have no matching SELECT in SteleDatabase.sq (stale):")
            orphaned.sorted().forEach { appendLine("  $it") }
            append("Remove stale entries from QUERIES in QueryPlanAuditTest.kt.")
        }
        if (staleAllowlist.isNotEmpty()) failures += buildString {
            appendLine("${staleAllowlist.size} ALLOWLIST entr(y/ies) not present in QUERIES:")
            staleAllowlist.sorted().forEach { appendLine("  $it") }
            append("Either add an AuditQuery entry to QUERIES or remove the name from ALLOWLIST.")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }
}
