package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for SQLITE_BUSY_SNAPSHOT on the JVM driver.
 *
 * Background: SQLite WAL mode combined with JDBC's default `BEGIN DEFERRED` transaction mode
 * creates a snapshot upgrade race. `BEGIN DEFERRED` acquires only a read lock at transaction
 * start and attempts to upgrade to a write lock on the first write. If another connection
 * commits between the read and the write, SQLite throws `SQLITE_BUSY_SNAPSHOT`. Crucially,
 * `busy_timeout` does NOT apply to snapshot upgrade failures — the error is immediate and
 * permanent for that transaction.
 *
 * The fix is `transaction_mode=IMMEDIATE`, which forces `BEGIN IMMEDIATE` and acquires the
 * write lock upfront, eliminating the race entirely.
 *
 * These tests assert the critical properties of [buildMainDbConnectionProps] so that a
 * future refactor cannot silently lose the IMMEDIATE mode (which would require concurrent
 * writes under a 8-connection pool to reproduce).
 */
class JvmDriverConnectionPropsTest {

    @Test
    fun `main db uses BEGIN IMMEDIATE to prevent SQLITE_BUSY_SNAPSHOT`() {
        val props = buildMainDbConnectionProps()
        assertEquals(
            "IMMEDIATE",
            props.getProperty("transaction_mode"),
            "transaction_mode must be IMMEDIATE. BEGIN DEFERRED (JDBC default) can throw " +
            "SQLITE_BUSY_SNAPSHOT when upgrading from read to write lock in WAL mode — " +
            "busy_timeout does not apply to this error class.",
        )
    }

    @Test
    fun `main db uses WAL journal mode`() {
        val props = buildMainDbConnectionProps()
        assertEquals("WAL", props.getProperty("journal_mode"))
    }

    @Test
    fun `main db has a non-zero busy_timeout`() {
        val props = buildMainDbConnectionProps()
        val timeout = props.getProperty("busy_timeout")?.toLongOrNull() ?: 0L
        assert(timeout > 0) { "busy_timeout must be positive, got: $timeout" }
    }
}
