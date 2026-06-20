package dev.stapler.stelekit.db.libsql

/**
 * JNI declarations for the Rust `stelekit_libsql` cdylib.
 *
 * Function names follow the JNI mangling convention and map 1:1 to the `#[no_mangle]`
 * `pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_*` functions in
 * `native/libsql/src/lib.rs`.
 *
 * Library loading is platform-specific ([loadLibsqlNativeLibrary]):
 * - JVM desktop: extracted from classpath resource `native/<os-arch>/libstelekit_libsql.{so,dylib,dll}`
 * - Android: loaded via [System.loadLibrary] from the APK's `jniLibs/<abi>/` directory
 */
object LibsqlJni {

    const val SQLITE_BUSY_SNAPSHOT = 517

    init {
        loadLibsqlNativeLibrary()
    }

    // ── Database lifecycle ──────────────────────────────────────────────────

    /** Opens a local libsql database at [path]. Returns an opaque handle (> 0) or throws. */
    @JvmStatic external fun openDatabase(path: String): Long

    /** Frees a database handle.  All connections must be closed first. */
    @JvmStatic external fun closeDatabase(handle: Long)

    // ── Connection lifecycle ────────────────────────────────────────────────

    /** Opens a new connection to the database identified by [dbHandle]. */
    @JvmStatic external fun openConnection(dbHandle: Long): Long

    /** Closes and frees a connection handle. */
    @JvmStatic external fun closeConnection(handle: Long)

    /** Returns the number of rows changed by the last DML statement. */
    @JvmStatic external fun connectionChanges(handle: Long): Long

    /** Returns the rowid of the last successful INSERT. */
    @JvmStatic external fun connectionLastInsertRowId(handle: Long): Long

    /** Returns the last error message for this connection, or null if none. */
    @JvmStatic external fun connectionLastError(handle: Long): String?

    /** Returns the extended error code for this connection (e.g. SQLITE_BUSY_SNAPSHOT = 517). */
    @JvmStatic external fun connectionExtendedErrcode(connHandle: Long): Int

    /** Returns true if the connection has been poisoned by a fatal error and must be discarded. */
    @JvmStatic external fun isConnectionPoisoned(connHandle: Long): Boolean

    /** Returns true if the database was opened with MVCC (BEGIN CONCURRENT) support. */
    @JvmStatic external fun isDatabaseMvccEnabled(dbHandle: Long): Boolean

    // ── Raw execute (PRAGMA / transaction control / DDL without params) ─────

    /**
     * Executes a single SQL statement with no bound parameters.
     * Returns rows-changed (≥ 0) or -1 on failure.  Used for PRAGMA, BEGIN CONCURRENT,
     * COMMIT, ROLLBACK, and DDL during schema setup.
     */
    @JvmStatic external fun executeRaw(connHandle: Long, sql: String): Long

    // ── Statement (prepare / bind / execute / query / finalize) ────────────

    /**
     * Allocates a statement handle for [sql].  No libsql round-trip at this point;
     * the SQL is stored and prepared lazily when [executeStatement] or [queryStatement] is called.
     */
    @JvmStatic external fun prepareStatement(connHandle: Long, sql: String): Long

    /** Frees a statement handle.  Must be called after execute/query is complete. */
    @JvmStatic external fun finalizeStatement(handle: Long)

    // Index convention: 0-based (SQLDelight generated code) or 1-based (hand-written callers).
    // build_params() in lib.rs detects the convention from min(idx) and handles both.
    @JvmStatic external fun bindNull(handle: Long, idx: Int)
    @JvmStatic external fun bindLong(handle: Long, idx: Int, value: Long)
    @JvmStatic external fun bindDouble(handle: Long, idx: Int, value: Double)
    @JvmStatic external fun bindString(handle: Long, idx: Int, value: String)
    @JvmStatic external fun bindBytes(handle: Long, idx: Int, value: ByteArray)

    /**
     * Executes the statement (INSERT / UPDATE / DELETE / DDL).
     * Returns rows-changed or -1 on failure.
     */
    @JvmStatic external fun executeStatement(connHandle: Long, stmtHandle: Long): Long

    /**
     * Executes a SELECT statement and eagerly collects all rows.
     * Returns a cursor handle or -1 on failure.
     */
    @JvmStatic external fun queryStatement(connHandle: Long, stmtHandle: Long): Long

    // ── Cursor ─────────────────────────────────────────────────────────────

    /**
     * Advances the cursor to the next row.
     * Returns true if a row is available; false when the result set is exhausted.
     */
    @JvmStatic external fun cursorNext(handle: Long): Boolean

    @JvmStatic external fun cursorColumnCount(handle: Long): Int

    /** Returns true if the value at [idx] is NULL in the current row. */
    @JvmStatic external fun cursorIsNull(handle: Long, idx: Int): Boolean

    @JvmStatic external fun cursorGetLong(handle: Long, idx: Int): Long
    @JvmStatic external fun cursorGetDouble(handle: Long, idx: Int): Double

    /** Returns the text value at [idx], or null if NULL. */
    @JvmStatic external fun cursorGetString(handle: Long, idx: Int): String?

    /** Returns the blob value at [idx] as a byte array, or null if NULL. */
    @JvmStatic external fun cursorGetBytes(handle: Long, idx: Int): ByteArray?

    /** Frees the cursor handle.  Must be called after all rows have been consumed. */
    @JvmStatic external fun closeCursor(handle: Long)
}
