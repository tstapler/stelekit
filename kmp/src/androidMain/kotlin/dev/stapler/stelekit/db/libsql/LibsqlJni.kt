package dev.stapler.stelekit.db.libsql

/**
 * JNI declarations for the Rust `stelekit_libsql` cdylib on Android.
 *
 * On Android the `.so` is bundled in the APK's `jniLibs/<abi>/` directory and loaded via
 * [System.loadLibrary].  The function names match the Rust `#[no_mangle]` exports named
 * `Java_dev_stapler_stelekit_db_libsql_LibsqlJni_*` — the same functions serve both JVM
 * (which loads the library from classpath resources) and Android (which loads from jniLibs).
 *
 * ## Placing the `.so`
 * ```
 * bazel build //native/libsql:stelekit_libsql \
 *   --platforms=@rules_rust//rust/platform:aarch64-linux-android
 * cp bazel-bin/native/libsql/libstelekit_libsql.so \
 *   androidApp/src/main/jniLibs/arm64-v8a/libstelekit_libsql.so
 * ```
 */
object LibsqlJni {

    const val SQLITE_BUSY_SNAPSHOT = 517

    init {
        System.loadLibrary("stelekit_libsql")
    }

    @JvmStatic external fun openDatabase(path: String): Long
    @JvmStatic external fun closeDatabase(handle: Long)
    @JvmStatic external fun openConnection(dbHandle: Long): Long
    @JvmStatic external fun closeConnection(handle: Long)
    @JvmStatic external fun connectionChanges(handle: Long): Long
    @JvmStatic external fun connectionLastInsertRowId(handle: Long): Long
    @JvmStatic external fun connectionLastError(handle: Long): String?
    @JvmStatic external fun connectionExtendedErrcode(connHandle: Long): Int
    @JvmStatic external fun isConnectionPoisoned(connHandle: Long): Boolean
    @JvmStatic external fun isDatabaseMvccEnabled(dbHandle: Long): Boolean
    @JvmStatic external fun executeRaw(connHandle: Long, sql: String): Long
    @JvmStatic external fun prepareStatement(connHandle: Long, sql: String): Long
    @JvmStatic external fun finalizeStatement(handle: Long)
    @JvmStatic external fun bindNull(handle: Long, idx: Int)
    @JvmStatic external fun bindLong(handle: Long, idx: Int, value: Long)
    @JvmStatic external fun bindDouble(handle: Long, idx: Int, value: Double)
    @JvmStatic external fun bindString(handle: Long, idx: Int, value: String)
    @JvmStatic external fun bindBytes(handle: Long, idx: Int, value: ByteArray)
    @JvmStatic external fun executeStatement(connHandle: Long, stmtHandle: Long): Long
    @JvmStatic external fun queryStatement(connHandle: Long, stmtHandle: Long): Long
    @JvmStatic external fun cursorNext(handle: Long): Boolean
    @JvmStatic external fun cursorColumnCount(handle: Long): Int
    @JvmStatic external fun cursorIsNull(handle: Long, idx: Int): Boolean
    @JvmStatic external fun cursorGetLong(handle: Long, idx: Int): Long
    @JvmStatic external fun cursorGetDouble(handle: Long, idx: Int): Double
    @JvmStatic external fun cursorGetString(handle: Long, idx: Int): String?
    @JvmStatic external fun cursorGetBytes(handle: Long, idx: Int): ByteArray?
    @JvmStatic external fun closeCursor(handle: Long)
}
