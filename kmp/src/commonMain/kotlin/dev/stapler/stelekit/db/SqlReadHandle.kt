// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

/** Receives one result set from [SqlReadHandle.execute]. Cells are already capped and stringified. */
interface SqlRowSink {
    fun columns(names: List<String>)

    /** Checked before a row's cells are read, so a row past the cap is never materialised. */
    fun hasCapacity(): Boolean
    fun row(cells: List<String?>)

    /** Called once, instead of [row], when another row existed beyond capacity. */
    fun overflow()
}

/**
 * A console-owned, engine-enforced read-only SQLite connection. Blocking; callers run it on
 * `PlatformDispatcher.DB`. Not the app pool and never `DriverFactory.createReadDriver` (read-write on Android).
 */
interface SqlReadHandle {
    /** Clears a cancel left over from a previous statement. Call before [execute]. */
    fun prepare()

    /** Runs [sql] and streams the first result set into [sink]. Throws the engine's exception on failure or [cancel]. */
    fun execute(sql: String, maxCellChars: Int, sink: SqlRowSink)

    /** Stops the running statement in the engine (`Statement.cancel` / `CancellationSignal`). Safe from any thread. */
    fun cancel()

    fun close()
}

/** Opens a read-only handle on the database behind [jdbcUrl], or null where no read-only connection exists (iOS, wasm). */
expect fun openSqlReadHandle(jdbcUrl: String): SqlReadHandle?

/** Shared cell formatting so JVM and Android render identically. */
internal fun capCell(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val marker = "…[cut]"
    return text.substring(0, (maxChars - marker.length).coerceAtLeast(0)) + marker
}

internal fun blobCell(sizeBytes: Int): String = "<blob $sizeBytes bytes>"
