package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor

internal class JsRowCursor(private val rows: JsAny) : SqlCursor {
    private var index = -1
    private var columnNames: List<String>? = null
    private val count = rowsLength(rows)

    override fun next(): QueryResult<Boolean> {
        index++
        if (index < count && columnNames == null) {
            val row = getRow(rows, 0)
            val keys = getColumnNames(row)
            val len = jsArrayLength(keys)
            columnNames = (0 until len).map { jsArrayGetString(keys, it) }
        }
        return QueryResult.Value(index < count)
    }

    private fun currentValue(columnIndex: Int): JsAny? {
        val cols = columnNames ?: return null
        if (columnIndex >= cols.size) return null
        val row = getRow(rows, index)
        return getColumnValue(row, cols[columnIndex])
    }

    override fun getString(index: Int): String? {
        val v = currentValue(index) ?: return null
        if (jsValueIsNull(v)) return null
        return jsValueToString(v)
    }

    override fun getLong(index: Int): Long? {
        val v = currentValue(index) ?: return null
        if (jsValueIsNull(v)) return null
        return jsValueToDouble(v).toLong()
    }

    override fun getDouble(index: Int): Double? {
        val v = currentValue(index) ?: return null
        if (jsValueIsNull(v)) return null
        return jsValueToDouble(v)
    }

    override fun getBytes(index: Int): ByteArray? {
        val v = currentValue(index) ?: return null
        if (jsValueIsNull(v)) return null
        return jsValueToString(v).encodeToByteArray()
    }

    override fun getBoolean(index: Int): Boolean? {
        val v = currentValue(index) ?: return null
        if (jsValueIsNull(v)) return null
        return jsValueToDouble(v).toLong() != 0L
    }
}
