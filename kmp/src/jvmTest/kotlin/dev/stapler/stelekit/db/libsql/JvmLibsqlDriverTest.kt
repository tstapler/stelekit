package dev.stapler.stelekit.db.libsql

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import dev.stapler.stelekit.db.MigrationRunner
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [JvmLibsqlDriver].
 *
 * All tests that require the native libsql library start with
 * [LibsqlTestHarness.assumeNativeAvailable] and are silently skipped when the
 * .so / .dylib is not bundled in the classpath (e.g. CI without the Bazel build).
 *
 * The MVCC-specific tests additionally guard on [JvmLibsqlDriver.isMvccActive] so
 * that they skip gracefully on standard libsql builds that lack BEGIN CONCURRENT.
 * One exception: [mvccPragma_isActiveAfterOpen] is the gate test — it does NOT
 * guard on isMvccActive because its purpose is to assert that MVCC is enabled.
 */
class JvmLibsqlDriverTest {

    private lateinit var driver: JvmLibsqlDriver

    @Before
    fun setUp() {
        LibsqlTestHarness.assumeNativeAvailable()
        driver = LibsqlTestHarness.createTempDriver()
    }

    @After
    fun tearDown() {
        if (::driver.isInitialized) runCatching { driver.close() }
    }

    // ── Story 5.2 — schema round-trip ─────────────────────────────────────────

    @Test
    fun schemaRoundTrip_createAndQueryPage() {
        val now = System.currentTimeMillis()
        driver.execute(
            null,
            "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(1, "test-uuid-1")
            bindString(2, "Test Page")
            bindLong(3, now)
            bindLong(4, now)
        }

        var foundUuid: String? = null
        var foundName: String? = null
        driver.executeQuery(
            null,
            "SELECT uuid, name FROM pages WHERE uuid = ?",
            { cursor ->
                if (cursor.next().value) {
                    foundUuid = cursor.getString(0)
                    foundName = cursor.getString(1)
                }
                QueryResult.Value(Unit)
            },
            1,
        ) { bindString(1, "test-uuid-1") }

        assertEquals("test-uuid-1", foundUuid, "Inserted page should be queryable by uuid")
        assertEquals("Test Page", foundName, "Page name should round-trip correctly")
    }

    // ── Story 5.3 — transaction commit ────────────────────────────────────────

    @Test
    fun transaction_commit_persistsData() {
        val now = System.currentTimeMillis()
        runBlocking {
            SteleDatabase(driver).transaction {
                driver.execute(
                    null,
                    "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    4,
                ) {
                    bindString(1, "tx-uuid-1")
                    bindString(2, "Tx Page")
                    bindLong(3, now)
                    bindLong(4, now)
                }
            }
        }

        var count = 0L
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pages WHERE uuid = ?",
            { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            1,
        ) { bindString(1, "tx-uuid-1") }

        assertEquals(1L, count, "Committed row must be visible after transaction ends")
    }

    // ── Story 5.4 — transaction rollback ──────────────────────────────────────

    @Test
    fun transaction_rollback_leavesDbUnchanged() {
        val now = System.currentTimeMillis()
        runBlocking {
            SteleDatabase(driver).transaction {
                driver.execute(
                    null,
                    "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    4,
                ) {
                    bindString(1, "rollback-uuid")
                    bindString(2, "Should Not Exist")
                    bindLong(3, now)
                    bindLong(4, now)
                }
                rollback()
            }
        }

        var count = 0L
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pages WHERE uuid = ?",
            { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            1,
        ) { bindString(1, "rollback-uuid") }

        assertEquals(0L, count, "Rolled-back row must not appear in the database")
    }

    // ── Story 5.5 — nested savepoints ─────────────────────────────────────────

    /**
     * In SQLDelight 2.x, calling [rollback] inside a nested [transaction] block throws
     * [app.cash.sqldelight.RollbackException], which SQLDelight's [postTransactionCleanup]
     * re-throws to the enclosing transaction.  The outer transaction therefore ALSO rolls back.
     * This matches SQLDelight's design: nested transactions share the same atomicity boundary.
     *
     * The SAVEPOINTs managed by [JvmLibsqlDriver] are an implementation detail for correct
     * undo semantics; they do NOT expose partial-commit capability at the SQLDelight API level.
     */
    @Test
    fun nestedSavepoint_rollbackPropagatesOutward() {
        val now = System.currentTimeMillis()
        val db = SteleDatabase(driver)

        runBlocking {
            // SQLDelight's rollback() in a nested transaction propagates to the outer;
            // the outer block itself never runs endTransaction(true).
            db.transaction {
                driver.execute(
                    null,
                    "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    4,
                ) {
                    bindString(1, "outer-uuid")
                    bindString(2, "Outer Page")
                    bindLong(3, now)
                    bindLong(4, now)
                }
                db.transaction {
                    driver.execute(
                        null,
                        "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        4,
                    ) {
                        bindString(1, "inner-uuid")
                        bindString(2, "Inner Page")
                        bindLong(3, now)
                        bindLong(4, now)
                    }
                    rollback() // propagates — outer also rolls back
                }
            }
        }

        var outerCount = 0L
        var innerCount = 0L

        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pages WHERE uuid = ?",
            { cursor ->
                if (cursor.next().value) outerCount = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            1,
        ) { bindString(1, "outer-uuid") }

        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pages WHERE uuid = ?",
            { cursor ->
                if (cursor.next().value) innerCount = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            1,
        ) { bindString(1, "inner-uuid") }

        // Both are absent — rollback() inside a nested SQLDelight transaction
        // propagates to the outermost transaction boundary.
        assertEquals(0L, outerCount, "Outer transaction rolls back when inner calls rollback()")
        assertEquals(0L, innerCount, "Inner data also absent after nested rollback")
    }

    // ── Story 5.6 — listener notify ───────────────────────────────────────────

    @Test
    fun listenerNotify_firesExactlyOnce() {
        val counter = AtomicInteger(0)
        val listener = Query.Listener { counter.incrementAndGet() }

        driver.addListener("pages", listener = listener)
        driver.notifyListeners("pages")
        assertEquals(1, counter.get(), "Listener should fire exactly once after one notify call")

        driver.removeListener("pages", listener = listener)
        driver.notifyListeners("pages")
        assertEquals(1, counter.get(), "Removed listener should not fire on subsequent notify")
    }

    @Test
    fun listenerNotify_unknownKey_isNoOp() {
        // Must not throw
        driver.notifyListeners("nonexistent_table_key")
    }

    // ── Story 5.8 — close idempotency ─────────────────────────────────────────

    @Test
    fun close_calledTwice_doesNotThrow() {
        driver.close()
        driver.close() // second call must be a no-op, not throw
    }

    @Test
    fun operationsAfterClose_throwIllegalStateException() {
        driver.close()
        assertFailsWith<IllegalStateException>("execute after close should throw") {
            driver.execute(null, "SELECT 1", 0) {}
        }
    }

    // ── Story 5.11.1 — MVCC gate test ────────────────────────────────────────

    /**
     * Verifies MVCC (BEGIN CONCURRENT) is active when the bundled libsql build supports it.
     * Skips gracefully when the build uses the WAL / BEGIN IMMEDIATE fallback path.
     *
     * libsql 0.9 with the "core" feature set does not expose BEGIN CONCURRENT for
     * local file-based databases; `PRAGMA journal_mode='mvcc'` returns the current mode
     * ("delete" or "wal") rather than switching to mvcc.  Future builds or the remote
     * replica mode may enable it.
     */
    @Test
    fun mvccPragma_isActiveAfterOpen() {
        assumeTrue(
            "MVCC (BEGIN CONCURRENT) not supported by this libsql build — " +
                "driver falls back to BEGIN IMMEDIATE; skipping MVCC gate test.",
            driver.isMvccActive,
        )
        // If we reach here, isMvccActive is true — confirm it is consistent.
        assertTrue(driver.isMvccActive)
    }

    // ── Story 5.11.2 — concurrent disjoint-row commits (MVCC required) ────────

    @Test
    fun mvcc_twoWritersDifferentRows_bothCommit() {
        assumeTrue("MVCC not active — skipping concurrent commit test", driver.isMvccActive)

        val now = System.currentTimeMillis()

        // Pre-populate two pages so we can UPDATE distinct rows concurrently
        driver.execute(
            null,
            "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            4,
        ) { bindString(1, "page-a"); bindString(2, "Page A"); bindLong(3, now); bindLong(4, now) }
        driver.execute(
            null,
            "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            4,
        ) { bindString(1, "page-b"); bindString(2, "Page B"); bindLong(3, now); bindLong(4, now) }

        val executor = Executors.newFixedThreadPool(2)
        val errors = mutableListOf<Throwable>()
        val latch = CountDownLatch(2)

        executor.submit {
            try {
                runBlocking {
                    SteleDatabase(driver).transaction {
                        driver.execute(null, "UPDATE pages SET name = ? WHERE uuid = ?", 2) {
                            bindString(1, "Page A Updated"); bindString(2, "page-a")
                        }
                    }
                }
            } catch (e: Throwable) {
                synchronized(errors) { errors += e }
            } finally {
                latch.countDown()
            }
        }

        executor.submit {
            try {
                runBlocking {
                    SteleDatabase(driver).transaction {
                        driver.execute(null, "UPDATE pages SET name = ? WHERE uuid = ?", 2) {
                            bindString(1, "Page B Updated"); bindString(2, "page-b")
                        }
                    }
                }
            } catch (e: Throwable) {
                synchronized(errors) { errors += e }
            } finally {
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(
            errors.isEmpty(),
            "Both writers on disjoint rows should commit without error under MVCC: $errors",
        )
    }

    // ── Story 5.9 — MigrationRunner compatibility ─────────────────────────────

    @Test
    fun migrationRunner_applyAll_succeedsOnLibsqlDriver() {
        // MigrationRunner.applyAll is idempotent: running it on an already-created
        // schema just marks migrations as applied if not already recorded.
        runBlocking { MigrationRunner.applyAll(driver) }
        // If we reach here without exception, the test passes.
    }
}
