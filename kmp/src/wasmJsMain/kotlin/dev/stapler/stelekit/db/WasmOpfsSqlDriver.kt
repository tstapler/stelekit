package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex

class WasmOpfsSqlDriver(private val workerScriptPath: String) : SqlDriver {

    private val worker: JsAny = createSqliteWorker(workerScriptPath)
    private var nextId = 0
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    // Only one BEGIN can be in flight on the shared OPFS connection at a time. `transactionMutex`
    // serializes unrelated concurrent transactions (e.g. warm reconcile racing an editor write);
    // `currentTxn` lets genuinely nested transaction() calls within the same logical flow (which
    // already hold the mutex from their enclosing transaction) skip sending a redundant BEGIN.
    private val transactionMutex = Mutex()
    private var currentTxn: WasmTransaction? = null

    private inner class WasmTransaction(val enclosing: WasmTransaction?) : Transacter.Transaction() {
        override val enclosingTransaction: Transacter.Transaction? = enclosing
        override fun endTransaction(successful: Boolean): QueryResult<Unit> =
            this@WasmOpfsSqlDriver.endTransaction(successful)
    }
    var actualBackend: String = "unknown"
        private set

    suspend fun init(dbPath: String) {
        val readyPromise = createWorkerReadyPromise(worker)
        workerPostMessage(worker, buildInitMessage(dbPath))
        val readyMsg: JsAny = readyPromise.await()
        actualBackend = getMessageBackend(readyMsg)
        val warning = getMessageWarning(readyMsg)
        if (warning != null) {
            println("[SteleKit] SQLite worker fallback: $warning")
        }
    }

    private fun nextMsgId(): Int = nextId++

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val bindArr = if (binders != null) {
            val c = JsBindCollector()
            binders(c)
            c.toJsArray()
        } else emptyJsArray()
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildExecuteLongMessage(id, sql, bindArr))
        val resp: JsAny = promise.await()
        getMessageChanges(resp)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val bindArr = if (binders != null) {
            val c = JsBindCollector()
            binders(c)
            c.toJsArray()
        } else emptyJsArray()
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildQueryMessage(id, sql, bindArr))
        val resp: JsAny = promise.await()
        val rows = getMessageRows(resp)
        val cursor = JsRowCursor(rows)
        mapper(cursor).await()
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        val enclosing = currentTxn
        if (enclosing == null) {
            // Blocks here until any unrelated in-flight transaction commits/rolls back —
            // this is what prevents a second BEGIN from ever reaching the shared connection.
            transactionMutex.lock()
            val id = nextMsgId()
            val promise = createWorkerResponsePromise(worker, id)
            workerPostMessage(worker, buildTransactionBeginMessage(id))
            @Suppress("UNUSED_VARIABLE") val _begin: JsAny = promise.await()
        }
        val txn = WasmTransaction(enclosing)
        currentTxn = txn
        txn
    }

    fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
        val enclosing = currentTxn?.enclosing
        currentTxn = enclosing
        if (enclosing == null) {
            val id = nextMsgId()
            val promise = createWorkerResponsePromise(worker, id)
            workerPostMessage(worker, buildTransactionEndMessage(id, successful))
            @Suppress("UNUSED_VARIABLE") val _end: JsAny = promise.await()
            transactionMutex.unlock()
        }
        Unit
    }

    override fun currentTransaction(): Transacter.Transaction? = currentTxn

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key -> listeners[key]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.forEach { key -> listeners[key]?.toSet()?.forEach { it.queryResultsChanged() } }
    }

    override fun close() {}
}
