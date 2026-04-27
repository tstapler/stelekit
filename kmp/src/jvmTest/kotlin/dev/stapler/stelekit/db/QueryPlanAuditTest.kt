package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.fail

/**
 * Static index-coverage audit: runs EXPLAIN QUERY PLAN on every SELECT from SteleDatabase.sq
 * against an in-memory SQLite instance and fails CI if any non-allowlisted query performs a
 * heap scan (SCAN <table> with no USING clause — i.e. no index).
 *
 * How to maintain:
 *   - Added a new SELECT to SteleDatabase.sq? → add a matching AuditQuery below.
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
        "selectAllPages", "selectAllPagesPaginated", "countPages",
        "selectAllMetadata", "selectAllDebugFlags",
        // content LIKE — no index on content; FTS handles production full-text search
        "selectBlocksWithContentLike", "selectBlocksWithContentLikePaginated",
        "countBlocksWithWikilink", "selectBlocksWithWikilink", "countLinkedReferencesForPage",
        // name LIKE — no index covers prefix-wildcard; FTS handles page-name search
        "selectPagesByNameLike", "selectPagesByNameLikePaginated",
        // Aggregate / analytics scans — intentionally full-table
        "selectDuplicateBlockHashes", "selectMostConnectedBlocks", "selectOrphanedBlocks",
        // pages columns without an index
        "selectUnloadedPages",        // is_content_loaded has no index
        "selectRecentlyUpdatedPages", // updated_at has no index on pages
        "selectRecentlyCreatedPages", // created_at has no index on pages
        "selectJournalPages",         // is_journal has no index
        "selectJournalPageByDate",    // journal_date has no index
        // JOIN where one relation must be fully scanned
        "selectAllBlocksWithPagePath", // blocks scanned, pages joined by uuid index
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
        AuditQuery("selectAllPages",
            "SELECT * FROM pages ORDER BY name"),
        AuditQuery("selectAllPagesPaginated",
            "SELECT * FROM pages ORDER BY name LIMIT 10 OFFSET 0"),
        AuditQuery("selectUnloadedPages",
            "SELECT * FROM pages WHERE is_content_loaded = 0"),
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
            "SELECT * FROM pages WHERE is_journal = 1 ORDER BY COALESCE(journal_date, name) DESC LIMIT 10 OFFSET 0"),
        AuditQuery("selectJournalPageByDate",
            "SELECT * FROM pages WHERE is_journal = 1 AND journal_date = '2024-01-01' LIMIT 1"),
        AuditQuery("selectPagesByNameLike",
            "SELECT * FROM pages WHERE name LIKE '%test%'"),
        AuditQuery("selectPagesByNameLikePaginated",
            "SELECT * FROM pages WHERE name LIKE '%test%' ORDER BY name LIMIT 10 OFFSET 0"),

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

        // ── wikilink LIKE queries ────────────────────────────────────────────────────────────
        AuditQuery("countBlocksWithWikilink",
            "SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[TestPage]]%'"),
        AuditQuery("selectBlocksWithWikilink",
            "SELECT * FROM blocks WHERE content LIKE '%[[TestPage]]%' ORDER BY page_uuid, position"),
        AuditQuery("countLinkedReferencesForPage",
            "SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[TestPage]]%'"),

        // ── perf_histogram_buckets ───────────────────────────────────────────────────────────
        AuditQuery("selectHistogramForOperation",
            "SELECT bucket_ms, count FROM perf_histogram_buckets WHERE operation_name = 'x' ORDER BY bucket_ms"),
        AuditQuery("selectAllHistogramOperations",
            "SELECT DISTINCT operation_name FROM perf_histogram_buckets ORDER BY operation_name"),

        // ── debug_flags ──────────────────────────────────────────────────────────────────────
        AuditQuery("selectDebugFlag",
            "SELECT value FROM debug_flags WHERE key = 'x'"),
        AuditQuery("selectAllDebugFlags",
            "SELECT key, value FROM debug_flags ORDER BY key"),

        // ── metadata ─────────────────────────────────────────────────────────────────────────
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

        // ── spans ────────────────────────────────────────────────────────────────────────────
        AuditQuery("selectRecentSpans",
            "SELECT * FROM spans ORDER BY start_epoch_ms DESC LIMIT 10"),

        // ── query_stats ────────────────────────────────────────────────────────────────────────
        AuditQuery("selectQueryStatsByVersion",
            "SELECT * FROM query_stats WHERE app_version = 'x' ORDER BY total_ms DESC"),
        AuditQuery("selectTopQueryStatsByTotalMs",
            "SELECT * FROM query_stats WHERE app_version = 'x' ORDER BY total_ms DESC LIMIT 10"),
        AuditQuery("selectTopQueryStatsByCalls",
            "SELECT * FROM query_stats WHERE app_version = 'x' ORDER BY calls DESC LIMIT 10"),
        AuditQuery("selectAllQueryStatVersions",
            "SELECT DISTINCT app_version FROM query_stats ORDER BY app_version DESC"),
    )

    @Test
    fun `no unexpected full table scans`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val sqliteDriver = driver as PooledJdbcSqliteDriver
        val conn = sqliteDriver.getConnection()
        val failures = mutableListOf<String>()
        try {
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
}
