package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for BUG-008 root cause C: [blocks_au] FTS5 trigger fired on ALL column
 * updates, not just content changes.
 *
 * Before the fix, every [splitBlock], [indentBlock], or [moveBlock] call issued O(N) FTS5
 * delete+insert pairs (one per sibling whose position shifted). The fix restricts the trigger
 * to `AFTER UPDATE OF content ON blocks` so structural-only mutations are FTS5-free.
 *
 * These tests query [sqlite_master] to verify the trigger DDL, because the performance
 * regression is not observable through FTS index correctness (a delete+insert of the same
 * content leaves the index unchanged). The DDL check catches a regression if someone reverts
 * [SteleDatabase.sq] or removes the [MigrationRunner] entry.
 */
class BlocksFtsTriggerTest {

    @Test
    fun blocks_au_trigger_is_restricted_to_content_column_updates() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")

        val triggerSql: String = driver.executeQuery(
            identifier = null,
            sql = "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='blocks_au'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0) ?: "")
            },
            parameters = 0
        ).await()

        assertTrue(
            triggerSql.contains("UPDATE OF content ON blocks", ignoreCase = true),
            "blocks_au trigger must be restricted to 'AFTER UPDATE OF content ON blocks' " +
            "to prevent O(N) FTS5 writes during structural-only mutations (splitBlock, indent, move).\n" +
            "Actual trigger SQL: $triggerSql"
        )
    }
}
