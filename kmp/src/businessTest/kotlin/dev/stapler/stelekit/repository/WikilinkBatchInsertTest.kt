package dev.stapler.stelekit.repository

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R4: Batch wikilink inserts — verifies that [RestrictedDatabaseQueries.insertWikilinkReferencesBatch]
 * issues a single multi-row INSERT OR IGNORE per chunk rather than one statement per page name.
 *
 * Tests R4-T1 through R4-T4 from the validation plan:
 * - R4-T1: 5 wikilinks → 1 INSERT statement
 * - R4-T2: 500 wikilinks → 2 INSERT statements (ceil(500/499) = 2 chunks)
 * - R4-T3: INSERT OR IGNORE semantics — no duplicate rows on re-insert
 * - R4-T4: binder index correctness — all (block_uuid, page_name) pairs correctly bound
 */
class WikilinkBatchInsertTest {

    private lateinit var realDriver: SqlDriver
    private lateinit var countingDriver: CountingSqlDriver
    private lateinit var database: SteleDatabase

    @BeforeTest
    fun setUp() {
        realDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        countingDriver = CountingSqlDriver(realDriver)
        database = SteleDatabase(countingDriver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { realDriver.close() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeRestricted() = RestrictedDatabaseQueries(
        database.steleDatabaseQueries,
        countingDriver,
    )

    /**
     * Resets the INSERT counter, runs [block], then returns the number of
     * INSERT statements targeting wikilink_references that were issued.
     */
    private fun countWikilinkInserts(block: () -> Unit): Int {
        countingDriver.resetCounts()
        block()
        return countingDriver.wikilinkInsertCount
    }

    /** Queries the wikilink_references table directly to verify row counts. */
    private fun rowCountForBlock(blockUuid: String): Int {
        var count = 0
        realDriver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM wikilink_references WHERE block_uuid = ?",
            mapper = { cursor ->
                if (cursor.next().value) count = cursor.getLong(0)?.toInt() ?: 0
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, blockUuid) },
        ).value
        return count
    }

    /** Returns all page_name values stored for [blockUuid]. */
    private fun rowsForBlock(blockUuid: String): Set<String> {
        val names = mutableSetOf<String>()
        realDriver.executeQuery(
            identifier = null,
            sql = "SELECT page_name FROM wikilink_references WHERE block_uuid = ?",
            mapper = { cursor ->
                while (cursor.next().value) {
                    names += cursor.getString(0) ?: ""
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, blockUuid) },
        ).value
        return names
    }

    /**
     * Inserts a minimal page and block into the DB so FK constraints are satisfied
     * when rows are inserted into wikilink_references (block_uuid FK → blocks.uuid).
     */
    private fun insertPageAndBlock(blockUuid: String) {
        realDriver.execute(
            null,
            "INSERT OR IGNORE INTO pages (uuid, name, created_at, updated_at, is_content_loaded) VALUES ('page-uuid', 'Test Page', 0, 0, 1)",
            0,
        )
        realDriver.execute(
            null,
            "INSERT OR IGNORE INTO blocks (uuid, page_uuid, content, level, position, created_at, updated_at, version, block_type) VALUES (?, 'page-uuid', '', 0, 0, 0, 0, 0, 'bullet')",
            1,
        ) { bindString(0, blockUuid) }
    }

    // ── R4-T1: 5 wikilinks → 1 INSERT statement ──────────────────────────────

    @Test
    fun `5 page names produce exactly 1 INSERT statement`() = runBlocking {
        val blockUuid = "block-001"
        insertPageAndBlock(blockUuid)
        val pageNames = (1..5).map { "Page$it" }.toSet()

        val inserts = countWikilinkInserts {
            runBlocking {
                @OptIn(DirectSqlWrite::class)
                makeRestricted().insertWikilinkReferencesBatch(blockUuid, pageNames)
            }
        }

        assertEquals(1, inserts, "5 page names should produce exactly 1 INSERT OR IGNORE statement")
        assertEquals(5, rowCountForBlock(blockUuid), "DB should have exactly 5 wikilink_references rows")
    }

    // ── R4-T2: 500 wikilinks → 2 INSERT statements ───────────────────────────

    @Test
    fun `500 page names produce exactly 2 INSERT statements due to chunking`() = runBlocking {
        val blockUuid = "block-002"
        insertPageAndBlock(blockUuid)
        val pageNames = (1..500).map { "Page$it" }.toSet()

        val inserts = countWikilinkInserts {
            runBlocking {
                @OptIn(DirectSqlWrite::class)
                makeRestricted().insertWikilinkReferencesBatch(blockUuid, pageNames)
            }
        }

        // ceil(500 / 499) = 2 chunks
        assertEquals(2, inserts, "500 page names (chunk size 499) should produce exactly 2 INSERT statements")
        assertEquals(500, rowCountForBlock(blockUuid), "DB should have exactly 500 wikilink_references rows")
    }

    // ── R4-T3: INSERT OR IGNORE semantics — no duplicates on re-insert ────────

    @Test
    fun `INSERT OR IGNORE does not duplicate rows when called twice with same names`() = runBlocking {
        val blockUuid = "block-003"
        insertPageAndBlock(blockUuid)
        val pageNames = setOf("Alpha", "Beta", "Gamma")

        @OptIn(DirectSqlWrite::class)
        makeRestricted().insertWikilinkReferencesBatch(blockUuid, pageNames)
        val countAfterFirst = rowCountForBlock(blockUuid)

        @OptIn(DirectSqlWrite::class)
        makeRestricted().insertWikilinkReferencesBatch(blockUuid, pageNames)
        val countAfterSecond = rowCountForBlock(blockUuid)

        assertEquals(3, countAfterFirst, "First insert should produce 3 rows")
        assertEquals(3, countAfterSecond, "Second insert should not add duplicates (INSERT OR IGNORE)")
    }

    // ── R4-T4: binder index correctness — all pairs correctly stored ──────────

    @Test
    fun `all block_uuid and page_name pairs are bound at correct positions`() = runBlocking {
        val blockUuid = "block-004"
        insertPageAndBlock(blockUuid)
        val pageNames = setOf("Foo", "Bar", "Baz", "Qux", "Quux")

        @OptIn(DirectSqlWrite::class)
        makeRestricted().insertWikilinkReferencesBatch(blockUuid, pageNames)

        val stored = rowsForBlock(blockUuid)
        assertEquals(pageNames, stored, "All page names must be stored at the correct bind positions")
    }

    // ── empty set is a no-op ──────────────────────────────────────────────────

    @Test
    fun `empty page names collection produces 0 INSERT statements`() = runBlocking {
        val blockUuid = "block-005"
        insertPageAndBlock(blockUuid)

        val inserts = countWikilinkInserts {
            runBlocking {
                @OptIn(DirectSqlWrite::class)
                makeRestricted().insertWikilinkReferencesBatch(blockUuid, emptySet())
            }
        }

        assertEquals(0, inserts, "Empty collection must produce no INSERT statements")
        assertEquals(0, rowCountForBlock(blockUuid))
    }

    // ── SQL statement counter wrapper ─────────────────────────────────────────

    /**
     * Wraps a real [SqlDriver] and counts INSERT statements targeting wikilink_references.
     * All calls are forwarded to the delegate unchanged.
     */
    private class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver {

        var wikilinkInsertCount: Int = 0
            private set

        fun resetCounts() { wikilinkInsertCount = 0 }

        private fun trackIfWikilinkInsert(sql: String) {
            val upper = sql.uppercase().trim()
            if (upper.startsWith("INSERT") && upper.contains("WIKILINK_REFERENCES")) {
                wikilinkInsertCount++
            }
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            trackIfWikilinkInsert(sql)
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

        override fun newTransaction(): QueryResult<Transacter.Transaction> =
            delegate.newTransaction()

        override fun currentTransaction(): Transacter.Transaction? =
            delegate.currentTransaction()

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.addListener(*queryKeys, listener = listener)

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
            delegate.removeListener(*queryKeys, listener = listener)

        override fun notifyListeners(vararg queryKeys: String) =
            delegate.notifyListeners(*queryKeys)

        override fun close() = delegate.close()
    }
}
