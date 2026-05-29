package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests that verify the covering_indexes_page_blocks migration produces
 * indexes that the SQLite query planner actually uses for the three hot query paths.
 *
 * Uses an in-memory JDBC driver (same pattern as HistogramRegressionTest) so that
 * MigrationRunner.applyAll runs automatically inside DriverFactory.createDriver.
 */
class MigrationRunnerIndexTest {

    private fun explainQueryPlan(driver: app.cash.sqldelight.db.SqlDriver, sql: String, bindParam: String): String {
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
            parameters = 1,
            binders = { bindString(0, bindParam) }
        )
        return planRows.joinToString("\n")
    }

    @Test
    fun `applyAll_should_createIdxBlocksPagePosition_when_migrationRuns`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val planText = explainQueryPlan(
            driver,
            "SELECT uuid, page_uuid, position, content, level, parent_uuid FROM blocks WHERE page_uuid = ? ORDER BY position",
            "dummy-page-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_page_position"),
            "Expected idx_blocks_page_position in query plan but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_eliminateFilesort_when_pageBlocksQueried`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val planText = explainQueryPlan(
            driver,
            "SELECT uuid, page_uuid, position, content, level, parent_uuid FROM blocks WHERE page_uuid = ? ORDER BY position",
            "dummy-page-uuid"
        )
        assertFalse(
            planText.contains("USE TEMP B-TREE"),
            "Expected no filesort in query plan but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_createIdxBlocksParentPosition_when_migrationRuns`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val planText = explainQueryPlan(
            driver,
            "SELECT uuid, position, content FROM blocks WHERE parent_uuid = ? ORDER BY position",
            "dummy-parent-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_parent_position"),
            "Expected idx_blocks_parent_position in query plan but got:\n$planText"
        )
    }

    @Test
    fun `applyAll_should_createIdxBlocksPageHash_when_migrationRuns`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val planText = explainQueryPlan(
            driver,
            "SELECT uuid, content_hash FROM blocks WHERE page_uuid = ?",
            "dummy-page-uuid"
        )
        assertTrue(
            planText.contains("idx_blocks_page_hash"),
            "Expected idx_blocks_page_hash in query plan but got:\n$planText"
        )
    }
}
