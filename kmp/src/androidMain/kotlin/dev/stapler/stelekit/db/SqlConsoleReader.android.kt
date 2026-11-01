// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import android.database.Cursor
import androidx.core.os.CancellationSignal
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The console's own `OPEN_READONLY` handle on the requery fork (SQLite 3.49, FTS5). Platform SQLite
 * 3.39.2 has no FTS5 module, so it cannot compile statements that touch the app schema's triggers
 * (Spike 0.3e addendum), and `DriverFactory.createReadDriver` returns a read-write driver.
 */
actual fun openSqlReadHandle(jdbcUrl: String): SqlReadHandle? {
    val name = jdbcUrl.substringAfter("jdbc:sqlite:")
    if (name.isEmpty() || jdbcUrl.contains(":memory:")) return null
    val context = DriverFactory.staticContext ?: return null
    val file = if (name.startsWith("/")) File(name) else context.getDatabasePath(name)
    return AndroidSqlReadHandle(SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
}

internal class AndroidSqlReadHandle(private val db: SQLiteDatabase) : SqlReadHandle {
    private val lock = Any()
    private var signal = CancellationSignal()

    override fun prepare() = synchronized(lock) { signal = CancellationSignal() }

    override fun execute(sql: String, maxCellChars: Int, sink: SqlRowSink) {
        val current = synchronized(lock) { signal }
        // rawQuery throws OperationCanceledException at once when the signal fired before the statement started.
        db.rawQuery(sql, null, current).use { cursor -> stream(cursor, maxCellChars, sink) }
    }

    private fun stream(cursor: Cursor, maxCellChars: Int, sink: SqlRowSink) {
        val columnCount = cursor.columnCount
        sink.columns(cursor.columnNames.toList())
        while (cursor.moveToNext()) {
            if (!sink.hasCapacity()) {
                sink.overflow()
                return
            }
            sink.row((0 until columnCount).map { cell(cursor, it, maxCellChars) })
        }
    }

    private fun cell(cursor: Cursor, index: Int, maxCellChars: Int): String? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_BLOB -> blobCell(cursor.getBlob(index).size)
        else -> capCell(cursor.getString(index), maxCellChars)
    }

    override fun cancel() = synchronized(lock) { signal.cancel() }

    override fun close() {
        db.close()
    }
}
