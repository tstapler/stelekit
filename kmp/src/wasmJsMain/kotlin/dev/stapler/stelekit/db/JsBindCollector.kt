package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlPreparedStatement

internal class JsBindCollector : SqlPreparedStatement {
    private val arr: JsAny = emptyJsArray()

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        if (boolean == null) jsArrayPushNull(arr) else jsArrayPushLong(arr, if (boolean) 1L else 0L)
    }
    override fun bindBytes(index: Int, bytes: ByteArray?) {
        if (bytes == null) jsArrayPushNull(arr) else jsArrayPushString(arr, bytes.decodeToString())
    }
    override fun bindDouble(index: Int, double: Double?) {
        if (double == null) jsArrayPushNull(arr) else jsArrayPushDouble(arr, double)
    }
    override fun bindLong(index: Int, long: Long?) {
        if (long == null) jsArrayPushNull(arr) else jsArrayPushLong(arr, long)
    }
    override fun bindString(index: Int, string: String?) {
        if (string == null) jsArrayPushNull(arr) else jsArrayPushString(arr, string)
    }

    fun toJsArray(): JsAny = arr
}
