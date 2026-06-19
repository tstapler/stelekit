package dev.stapler.stelekit.db.libsql

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQLDelight [SqlDriver] backed by libsql's local embedded database via JNI.
 *
 * ## MVCC / BEGIN CONCURRENT
 * When [isMvccActive] is true (libsql compiled with MVCC support), every top-level
 * [newTransaction] issues `BEGIN CONCURRENT` instead of `BEGIN`.  libsql's optimistic MVCC
 * allows multiple writer transactions to proceed in parallel; they conflict only when they
 * touch the same underlying B-tree pages.  On conflict libsql returns SQLITE_BUSY_SNAPSHOT
 * ([LibsqlBusySnapshotException]) — [DatabaseWriteActor] retries.  When MVCC is not active,
 * `BEGIN IMMEDIATE` is used as a safe fallback.
 *
 * ## Connection pool
 * [poolSize] connections are pre-created at startup.  Statements outside an explicit
 * transaction borrow a connection, execute, and return it immediately.  Transactions
 * acquire a dedicated connection for their entire duration so that BEGIN CONCURRENT
 * isolation is upheld across multiple SQL calls.
 *
 * ## Closed-guard and drain latch (Concern A fix)
 * [close] is idempotent.  Any call to [execute], [executeQuery], or [newTransaction] after
 * [close] throws [IllegalStateException].  [close] waits up to 5 s for in-flight operations
 * to finish before tearing down the pool.
 */
class JvmLibsqlDriver(
    dbPath: String,
    private val poolSize: Int = 8,
) : SqlDriver {

    private val dbHandle: Long = LibsqlJni.openDatabase(dbPath)

    /** True when the database was opened with MVCC (BEGIN CONCURRENT) support. */
    val isMvccActive: Boolean = LibsqlJni.isDatabaseMvccEnabled(dbHandle)

    // Pre-created pool of connections.  Overflow connections (created when pool empty)
    // are returned to the pool on release; if the pool is full they are closed.
    private val pool = ArrayBlockingQueue<Long>(poolSize + 4).also { q ->
        repeat(poolSize) { q.put(LibsqlJni.openConnection(dbHandle)) }
    }

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>()

    // Holds the active transaction's state for the calling thread.
    private val txState = ThreadLocal<TxState?>()

    // ── Closed-guard / drain latch ─────────────────────────────────────────

    private val closed = AtomicBoolean(false)
    private val inFlightCount = AtomicInteger(0)
    private val closeLatch = CountDownLatch(1)

    private class TxState(
        val connHandle: Long,
        var nestingLevel: Int = 0,
        var currentTx: Transacter.Transaction? = null,
    )

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun acquireConn(): Long {
        check(!closed.get()) { "libsql driver is closed" }
        inFlightCount.incrementAndGet()
        return txState.get()?.connHandle
            ?: pool.poll()
            ?: LibsqlJni.openConnection(dbHandle)
    }

    private fun releaseConn(handle: Long) {
        try {
            val tx = txState.get()
            if (tx?.connHandle == handle) return // transaction retains ownership
            if (LibsqlJni.isConnectionPoisoned(handle)) {
                LibsqlJni.closeConnection(handle)
                return
            }
            if (!pool.offer(handle)) LibsqlJni.closeConnection(handle)
        } finally {
            if (inFlightCount.decrementAndGet() == 0 && closed.get()) {
                closeLatch.countDown()
            }
        }
    }

    // ── SqlDriver ─────────────────────────────────────────────────────────

    override fun currentTransaction(): Transacter.Transaction? = txState.get()?.currentTx

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        check(!closed.get()) { "libsql driver is closed" }
        val existing = txState.get()
        val enclosing: Transacter.Transaction? = existing?.currentTx

        val connHandle: Long
        val savepointLevel: Int

        if (existing != null) {
            // Nested transaction — use a numbered SAVEPOINT
            existing.nestingLevel++
            savepointLevel = existing.nestingLevel
            connHandle = existing.connHandle
            val spResult = LibsqlJni.executeRaw(connHandle, "SAVEPOINT sp_$savepointLevel")
            if (spResult < 0) {
                val msg = LibsqlJni.connectionLastError(connHandle) ?: "SAVEPOINT failed"
                throw RuntimeException("$msg (sp_$savepointLevel)")
            }
        } else {
            // Top-level transaction — acquire a dedicated connection and BEGIN
            connHandle = pool.poll() ?: LibsqlJni.openConnection(dbHandle)
            savepointLevel = 0
            val beginSql = if (isMvccActive) "BEGIN CONCURRENT" else "BEGIN IMMEDIATE"
            val beginResult = LibsqlJni.executeRaw(connHandle, beginSql)
            if (beginResult < 0) {
                // Read error info before returning/closing the connection — use-after-free otherwise.
                val errcode = LibsqlJni.connectionExtendedErrcode(connHandle)
                val msg = LibsqlJni.connectionLastError(connHandle) ?: beginSql
                if (!pool.offer(connHandle)) LibsqlJni.closeConnection(connHandle)
                if (errcode == LibsqlJni.SQLITE_BUSY_SNAPSHOT) {
                    throw LibsqlBusySnapshotException("$beginSql failed: $msg")
                }
                throw RuntimeException("$beginSql failed (errcode=$errcode): $msg")
            }
            txState.set(TxState(connHandle = connHandle))
        }

        val state = txState.get()!! // set above for both branches
        val tx = object : Transacter.Transaction() {
            override val enclosingTransaction: Transacter.Transaction? = enclosing

            override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                return if (savepointLevel > 0) {
                    val sp = "sp_$savepointLevel"
                    if (successful) {
                        LibsqlJni.executeRaw(connHandle, "RELEASE $sp")
                    } else {
                        LibsqlJni.executeRaw(connHandle, "ROLLBACK TO $sp")
                        LibsqlJni.executeRaw(connHandle, "RELEASE $sp")
                    }
                    state.nestingLevel--
                    QueryResult.Value(Unit)
                } else {
                    if (successful) {
                        val result = LibsqlJni.executeRaw(connHandle, "COMMIT")
                        if (result < 0) {
                            // Read error info before returning/closing the connection — use-after-free otherwise.
                            val errcode = LibsqlJni.connectionExtendedErrcode(connHandle)
                            val commitErr = LibsqlJni.connectionLastError(connHandle)
                            LibsqlJni.executeRaw(connHandle, "ROLLBACK")
                            txState.remove()
                            if (!pool.offer(connHandle)) LibsqlJni.closeConnection(connHandle)
                            if (errcode == LibsqlJni.SQLITE_BUSY_SNAPSHOT) {
                                throw LibsqlBusySnapshotException(
                                    "MVCC snapshot conflict at commit — rollback and retry"
                                )
                            } else {
                                throw RuntimeException("COMMIT failed (errcode=$errcode): $commitErr")
                            }
                        }
                    } else {
                        LibsqlJni.executeRaw(connHandle, "ROLLBACK")
                    }
                    txState.remove()
                    if (!pool.offer(connHandle)) LibsqlJni.closeConnection(connHandle)
                    QueryResult.Value(Unit)
                }
            }
        }
        state.currentTx = tx
        return QueryResult.Value(tx)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        check(!closed.get()) { "libsql driver is closed" }
        val conn = acquireConn()
        try {
            val stmt = LibsqlJni.prepareStatement(conn, sql)
            try {
                binders?.invoke(JniPreparedStatement(stmt))
                val changed = LibsqlJni.executeStatement(conn, stmt)
                if (changed < 0) {
                    val err = LibsqlJni.connectionLastError(conn) ?: "unknown error"
                    throw RuntimeException("libsql execute failed: $err  sql=$sql")
                }
                return QueryResult.Value(changed)
            } finally {
                LibsqlJni.finalizeStatement(stmt)
            }
        } finally {
            releaseConn(conn)
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        check(!closed.get()) { "libsql driver is closed" }
        val conn = acquireConn()
        try {
            val stmt = LibsqlJni.prepareStatement(conn, sql)
            try {
                binders?.invoke(JniPreparedStatement(stmt))
                val cursorHandle = LibsqlJni.queryStatement(conn, stmt)
                if (cursorHandle < 0) {
                    val err = LibsqlJni.connectionLastError(conn) ?: "unknown error"
                    throw RuntimeException("libsql query failed: $err  sql=$sql")
                }
                try {
                    return mapper(JniCursor(cursorHandle))
                } finally {
                    LibsqlJni.closeCursor(cursorHandle)
                }
            } finally {
                LibsqlJni.finalizeStatement(stmt)
            }
        } finally {
            releaseConn(conn)
        }
    }

    // ── Listener registry (SQLDelight reactive query invalidation) ─────────

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key ->
            listeners.getOrPut(key) { CopyOnWriteArrayList() }.add(listener)
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key -> listeners[key]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        queryKeys.forEach { key -> listeners[key]?.let { toNotify.addAll(it) } }
        toNotify.forEach { it.queryResultsChanged() }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Closes and reopens all idle pool connections so they reload the current schema from disk.
     *
     * libsql local mode does not propagate DDL committed by other connections to pre-opened
     * connection handles — their internal sqlite3 schema cache is not refreshed automatically.
     * This causes "no such table" errors when a trigger fires and references a virtual table
     * that was created (by another pool connection) after the triggering connection was opened.
     *
     * Call this once after [SteleDatabase.Schema.create] or [MigrationRunner.applyAll] to
     * ensure all pool connections see the freshly created schema.
     */
    fun resetPool() {
        val handles = mutableListOf<Long>()
        pool.drainTo(handles)
        handles.forEach { LibsqlJni.closeConnection(it) }
        repeat(handles.size) { pool.put(LibsqlJni.openConnection(dbHandle)) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return  // idempotent — Concern A fix
        if (inFlightCount.get() == 0) closeLatch.countDown()
        closeLatch.await(5, TimeUnit.SECONDS)
        listeners.clear()
        val handles = mutableListOf<Long>()
        pool.drainTo(handles)
        handles.forEach { LibsqlJni.closeConnection(it) }
        LibsqlJni.closeDatabase(dbHandle)
    }
}

// ── SqlPreparedStatement backed by JNI stmt handle ────────────────────────

private class JniPreparedStatement(private val handle: Long) : SqlPreparedStatement {
    override fun bindBytes(index: Int, bytes: ByteArray?) =
        if (bytes == null) LibsqlJni.bindNull(handle, index)
        else LibsqlJni.bindBytes(handle, index, bytes)

    override fun bindLong(index: Int, long: Long?) =
        if (long == null) LibsqlJni.bindNull(handle, index)
        else LibsqlJni.bindLong(handle, index, long)

    override fun bindDouble(index: Int, double: Double?) =
        if (double == null) LibsqlJni.bindNull(handle, index)
        else LibsqlJni.bindDouble(handle, index, double)

    override fun bindString(index: Int, string: String?) =
        if (string == null) LibsqlJni.bindNull(handle, index)
        else LibsqlJni.bindString(handle, index, string)

    override fun bindBoolean(index: Int, boolean: Boolean?) =
        if (boolean == null) LibsqlJni.bindNull(handle, index)
        else LibsqlJni.bindLong(handle, index, if (boolean) 1L else 0L)

}

// ── SqlCursor backed by JNI cursor handle ─────────────────────────────────

private class JniCursor(private val handle: Long) : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(LibsqlJni.cursorNext(handle))
    override fun getString(index: Int): String? = LibsqlJni.cursorGetString(handle, index)
    override fun getLong(index: Int): Long? =
        if (LibsqlJni.cursorIsNull(handle, index)) null else LibsqlJni.cursorGetLong(handle, index)
    override fun getBytes(index: Int): ByteArray? = LibsqlJni.cursorGetBytes(handle, index)
    override fun getDouble(index: Int): Double? =
        if (LibsqlJni.cursorIsNull(handle, index)) null else LibsqlJni.cursorGetDouble(handle, index)
    override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
}
