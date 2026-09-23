package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SqliteStatementAnalyzer].
 *
 * Covers the tokenizer edge cases that matter for migration safety analysis:
 * - Standalone BEGIN/COMMIT/ROLLBACK (all variants) must be detected
 * - BEGIN inside a CREATE TRIGGER body must NOT be detected as transaction control
 * - SQL comments (-- and /* */) must be stripped before keyword extraction
 * - String literals must not contribute tokens to the keyword list
 */
class SqliteStatementAnalyzerTest {

    // ── isTransactionControl ─────────────────────────────────────────────────

    @Test fun `BEGIN is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("BEGIN"))

    @Test fun `BEGIN TRANSACTION is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("BEGIN TRANSACTION"))

    @Test fun `BEGIN IMMEDIATE is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("BEGIN IMMEDIATE"))

    @Test fun `BEGIN DEFERRED is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("BEGIN DEFERRED"))

    @Test fun `BEGIN EXCLUSIVE is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("BEGIN EXCLUSIVE"))

    @Test fun `COMMIT is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("COMMIT"))

    @Test fun `COMMIT TRANSACTION is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("COMMIT TRANSACTION"))

    @Test fun `ROLLBACK is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("ROLLBACK"))

    @Test fun `ROLLBACK TO SAVEPOINT is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("ROLLBACK TO SAVEPOINT foo"))

    @Test fun `lowercase begin is transaction control`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("begin"))

    @Test fun `leading whitespace before BEGIN is handled`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("  BEGIN  "))

    @Test fun `single-line comment before BEGIN is stripped`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("-- start a transaction\nBEGIN"))

    @Test fun `multi-line comment before BEGIN is stripped`() =
        assertTrue(SqliteStatementAnalyzer.isTransactionControl("/* wrap in txn */ BEGIN"))

    // ── NOT transaction control ───────────────────────────────────────────────

    @Test fun `CREATE TABLE is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("CREATE TABLE IF NOT EXISTS foo (id TEXT)"))

    @Test fun `DROP TABLE is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("DROP TABLE IF EXISTS foo"))

    @Test fun `CREATE INDEX is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("CREATE INDEX IF NOT EXISTS idx_foo ON foo(id)"))

    @Test fun `ALTER TABLE is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("ALTER TABLE pages ADD COLUMN section_id TEXT NOT NULL DEFAULT ''"))

    @Test fun `INSERT is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("INSERT OR IGNORE INTO pages_fts(rowid, name) SELECT rowid, name FROM pages"))

    @Test fun `ANALYZE is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("ANALYZE blocks"))

    @Test fun `PRAGMA is not transaction control`() =
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("PRAGMA foreign_keys=OFF"))

    @Test fun `BEGIN inside CREATE TRIGGER body is not transaction control`() {
        val trigger = """
            CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
                INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
            END
        """.trimIndent()
        assertFalse(SqliteStatementAnalyzer.isTransactionControl(trigger))
    }

    @Test fun `BEGIN inside CREATE TRIGGER with IF NOT EXISTS is not transaction control`() {
        val trigger = "CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END"
        assertFalse(SqliteStatementAnalyzer.isTransactionControl(trigger))
    }

    // ── leadingKeywords ───────────────────────────────────────────────────────

    @Test fun `leadingKeywords extracts CREATE TABLE IF NOT`() {
        assertEquals(
            listOf("CREATE", "TABLE", "IF", "NOT"),
            SqliteStatementAnalyzer.leadingKeywords("CREATE TABLE IF NOT EXISTS foo (id TEXT)", limit = 4)
        )
    }

    @Test fun `leadingKeywords extracts DROP TABLE IF EXISTS`() {
        assertEquals(
            listOf("DROP", "TABLE", "IF", "EXISTS"),
            SqliteStatementAnalyzer.leadingKeywords("DROP TABLE IF EXISTS foo", limit = 4)
        )
    }

    @Test fun `leadingKeywords stops at opening parenthesis`() {
        // Stops at '(' — table name 'foo' is extracted but column list is not
        assertEquals(
            listOf("CREATE", "TABLE", "FOO"),
            SqliteStatementAnalyzer.leadingKeywords("CREATE TABLE foo (id TEXT)", limit = 4)
        )
    }

    @Test fun `leadingKeywords includes tokens up to parenthesis before string`() {
        // '(' stops extraction before the string literal, so 'begin' inside the string is never seen
        assertEquals(
            listOf("INSERT", "INTO", "T", "VALUES"),
            SqliteStatementAnalyzer.leadingKeywords("INSERT INTO t VALUES ('begin')", limit = 4)
        )
    }

    @Test fun `string literal containing BEGIN keyword does not register as transaction control`() {
        // isTransactionControl must ignore keyword-looking content inside string values
        assertFalse(SqliteStatementAnalyzer.isTransactionControl("INSERT INTO t VALUES ('begin')"))
    }

    @Test fun `leadingKeywords strips leading comment`() {
        assertEquals(
            listOf("CREATE", "INDEX"),
            SqliteStatementAnalyzer.leadingKeywords("-- covering index\nCREATE INDEX IF NOT EXISTS", limit = 2)
        )
    }

    @Test fun `leadingKeywords strips multi-line comment`() {
        assertEquals(
            listOf("ANALYZE"),
            SqliteStatementAnalyzer.leadingKeywords("/* refresh stats */ ANALYZE blocks", limit = 1)
        )
    }

    @Test fun `leadingKeywords on empty string returns empty list`() {
        assertEquals(emptyList<String>(), SqliteStatementAnalyzer.leadingKeywords(""))
    }

    @Test fun `leadingKeywords on comment-only returns empty list`() {
        assertEquals(emptyList<String>(), SqliteStatementAnalyzer.leadingKeywords("-- nothing here"))
    }

    // ── stripComments ─────────────────────────────────────────────────────────

    @Test fun `stripComments removes single-line comment`() {
        assertEquals("\nCREATE TABLE foo", SqliteStatementAnalyzer.stripComments("-- comment\nCREATE TABLE foo"))
    }

    @Test fun `stripComments removes multi-line comment`() {
        assertEquals("CREATE  TABLE foo", SqliteStatementAnalyzer.stripComments("CREATE /* inline */ TABLE foo"))
    }

    @Test fun `stripComments preserves string literal containing comment syntax`() {
        val sql = "INSERT INTO t VALUES ('-- not a comment')"
        assertEquals(sql, SqliteStatementAnalyzer.stripComments(sql))
    }

    @Test fun `stripComments preserves escaped single quote in string literal`() {
        val sql = "INSERT INTO t VALUES ('it''s fine')"
        assertEquals(sql, SqliteStatementAnalyzer.stripComments(sql))
    }

    @Test fun `stripComments handles comment after string literal`() {
        assertEquals(
            "INSERT INTO t VALUES ('val') ",
            SqliteStatementAnalyzer.stripComments("INSERT INTO t VALUES ('val') -- trailing")
        )
    }
}
