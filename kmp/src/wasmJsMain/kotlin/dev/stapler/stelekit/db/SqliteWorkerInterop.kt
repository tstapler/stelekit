package dev.stapler.stelekit.db

internal fun createSqliteWorker(scriptPath: String): JsAny =
    js("new Worker(scriptPath, { type: 'module' })")

internal fun workerPostMessage(worker: JsAny, message: JsAny): Unit =
    js("worker.postMessage(message)")

internal fun buildInitMessage(dbPath: String): JsAny =
    js("({ type: 'init', dbPath: dbPath })")

internal fun buildExecMessage(id: Int, sql: String, bindArray: JsAny): JsAny =
    js("({ type: 'exec', id: id, sql: sql, bind: bindArray })")

internal fun buildQueryMessage(id: Int, sql: String, bindArray: JsAny): JsAny =
    js("({ type: 'query', id: id, sql: sql, bind: bindArray })")

internal fun buildTransactionBeginMessage(id: Int): JsAny =
    js("({ type: 'transaction-begin', id: id })")

internal fun buildTransactionEndMessage(id: Int, successful: Boolean): JsAny =
    js("({ type: 'transaction-end', id: id, successful: successful })")

internal fun buildExecuteLongMessage(id: Int, sql: String, bindArray: JsAny): JsAny =
    js("({ type: 'execute-long', id: id, sql: sql, bind: bindArray })")

internal fun emptyJsArray(): JsAny = js("[]")

internal fun jsArrayPushString(arr: JsAny, value: String): Unit = js("arr.push(value)")
internal fun jsArrayPushLong(arr: JsAny, value: Long): Unit = js("arr.push(Number(value))")
internal fun jsArrayPushDouble(arr: JsAny, value: Double): Unit = js("arr.push(value)")
internal fun jsArrayPushNull(arr: JsAny): Unit = js("arr.push(null)")

internal fun getMessageType(msg: JsAny): String = js("msg.type")
internal fun getMessageId(msg: JsAny): Int = js("msg.id | 0")
internal fun getMessageRows(msg: JsAny): JsAny = js("msg.rows")
internal fun getMessageChanges(msg: JsAny): Long = js("BigInt(msg.value)")
internal fun getMessageError(msg: JsAny): String = js("msg.message")
internal fun getMessageBackend(msg: JsAny): String = js("msg.backend")
internal fun getMessageWarning(msg: JsAny): String? = js("msg.warning || null")
internal fun rowsLength(rows: JsAny): Int = js("rows.length | 0")
internal fun getRow(rows: JsAny, index: Int): JsAny = js("rows[index]")
internal fun getColumnNames(row: JsAny): JsAny = js("Object.keys(row)")
internal fun jsArrayLength(arr: JsAny): Int = js("arr.length | 0")
internal fun jsArrayGetString(arr: JsAny, index: Int): String = js("arr[index]")
internal fun getColumnValue(row: JsAny, name: String): JsAny? = js("row[name] ?? null")
internal fun jsValueToString(v: JsAny): String = js("String(v)")
internal fun jsValueToDouble(v: JsAny): Double = js("Number(v)")
internal fun jsValueIsNull(v: JsAny?): Boolean = js("v === null || v === undefined")
internal fun jsValueIsNumber(v: JsAny): Boolean = js("typeof v === 'number'")
internal fun jsValueIsString(v: JsAny): Boolean = js("typeof v === 'string'")

internal fun createWorkerReadyPromise(worker: JsAny): kotlin.js.Promise<JsAny> = js("""
    new Promise(function(resolve) {
        function onReady(e) {
            if (e.data.type === 'ready') {
                worker.removeEventListener('message', onReady);
                resolve(e.data);
            }
        }
        worker.addEventListener('message', onReady);
    })
""")

internal fun createWorkerResponsePromise(worker: JsAny, id: Int): kotlin.js.Promise<JsAny> = js("""
    new Promise(function(resolve, reject) {
        function handler(e) {
            var data = e.data;
            if ((data.type === 'result' || data.type === 'long-result' || data.type === 'error') && (data.id | 0) === (id | 0)) {
                worker.removeEventListener('message', handler);
                if (data.type === 'error') {
                    reject(new Error(data.message));
                } else {
                    resolve(data);
                }
            }
        }
        worker.addEventListener('message', handler);
    })
""")
