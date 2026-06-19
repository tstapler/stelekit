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
 * Shared [SqlDriver] implementation backed by the libsql JNI bridge.
 *
 * Used by both [JvmLibsqlDriver] (desktop, poolSize = 8) and [AndroidLibsqlDriver]
 * (mobile, poolSize = 4) via Kotlin interface delegation (`by core`).
 *
 * See [JvmLibsqlDriver] KDoc for the full design description (MVCC, pool, closed-guard).
 */
internal class LibsqlDriverCore(
    dbPath: String,
    poolSize: Int,
) : SqlDriver {

    private val dbHandle: Long = LibsqlJni.openDatabase(dbPath)

    val isMvccActive: Boolean = LibsqlJni.isDatabaseMvccEnabled(dbHandle)

    private val pool = ArrayBlockingQueue<Long>(poolSize + 4).also { q ->
        repeat(poolSize) { q.put(LibsqlJni.openConnection(dbHandle)) }
    }

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>()
    private val txState = ThreadLocal<TxState?>()

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
            existing.nestingLevel++
            savepointLevel = existing.nestingLevel
            connHandle = existing.connHandle
            val spResult = LibsqlJni.executeRaw(connHandle, "SAVEPOINT sp_$savepointLevel")
            if (spResult < 0) {
                val msg = LibsqlJni.connectionLastError(connHandle) ?: "SAVEPOINT failed"
                throw RuntimeException("$msg (sp_$savepointLevel)")
            }
        } else {
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

        val state = txState.get()!!
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
                binders?.invoke(LibsqlPreparedStatement(stmt))
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
                binders?.invoke(LibsqlPreparedStatement(stmt))
                val cursorHandle = LibsqlJni.queryStatement(conn, stmt)
                if (cursorHandle < 0) {
                    val err = LibsqlJni.connectionLastError(conn) ?: "unknown error"
                    throw RuntimeException("libsql query failed: $err  sql=$sql")
                }
                try {
                    return mapper(LibsqlCursor(cursorHandle))
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

    // ── Listener registry ──────────────────────────────────────────────────

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
     * connection handles.  Call once after [app.cash.sqldelight.db.SqlSchema.create] or
     * [dev.stapler.stelekit.db.MigrationRunner.applyAll] to ensure all pool connections see
     * the freshly created schema.
     */
    fun resetPool() {
        val handles = mutableListOf<Long>()
        pool.drainTo(handles)
        handles.forEach { LibsqlJni.closeConnection(it) }
        repeat(handles.size) { pool.put(LibsqlJni.openConnection(dbHandle)) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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

private class LibsqlPreparedStatement(private val handle: Long) : SqlPreparedStatement {
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

private class LibsqlCursor(private val handle: Long) : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(LibsqlJni.cursorNext(handle))
    override fun getString(index: Int): String? = LibsqlJni.cursorGetString(handle, index)
    override fun getLong(index: Int): Long? =
        if (LibsqlJni.cursorIsNull(handle, index)) null else LibsqlJni.cursorGetLong(handle, index)
    override fun getBytes(index: Int): ByteArray? = LibsqlJni.cursorGetBytes(handle, index)
    override fun getDouble(index: Int): Double? =
        if (LibsqlJni.cursorIsNull(handle, index)) null else LibsqlJni.cursorGetDouble(handle, index)
    override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
}
