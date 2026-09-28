package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import kotlinx.coroutines.CancellationException
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import dev.stapler.stelekit.performance.PoolWaitMetrics
import dev.stapler.stelekit.performance.PoolWaitSnapshot
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * SQLite-specific JdbcDriver backed by a fixed-size connection pool.
 *
 * JdbcSqliteDriver's ThreadedConnectionManager creates one JDBC connection per OS thread.
 * With Dispatchers.IO (up to 64 threads) this causes a burst of JDBC.connect calls during
 * graph loading. This pool pre-creates [poolSize] connections at construction so JDBC.connect
 * is called exactly [poolSize] times, ever — connections are then reused indefinitely.
 *
 * WAL mode (applied via [properties]) lets multiple readers proceed concurrently alongside
 * a single writer. DatabaseWriteActor serializes writes so at most one connection is in
 * a write transaction at any given time; the remaining connections are available for reads.
 *
 * ## In-memory databases
 * SQLite `:memory:` databases are connection-scoped: each JDBC connection gets a separate
 * empty database. Overflow connections (created when the pool is exhausted) would return a
 * fresh schema-less database, causing "no such table" errors. For in-memory URLs,
 * [getConnection] polls with a 50 ms timeout instead of creating an overflow connection,
 * checking [closed] each iteration so that [close] is detected within 50 ms.
 */
internal class PooledJdbcSqliteDriver(
    private val url: String,
    private val properties: Properties,
    poolSize: Int = 8,
) : JdbcDriver(), PoolWaitMetrics {

    private val log = Logger.getLogger("PooledJdbcSqliteDriver")

    // Matches the standard `:memory:` form produced by DriverFactory. Does not match the
    // shared-cache URI form `file::memory:?cache=shared` — don't use that form with this driver.
    private val isMemory = url.contains(":memory:")

    private val pool = ArrayBlockingQueue<Connection>(poolSize).also { queue ->
        repeat(poolSize) { queue.put(DriverManager.getConnection(url, properties)) }
    }

    // Thread-safe listener registry backing SQLDelight's reactive Flow invalidation.
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>()

    private val closed = AtomicBoolean(false)

    // Atomic counters for pool wait-time metrics. Thread-safe accumulation; drained every 5s.
    private val poolWaitTotalMs = AtomicLong(0L)
    private val poolWaitCallCount = AtomicLong(0L)

    override fun drainPoolWaitStats(): PoolWaitSnapshot? {
        val total = poolWaitTotalMs.getAndSet(0L)
        val count = poolWaitCallCount.getAndSet(0L)
        return if (count > 0L) PoolWaitSnapshot(total, count) else null
    }

    // ── ConnectionManager ─────────────────────────────────────────────────────

    // Called by JdbcDriver before every statement and at transaction start.
    override fun getConnection(): Connection {
        if (!isMemory) {
            // Non-blocking poll: returns a pooled connection immediately or null if all are
            // checked out. Fallback creates a temporary connection that is closed after use.
            return pool.poll() ?: DriverManager.getConnection(url, properties)
        }
        // In-memory path: block until the single pooled connection is available. Creating an
        // overflow connection would return a fresh schema-less database ("no such table").
        // Poll with a short timeout instead of take() so that close() can be detected —
        // take() blocks forever once the pool is drained, causing a 58-minute test hang.
        val t0 = System.nanoTime()
        while (true) {
            if (closed.get()) error("PooledJdbcSqliteDriver is closed")
            val conn = pool.poll(50, TimeUnit.MILLISECONDS) ?: continue
            val waitMs = (System.nanoTime() - t0) / 1_000_000L
            if (waitMs >= 1L) {
                poolWaitTotalMs.addAndGet(waitMs)
                poolWaitCallCount.incrementAndGet()
            }
            return conn
        }
    }

    // Called by JdbcDriver after every non-transactional statement and at transaction end.
    override fun closeConnection(connection: Connection) {
        if (!pool.offer(connection)) {
            // Pool is full (temporary overflow connection) — close it rather than leak.
            connection.close()
        }
    }

    /**
     * Executes [block] with a single connection held for the entire duration.
     *
     * The connection is checked out from the pool before [block] runs and returned
     * after it completes (or throws). This lets callers safely issue multi-statement
     * sequences that must all run on the same JDBC connection — for example, wrapping
     * DDL in an explicit `BEGIN`/`COMMIT` without risk of acquiring a different
     * connection between statements.
     *
     * For in-memory databases the pool blocks until a connection is available (same
     * semantics as [getConnection]). For file databases an overflow connection is
     * created if the pool is exhausted.
     */
    suspend fun <T> withPinnedConnection(block: suspend (Connection) -> T): T {
        val conn = getConnection()
        return try {
            block(conn)
        } finally {
            closeConnection(conn)
        }
    }

    // beginTransaction / endTransaction / rollbackTransaction manage autoCommit and are
    // provided by JdbcDriver via its ThreadLocal<ConnectionManager.Transaction>.

    // ── SqlDriver listener support ────────────────────────────────────────────

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        for (key in queryKeys) {
            listeners.getOrPut(key) { CopyOnWriteArrayList() }.add(listener)
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        for (key in queryKeys) {
            listeners[key]?.remove(listener)
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        for (key in queryKeys) {
            listeners[key]?.let { toNotify.addAll(it) }
        }
        toNotify.forEach { it.queryResultsChanged() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        closed.set(true)
        listeners.clear()
        var conn = pool.poll()
        while (conn != null) {
            try {
                conn.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning("Failed to close pooled connection during shutdown: ${e.message}")
            }
            conn = pool.poll()
        }
    }
}
