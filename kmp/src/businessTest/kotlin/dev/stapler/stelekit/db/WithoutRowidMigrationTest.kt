package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies the wikilink_references_without_rowid migration:
 *
 * 1. Foreign key CASCADE: deleting a block removes its wikilink_references rows.
 * 2. Query plan: `SELECT * FROM wikilink_references WHERE block_uuid = ?` must NOT
 *    produce a bare `SCAN wikilink_references` (indicating it uses the PK B-tree).
 *
 * Uses an in-memory JDBC driver via DriverFactory so that MigrationRunner.applyAll
 * runs automatically inside createDriver, including the new WITHOUT ROWID migration.
 */
class WithoutRowidMigrationTest {

    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun execute(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun queryInt(sql: String): Int {
        var result = 0
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) result = cursor.getLong(0)?.toInt() ?: 0
                QueryResult.Value(Unit)
            },
            parameters = 0
        ).value
        return result
    }

    private fun explainQueryPlan(sql: String): String {
        val planRows = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN $sql",
            mapper = { cursor ->
                while (cursor.next().value) {
                    planRows.add(cursor.getString(3) ?: "")
                }
                QueryResult.Value(Unit)
            },
            parameters = 0
        ).value
        return planRows.joinToString("\n")
    }

    @Test
    fun `FK cascade deletes wikilink_references rows when block is deleted`() {
        // Seed: 1 page, 1 block, 2 wikilink_references rows
        execute(
            "INSERT INTO pages(uuid,name,namespace,file_path,created_at,updated_at," +
            "properties,version,is_favorite,is_journal,journal_date,is_content_loaded,backlink_count) " +
            "VALUES('page-1','TestPage',NULL,NULL,0,0,NULL,0,0,0,NULL,1,0)"
        )
        execute(
            "INSERT INTO blocks(uuid,page_uuid,parent_uuid,left_uuid,content," +
            "level,position,created_at,updated_at,properties,version,content_hash,block_type) " +
            "VALUES('block-1','page-1',NULL,NULL,'[[Foo]] and [[Bar]]',0,0,0,0,NULL,0,'h1','bullet')"
        )
        execute("INSERT OR IGNORE INTO wikilink_references(block_uuid,page_name) VALUES('block-1','Foo')")
        execute("INSERT OR IGNORE INTO wikilink_references(block_uuid,page_name) VALUES('block-1','Bar')")

        val countBefore = queryInt("SELECT COUNT(*) FROM wikilink_references WHERE block_uuid = 'block-1'")
        assertEquals(2, countBefore, "Expected 2 wikilink_references rows before deletion")

        // Enable FK enforcement for this connection (SQLite disables it by default)
        execute("PRAGMA foreign_keys=ON")
        execute("DELETE FROM blocks WHERE uuid = 'block-1'")

        val countAfter = queryInt("SELECT COUNT(*) FROM wikilink_references WHERE block_uuid = 'block-1'")
        assertEquals(
            0, countAfter,
            "Expected ON DELETE CASCADE to remove all wikilink_references rows when the block is deleted"
        )
    }

    @Test
    fun `block_uuid lookup on WITHOUT ROWID table does not produce bare heap scan`() {
        val plan = explainQueryPlan(
            "SELECT * FROM wikilink_references WHERE block_uuid = 'b0'"
        )
        // A bare "SCAN wikilink_references" (no USING clause) would mean a full table walk.
        // WITHOUT ROWID tables store all data in the PK B-tree, so a point lookup on the
        // leading PK column should appear as SEARCH or SCAN ... USING PRIMARY KEY.
        val heapScan = plan.lines().any { line ->
            line.trim().startsWith("SCAN wikilink_references") &&
                !line.contains("USING")
        }
        assertFalse(
            heapScan,
            "Expected no bare heap scan for block_uuid point lookup on wikilink_references. " +
            "Plan:\n$plan"
        )
    }
}
