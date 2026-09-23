package dev.stapler.stelekit.db.libsql

import app.cash.sqldelight.db.SqlDriver

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
 * ## Closed-guard and drain latch
 * [close] is idempotent.  Any call to [execute], [executeQuery], or [newTransaction] after
 * [close] throws [IllegalStateException].  [close] waits up to 5 s for in-flight operations
 * to finish before tearing down the pool.
 *
 * Implementation is shared with [AndroidLibsqlDriver] via [LibsqlDriverCore].
 */
class JvmLibsqlDriver private constructor(
    private val core: LibsqlDriverCore,
) : SqlDriver by core {

    constructor(dbPath: String, poolSize: Int = 8) : this(LibsqlDriverCore(dbPath, poolSize))

    /** True when the database was opened with MVCC (BEGIN CONCURRENT) support. */
    val isMvccActive: Boolean get() = core.isMvccActive

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
    fun resetPool() = core.resetPool()
}
