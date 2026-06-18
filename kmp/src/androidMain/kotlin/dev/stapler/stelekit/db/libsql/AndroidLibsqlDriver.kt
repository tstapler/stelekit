package dev.stapler.stelekit.db.libsql

import android.content.Context
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
 * SQLDelight [SqlDriver] backed by libsql on Android via the same JNI bridge as [JvmLibsqlDriver].
 *
 * The `stelekit_libsql` `.so` compiled for `aarch64-linux-android` / `x86_64-linux-android`
 * must be placed in the APK's `jniLibs/<abi>/` directory.  Android's class loader then makes
 * it available to [System.loadLibrary].
 *
 * ## Build the Android `.so`
 * ```
 * bazel build //native/libsql:stelekit_libsql \
 *   --platforms=@rules_rust//rust/platform:aarch64-linux-android
 * # Copy output to androidApp/src/main/jniLibs/arm64-v8a/libstelekit_libsql.so
 * ```
 *
 * ## Status
 * Uses [BEGIN CONCURRENT] when MVCC is active ([isMvccActive]); falls back to
 * [BEGIN IMMEDIATE] otherwise.  Same semantics as the JVM driver.
 * Pool size defaults to 4 (vs 8 on JVM) to stay within mobile memory budgets.
 */
class AndroidLibsqlDriver(
    dbPath: String,
    private val poolSize: Int = 4,
) : SqlDriver {

    companion object {
        fun create(context: Context, graphId: String): AndroidLibsqlDriver {
            LibsqlJni  // trigger init { System.loadLibrary } via class initialisation
            val dbPath = "${context.filesDir.absolutePath}/stelekit-graph-$graphId.db"
            return AndroidLibsqlDriver(dbPath)
        }
    }

    private val dbHandle: Long = LibsqlJni.openDatabase(dbPath)

    /** True when the database was opened with MVCC (BEGIN CONCURRENT) support. */
    val isMvccActive: Boolean = LibsqlJni.isDatabaseMvccEnabled(dbHandle)

    private val pool = ArrayBlockingQueue<Long>(poolSize + 2).also { q ->
        repeat(poolSize) { q.put(LibsqlJni.openConnection(dbHandle)) }
    }
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>()
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

    private fun acquireConn(): Long {
        check(!closed.get()) { "libsql driver is closed" }
        inFlightCount.incrementAndGet()
        return txState.get()?.connHandle ?: pool.poll() ?: LibsqlJni.openConnection(dbHandle)
    }

    private fun releaseConn(handle: Long) {
        try {
            val tx = txState.get()
            if (tx?.connHandle == handle) return
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
            LibsqlJni.executeRaw(connHandle, "SAVEPOINT sp_$savepointLevel")
        } else {
            connHandle = pool.poll() ?: LibsqlJni.openConnection(dbHandle)
            savepointLevel = 0
            val beginSql = if (isMvccActive) "BEGIN CONCURRENT" else "BEGIN IMMEDIATE"
            LibsqlJni.executeRaw(connHandle, beginSql)
            txState.set(TxState(connHandle = connHandle))
        }

        val state = txState.get()!!
        val tx = object : Transacter.Transaction() {
            override val enclosingTransaction: Transacter.Transaction? = enclosing

            override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                return if (savepointLevel > 0) {
                    val sp = "sp_$savepointLevel"
                    if (successful) LibsqlJni.executeRaw(connHandle, "RELEASE $sp")
                    else {
                        LibsqlJni.executeRaw(connHandle, "ROLLBACK TO $sp")
                        LibsqlJni.executeRaw(connHandle, "RELEASE $sp")
                    }
                    state.nestingLevel--
                    QueryResult.Value(Unit)
                } else {
                    if (successful) {
                        val result = LibsqlJni.executeRaw(connHandle, "COMMIT")
                        if (result < 0) {
                            val errcode = LibsqlJni.connectionExtendedErrcode(connHandle)
                            LibsqlJni.executeRaw(connHandle, "ROLLBACK")
                            txState.remove()
                            if (!pool.offer(connHandle)) LibsqlJni.closeConnection(connHandle)
                            if (errcode == LibsqlJni.SQLITE_BUSY_SNAPSHOT) {
                                throw LibsqlBusySnapshotException(
                                    "MVCC snapshot conflict at commit — rollback and retry"
                                )
                            } else {
                                throw RuntimeException(
                                    "COMMIT failed (errcode=$errcode): ${LibsqlJni.connectionLastError(connHandle)}"
                                )
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
                binders?.invoke(JniStmt(stmt))
                val changed = LibsqlJni.executeStatement(conn, stmt)
                return QueryResult.Value(if (changed >= 0) changed else LibsqlJni.connectionLastInsertRowId(conn))
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
                binders?.invoke(JniStmt(stmt))
                val cursor = LibsqlJni.queryStatement(conn, stmt)
                if (cursor < 0) throw RuntimeException("libsql query failed: ${LibsqlJni.connectionLastError(conn)}")
                try {
                    return mapper(JniCursor(cursor))
                } finally {
                    LibsqlJni.closeCursor(cursor)
                }
            } finally {
                LibsqlJni.finalizeStatement(stmt)
            }
        } finally {
            releaseConn(conn)
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { CopyOnWriteArrayList() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        queryKeys.forEach { listeners[it]?.let { l -> toNotify.addAll(l) } }
        toNotify.forEach { it.queryResultsChanged() }
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

    // ── Prepared statement and cursor — delegate to top-level LibsqlJni ───

    private class JniStmt(private val h: Long) : SqlPreparedStatement {
        override fun bindBytes(index: Int, bytes: ByteArray?) =
            if (bytes == null) LibsqlJni.bindNull(h, index) else LibsqlJni.bindBytes(h, index, bytes)
        override fun bindLong(index: Int, long: Long?) =
            if (long == null) LibsqlJni.bindNull(h, index) else LibsqlJni.bindLong(h, index, long)
        override fun bindDouble(index: Int, double: Double?) =
            if (double == null) LibsqlJni.bindNull(h, index) else LibsqlJni.bindDouble(h, index, double)
        override fun bindString(index: Int, string: String?) =
            if (string == null) LibsqlJni.bindNull(h, index) else LibsqlJni.bindString(h, index, string)
        override fun bindBoolean(index: Int, boolean: Boolean?) =
            if (boolean == null) LibsqlJni.bindNull(h, index) else LibsqlJni.bindLong(h, index, if (boolean) 1L else 0L)
    }

    private class JniCursor(private val h: Long) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(LibsqlJni.cursorNext(h))
        override fun getString(index: Int): String? = LibsqlJni.cursorGetString(h, index)
        override fun getLong(index: Int): Long? = if (LibsqlJni.cursorIsNull(h, index)) null else LibsqlJni.cursorGetLong(h, index)
        override fun getBytes(index: Int): ByteArray? = LibsqlJni.cursorGetBytes(h, index)
        override fun getDouble(index: Int): Double? = if (LibsqlJni.cursorIsNull(h, index)) null else LibsqlJni.cursorGetDouble(h, index)
        override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
    }
}
