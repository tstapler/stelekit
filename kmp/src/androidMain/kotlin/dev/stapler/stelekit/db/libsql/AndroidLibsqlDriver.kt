package dev.stapler.stelekit.db.libsql

import android.content.Context
import app.cash.sqldelight.db.SqlDriver

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
 * Pool size defaults to 4 (vs 8 on JVM) to stay within mobile memory budgets.
 * Implementation is shared with [JvmLibsqlDriver] via [LibsqlDriverCore].
 */
class AndroidLibsqlDriver private constructor(
    private val core: LibsqlDriverCore,
) : SqlDriver by core {

    constructor(dbPath: String, poolSize: Int = 4) : this(LibsqlDriverCore(dbPath, poolSize))

    /** True when the database was opened with MVCC (BEGIN CONCURRENT) support. */
    val isMvccActive: Boolean get() = core.isMvccActive

    /** Re-opens all pooled connections so they see schema changes made since pool creation. */
    fun resetPool() = core.resetPool()

    companion object {
        fun create(context: Context, graphId: String): AndroidLibsqlDriver {
            LibsqlJni  // trigger init { loadLibsqlNativeLibrary() } via class initialisation
            val dbPath = "${context.filesDir.absolutePath}/stelekit-graph-$graphId.db"
            return AndroidLibsqlDriver(dbPath)
        }
    }
}
