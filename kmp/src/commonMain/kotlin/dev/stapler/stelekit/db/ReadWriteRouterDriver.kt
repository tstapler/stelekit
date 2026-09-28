package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Routes SQL reads to [readDriver] and writes to [writeDriver].
 *
 * On Android, [readDriver] is a second [AndroidSqliteDriver] connection to the same SQLite
 * file. With WAL mode active on both connections, reads and writes are fully concurrent:
 * reads always see the latest committed snapshot without being blocked by an in-progress
 * write transaction, and writes are not blocked by pending reads.
 *
 * On JVM, the write driver is a [PooledJdbcSqliteDriver] that already provides concurrent
 * connections, so [createReadDriver] returns null and [RepositoryFactoryImpl] uses the write
 * driver directly (no router). On iOS and WASM, similarly no router is used.
 *
 * SQLDelight invalidation flow (unchanged from single-driver setup):
 *   1. Write executes via [writeDriver]; SQLDelight calls [notifyListeners] on [writeDriver].
 *   2. Flow observers registered via [addListener] on [writeDriver] are woken up.
 *   3. Each observer re-executes its [executeQuery] call, which routes to [readDriver].
 *   4. [readDriver] starts a fresh read transaction and returns the latest committed snapshot.
 */
class ReadWriteRouterDriver(
    private val readDriver: SqlDriver,
    private val writeDriver: SqlDriver,
) : SqlDriver {

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = writeDriver.execute(identifier, sql, parameters, binders)

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = readDriver.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        writeDriver.addListener(*queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        writeDriver.removeListener(*queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) =
        writeDriver.notifyListeners(*queryKeys)

    override fun currentTransaction(): Transacter.Transaction? =
        writeDriver.currentTransaction()

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        writeDriver.newTransaction()

    override fun close() {
        readDriver.close()
        if (readDriver !== writeDriver) writeDriver.close()
    }
}
