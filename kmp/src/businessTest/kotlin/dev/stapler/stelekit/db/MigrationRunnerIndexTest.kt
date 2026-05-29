package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests that verify the covering_indexes_page_blocks migration produces
 * indexes that the SQLite query planner actually uses for the three hot query paths.
 *
 * Uses an in-memory JDBC driver so that MigrationRunner.applyAll runs automatically
 * inside DriverFactory.createDriver. One driver is shared across all tests to avoid
 * replaying all migrations four times.
 */
class MigrationRunnerIndexTest {

    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun explainQueryPlan(sql: String, bindParam: String): String {
        val planRows = mutableListOf<String>()
        // .value accesses the result of the synchronous JDBC QueryResult immediately,
        // respecting the QueryResult API contract (an async driver would require .await()).
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN $sql",
            mapper = { cursor ->
                while (cursor.next().value) {
                    planRows.add(cursor.getString(3) ?: "")
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, bindParam) }
        ).value
        return planRows.joinToString("\n")
    }

    @Test
    fun `applyAll_should_createIdxBlocksPagePosition_and_eliminateFilesort_when_migrationRuns`() {
        val planText = explainQueryPlan(
            "SELECT uuid, page_uuid, position, content, level, parent_uuid FROM blocks WHERE page_uuid = ? ORDER BY position",
            "dummy-page-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_page_position"),
            "Expected idx_blocks_page_position in query plan but got:\n$planText"
        )
        assertFalse(
            planText.contains("USE TEMP B-TREE"),
            "Expected no filesort in query plan but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_createIdxBlocksParentPosition_for_asc_order_when_migrationRuns`() {
        val planText = explainQueryPlan(
            "SELECT uuid, position, content FROM blocks WHERE parent_uuid = ? ORDER BY position",
            "dummy-parent-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_parent_position"),
            "Expected idx_blocks_parent_position in query plan but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_createIdxBlocksParentPosition_for_desc_order_when_migrationRuns`() {
        // Verifies selectLastChild: SELECT uuid, position FROM blocks WHERE parent_uuid = ? ORDER BY position DESC LIMIT 1
        val planText = explainQueryPlan(
            "SELECT uuid, position, content FROM blocks WHERE parent_uuid = ? ORDER BY position DESC LIMIT 1",
            "dummy-parent-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_parent_position"),
            "Expected idx_blocks_parent_position for DESC scan (selectLastChild path) but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_createIdxBlocksPageHash_as_covering_index_when_migrationRuns`() {
        val planText = explainQueryPlan(
            "SELECT uuid, content_hash FROM blocks WHERE page_uuid = ?",
            "dummy-page-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_page_hash"),
            "Expected idx_blocks_page_hash in query plan but got:\n$planText"
        )
        // A covering index scan should not require a table fetch.
        assertFalse(
            planText.uppercase().contains("SCAN TABLE BLOCKS"),
            "Expected covering index scan (no table fetch) but got:\n$planText"
        )
    }
}
