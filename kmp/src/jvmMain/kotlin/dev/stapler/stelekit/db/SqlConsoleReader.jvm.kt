// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import java.sql.Connection
import java.sql.Statement

actual fun openSqlReadHandle(jdbcUrl: String): SqlReadHandle? {
    if (!jdbcUrl.startsWith("jdbc:sqlite:") || jdbcUrl.contains(":memory:")) return null
    return JvmSqlReadHandle(openReadOnlyConnection(jdbcUrl))
}

/**
 * A new non-pooled connection with `SQLiteConfig.setReadOnly(true)` (SQLITE_OPEN_READONLY). A pragma
 * (`query_only`) would be liftable by the statement it guards; the open flag is not (Spike 0.3a).
 * sqlite-jdbc is a runtime-only dependency of this module, so `SQLiteConfig` is reached by reflection,
 * the same way the spike did.
 */
private fun openReadOnlyConnection(jdbcUrl: String): Connection {
    val cfg = Class.forName("org.sqlite.SQLiteConfig").getDeclaredConstructor().newInstance()
    cfg.javaClass.getMethod("setReadOnly", Boolean::class.javaPrimitiveType).invoke(cfg, true)
    cfg.javaClass.getMethod("setBusyTimeout", Int::class.javaPrimitiveType).invoke(cfg, BUSY_TIMEOUT_MS)
    return cfg.javaClass.getMethod("createConnection", String::class.java).invoke(cfg, jdbcUrl) as Connection
}

private const val BUSY_TIMEOUT_MS = 5_000

internal class JvmSqlReadHandle(private val connection: Connection) : SqlReadHandle {
    private val lock = Any()
    private var active: Statement? = null
    private var cancelPending = false

    override fun prepare() = synchronized(lock) { cancelPending = false }

    override fun execute(sql: String, maxCellChars: Int, sink: SqlRowSink) {
        connection.createStatement().use { st ->
            synchronized(lock) {
                active = st
                // sqlite3_interrupt before a step is a no-op, so a cancel that beat the statement is honoured here.
                if (cancelPending) throw java.sql.SQLException("[SQLITE_INTERRUPT] cancelled before the statement started")
            }
            try {
                if (st.execute(sql)) st.resultSet.use { stream(it, maxCellChars, sink) }
            } finally {
                synchronized(lock) { active = null }
            }
        }
    }

    private fun stream(rs: java.sql.ResultSet, maxCellChars: Int, sink: SqlRowSink) {
        val columnCount = rs.metaData.columnCount
        sink.columns((1..columnCount).map { rs.metaData.getColumnLabel(it) })
        while (rs.next()) {
            if (!sink.hasCapacity()) {
                sink.overflow()
                return
            }
            sink.row((1..columnCount).map { cell(rs.getObject(it), maxCellChars) })
        }
    }

    private fun cell(value: Any?, maxCellChars: Int): String? = when (value) {
        null -> null
        is ByteArray -> blobCell(value.size)
        else -> capCell(value.toString(), maxCellChars)
    }

    override fun cancel() = synchronized(lock) {
        cancelPending = true
        active?.cancel()
        Unit
    }

    override fun close() {
        connection.close()
    }
}
