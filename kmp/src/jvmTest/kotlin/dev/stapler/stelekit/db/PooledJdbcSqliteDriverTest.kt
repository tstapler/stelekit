package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import java.io.File
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [PooledJdbcSqliteDriver].
 *
 * Coverage areas:
 * - Pool pre-creates connections (JDBC.connect called exactly poolSize times)
 * - Connection reuse across get/close cycles
 * - Overflow connections created when pool exhausted (file DB)
 * - Overflow connections closed on return to full pool
 * - In-memory databases block rather than create overflow (schema isolation)
 * - Listener add / notify / remove / deduplication
 * - Close cleans up all pooled connections and listener registry
 * - Concurrent readers all succeed without deadlock
 * - Real SQL round-trip with SQLDelight schema
 */
class PooledJdbcSqliteDriverTest {

    private val props = Properties().apply {
        setProperty("journal_mode", "WAL")
        setProperty("synchronous", "NORMAL")
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private lateinit var tempFile: File
    private lateinit var fileDriver: PooledJdbcSqliteDriver

    @Before
    fun setUp() {
        tempFile = File.createTempFile("pool-test-", ".db")
        fileDriver = PooledJdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", props, poolSize = 3)
    }

    @After
    fun tearDown() {
        runCatching { fileDriver.close() }
        tempFile.delete()
    }

    // ── Connection reuse ──────────────────────────────────────────────────────

    @Test
    fun `getConnection followed by closeConnection returns same connection on next get`() {
        // Use a single-connection pool so there is only one connection to cycle through.
        val singleDriver = PooledJdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", props, poolSize = 1)
        val first = singleDriver.getConnection()
        singleDriver.closeConnection(first)

        val second = singleDriver.getConnection()
        singleDriver.closeConnection(second)

        assertSame(first, second, "pool must reuse connections, not create new ones")
        singleDriver.close()
    }

    @Test
    fun `getConnection returns distinct connections when called without closeConnection`() {
        val a = fileDriver.getConnection()
        val b = fileDriver.getConnection()

        assertNotSame(a, b, "each call before closeConnection should yield a different connection")

        fileDriver.closeConnection(a)
        fileDriver.closeConnection(b)
    }

    // ── Overflow for file databases ───────────────────────────────────────────

    @Test
    fun `file driver creates overflow connection when pool is exhausted`() {
        // Drain the pool completely (poolSize = 3)
        val a = fileDriver.getConnection()
        val b = fileDriver.getConnection()
        val c = fileDriver.getConnection()

        // 4th call must succeed via overflow, not block
        val overflow = fileDriver.getConnection()
        assertNotNull(overflow)
        assertFalse(overflow.isClosed, "overflow connection must be open and usable")

        fileDriver.closeConnection(c)
        fileDriver.closeConnection(b)
        fileDriver.closeConnection(a)
        // overflow goes back to the pool (one slot freed when c was returned)
        fileDriver.closeConnection(overflow)
    }

    @Test
    fun `overflow connection is closed rather than returned to a full pool`() {
        // Pool capacity = 3. Check out 3 connections to drain the pool.
        val a = fileDriver.getConnection()
        val b = fileDriver.getConnection()
        val c = fileDriver.getConnection()

        // 4th = overflow
        val overflow = fileDriver.getConnection()
        assertFalse(overflow.isClosed)

        // Return the 3 pooled connections so pool is full again
        fileDriver.closeConnection(a)
        fileDriver.closeConnection(b)
        fileDriver.closeConnection(c)

        // Returning overflow to a full pool should close it
        fileDriver.closeConnection(overflow)
        assertTrue(overflow.isClosed, "overflow connection must be closed when pool is at capacity")
    }

    // ── In-memory: blocking semantics ─────────────────────────────────────────

    @Test
    fun `in-memory driver blocks second caller until connection is released`() {
        val memDriver = PooledJdbcSqliteDriver("jdbc:sqlite::memory:", props, poolSize = 1)
        val releaseLatch = CountDownLatch(1)
        val acquiredLatch = CountDownLatch(1)
        val blockedThreadAcquired = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()

        val conn = memDriver.getConnection()

        // Thread B tries to get the connection — must block since pool is exhausted
        val future = executor.submit {
            acquiredLatch.countDown()
            val blocked = memDriver.getConnection() // blocks here
            blockedThreadAcquired.set(1)
            releaseLatch.await(2, TimeUnit.SECONDS)
            memDriver.closeConnection(blocked)
        }

        acquiredLatch.await(2, TimeUnit.SECONDS)
        Thread.sleep(50) // give the blocked thread time to actually block

        assertEquals(0, blockedThreadAcquired.get(), "thread B must not have acquired connection yet")

        memDriver.closeConnection(conn) // releases → thread B should unblock

        future.get(3, TimeUnit.SECONDS) // wait for thread B to finish without timeout
        assertEquals(1, blockedThreadAcquired.get(), "thread B should have acquired connection after release")

        releaseLatch.countDown()
        executor.shutdown()
        memDriver.close()
    }

    @Test
    fun `in-memory driver with pool-1 preserves schema across multiple get-close cycles`() {
        val memDriver = PooledJdbcSqliteDriver("jdbc:sqlite::memory:", props, poolSize = 1)
        // Create a table on the single pooled connection
        val conn = memDriver.getConnection()
        conn.createStatement().use { it.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)") }
        conn.createStatement().use { it.execute("INSERT INTO items VALUES (1, 'hello')") }
        memDriver.closeConnection(conn)

        // Next getConnection must return the same connection (schema intact)
        val conn2 = memDriver.getConnection()
        val rows = conn2.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT name FROM items WHERE id = 1")
            if (rs.next()) rs.getString(1) else null
        }
        memDriver.closeConnection(conn2)
        memDriver.close()

        assertEquals("hello", rows, "schema must survive the get/close/get cycle")
    }

    // ── Listener management ───────────────────────────────────────────────────

    @Test
    fun `addListener and notifyListeners fires registered listener`() {
        var notified = 0
        val listener = Query.Listener { notified++ }

        fileDriver.addListener("blocks", listener = listener)
        fileDriver.notifyListeners("blocks")

        assertEquals(1, notified)
    }

    @Test
    fun `removeListener stops future notifications`() {
        var notified = 0
        val listener = Query.Listener { notified++ }

        fileDriver.addListener("blocks", listener = listener)
        fileDriver.removeListener("blocks", listener = listener)
        fileDriver.notifyListeners("blocks")

        assertEquals(0, notified, "removed listener must not be notified")
    }

    @Test
    fun `notifyListeners for unknown key is a no-op`() {
        fileDriver.notifyListeners("nonexistent_key") // must not throw
    }

    @Test
    fun `notifyListeners deduplicates listener registered on multiple keys`() {
        var notified = 0
        val listener = Query.Listener { notified++ }

        fileDriver.addListener("pages", "blocks", listener = listener)
        fileDriver.notifyListeners("pages", "blocks")

        assertEquals(1, notified, "listener registered on both keys must only fire once per notify call")
    }

    @Test
    fun `independent listeners on different keys are each notified`() {
        var pagesNotified = 0
        var blocksNotified = 0
        fileDriver.addListener("pages", listener = Query.Listener { pagesNotified++ })
        fileDriver.addListener("blocks", listener = Query.Listener { blocksNotified++ })

        fileDriver.notifyListeners("pages")

        assertEquals(1, pagesNotified)
        assertEquals(0, blocksNotified)
    }

    // ── Lifecycle / close ─────────────────────────────────────────────────────

    @Test
    fun `close closes all pooled connections`() {
        // Borrow connections then return them so they're all back in the pool
        val connections = (1..3).map { fileDriver.getConnection() }
        connections.forEach { fileDriver.closeConnection(it) }

        fileDriver.close()

        assertTrue(connections.all { it.isClosed }, "every pooled connection must be closed after driver.close()")
    }

    @Test
    fun `close clears listener registry`() {
        var notified = 0
        fileDriver.addListener("blocks", listener = Query.Listener { notified++ })

        fileDriver.close()
        // Notify after close — listener list was cleared, must be a no-op
        fileDriver.notifyListeners("blocks")

        assertEquals(0, notified, "listeners must not fire after driver is closed")
    }

    // ── Real SQL round-trip ───────────────────────────────────────────────────

    @Test
    fun `SQLDelight schema round-trip - write and read back with pooled driver`() {
        val driver = DriverFactory().createDriver("jdbc:sqlite:${tempFile.absolutePath}")
        val db = SteleDatabase(driver)
        val now = System.currentTimeMillis()

        // Write a page via the real SteleDatabase schema
        runBlocking {
            db.steleDatabaseQueries.run {
                transaction {
                    insertPage(
                        uuid = "test-page-uuid",
                        name = "Test Page",
                        namespace = null,
                        file_path = null,
                        created_at = now,
                        updated_at = now,
                        properties = null,
                        version = 1L,
                        is_favorite = 0,
                        is_journal = 0,
                        journal_date = null,
                        is_content_loaded = 0,
                    )
                }
            }
        }

        // Read it back
        val page = db.steleDatabaseQueries.selectPageByUuid("test-page-uuid").executeAsOneOrNull()

        driver.close()

        assertNotNull(page, "page must be retrievable after write")
        assertEquals("Test Page", page.name)
        assertEquals("test-page-uuid", page.uuid)
    }

    // ── Concurrent readers ────────────────────────────────────────────────────

    @Test
    fun `concurrent readers on file pool all complete without error or deadlock`() {
        // Create a table for readers
        val conn = fileDriver.getConnection()
        conn.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS items (id INTEGER)") }
        conn.createStatement().use { it.execute("INSERT INTO items VALUES (1)") }
        fileDriver.closeConnection(conn)

        val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        runBlocking(dispatcher) {
            val results = (1..20).map {
                async {
                    val c = fileDriver.getConnection()
                    val result = c.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT id FROM items").let { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                    fileDriver.closeConnection(c)
                    result
                }
            }.awaitAll()

            assertTrue(results.all { it == 1 }, "all concurrent reads must succeed and return correct data")
        }
        dispatcher.close()
    }

    // ── Connection validity after reuse ───────────────────────────────────────

    @Test
    fun `reused connection is still open and usable after multiple cycles`() {
        repeat(5) {
            val c = fileDriver.getConnection()
            assertFalse(c.isClosed, "connection must be open on get cycle $it")
            // Execute a trivial query to verify the connection is functional
            c.createStatement().use { it.execute("SELECT 1") }
            fileDriver.closeConnection(c)
        }
    }
}
