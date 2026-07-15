package dev.stapler.stelekit.platform

import kotlinx.coroutines.await

internal fun showDirectoryPickerSupported(): Boolean = js("typeof window.showDirectoryPicker === 'function'")
private fun showDirectoryPickerPromise(): kotlin.js.Promise<JsAny> = js("window.showDirectoryPicker()")
internal suspend fun showDirectoryPicker(): JsAny = showDirectoryPickerPromise().await()

private fun opfsRootPromise(): kotlin.js.Promise<JsAny> = js("navigator.storage.getDirectory()")
private fun directoryHandlePromise(parent: JsAny, name: String, create: Boolean): kotlin.js.Promise<JsAny> =
    js("parent.getDirectoryHandle(name, { create: create })")
private fun fileHandlePromise(parent: JsAny, name: String, create: Boolean): kotlin.js.Promise<JsAny> =
    js("parent.getFileHandle(name, { create: create })")

internal suspend fun getOpfsRoot(): JsAny = opfsRootPromise().await()

internal suspend fun getDirectoryHandle(parent: JsAny, name: String, create: Boolean): JsAny =
    directoryHandlePromise(parent, name, create).await()

internal suspend fun getFileHandle(parent: JsAny, name: String, create: Boolean): JsAny =
    fileHandlePromise(parent, name, create).await()

private fun iteratorValues(handle: JsAny): JsAny = js("handle.values()")
private fun iteratorNext(iter: JsAny): kotlin.js.Promise<JsAny> = js("iter.next()")
private fun iterResultDone(result: JsAny): Boolean = js("result.done === true")
private fun iterResultValue(result: JsAny): JsAny = js("result.value")

internal suspend fun listOpfsEntries(dirHandle: JsAny): List<JsAny> {
    val entries = mutableListOf<JsAny>()
    val iterator = iteratorValues(dirHandle)
    while (true) {
        val next: JsAny = iteratorNext(iterator).await()
        if (iterResultDone(next)) break
        entries.add(iterResultValue(next))
    }
    return entries
}

internal fun getEntryName(entry: JsAny): String = js("entry.name")
internal fun isFileEntry(entry: JsAny): Boolean = js("entry.kind === 'file'")
internal fun isDirectoryEntry(entry: JsAny): Boolean = js("entry.kind === 'directory'")

private fun fileHandleGetFile(handle: JsAny): kotlin.js.Promise<JsAny> = js("handle.getFile()")
private fun fileText(file: JsAny): kotlin.js.Promise<JsAny> = js("file.text()")
private fun jsStringValue(v: JsAny): String = js("String(v)")

internal suspend fun readOpfsFile(fileHandle: JsAny): String? = try {
    val file: JsAny = fileHandleGetFile(fileHandle).await()
    jsStringValue(fileText(file).await())
} catch (e: Throwable) {
    null
}

private fun fileHandleCreateWritable(handle: JsAny): kotlin.js.Promise<JsAny> = js("handle.createWritable()")
private fun writableWrite(writable: JsAny, content: String): kotlin.js.Promise<JsAny> = js("writable.write(content)")
private fun writableWriteBuffer(writable: JsAny, buffer: JsAny): kotlin.js.Promise<JsAny> = js("writable.write(buffer)")
private fun writableClose(writable: JsAny): kotlin.js.Promise<JsAny> = js("writable.close()")
private fun dirRemoveEntry(dir: JsAny, name: String): kotlin.js.Promise<JsAny> = js("dir.removeEntry(name)")

internal suspend fun opfsWriteFile(path: String, content: String) {
    try {
        val root = getOpfsRoot()
        val parts = path.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts.dropLast(1)) {
            dir = getDirectoryHandle(dir, part, true)
        }
        val fileName = parts.last()
        val fileHandle = getFileHandle(dir, fileName, true)
        val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
        @Suppress("UNUSED_VARIABLE") val _write: JsAny = writableWrite(writable, content).await()
        @Suppress("UNUSED_VARIABLE") val _close: JsAny = writableClose(writable).await()
    } catch (e: Throwable) {
        println("[SteleKit] OPFS write failed for $path: ${e.message}")
    }
}

internal fun createObjectUrlFromFile(file: JsAny): String = js("URL.createObjectURL(file)")

internal suspend fun readOpfsFileAsObjectUrl(fileHandle: JsAny): String? = try {
    val file: JsAny = fileHandleGetFile(fileHandle).await()
    createObjectUrlFromFile(file)
} catch (e: Throwable) {
    null
}

internal suspend fun opfsWriteFileBytes(path: String, data: JsAny) {
    try {
        val root = getOpfsRoot()
        val parts = path.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts.dropLast(1)) {
            dir = getDirectoryHandle(dir, part, true)
        }
        val fileHandle = getFileHandle(dir, parts.last(), true)
        val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
        @Suppress("UNUSED_VARIABLE") val _write: JsAny = writableWriteBuffer(writable, data).await()
        @Suppress("UNUSED_VARIABLE") val _close: JsAny = writableClose(writable).await()
    } catch (e: Throwable) {
        println("[SteleKit] OPFS binary write failed for $path: ${e.message}")
        throw e
    }
}

internal suspend fun opfsDeleteFile(path: String) {
    try {
        val root = getOpfsRoot()
        val parts = path.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts.dropLast(1)) {
            dir = getDirectoryHandle(dir, part, false)
        }
        val fileName = parts.last()
        @Suppress("UNUSED_VARIABLE") val _remove: JsAny = dirRemoveEntry(dir, fileName).await()
    } catch (e: Throwable) {
        println("[SteleKit] OPFS delete failed for $path: ${e.message}")
    }
}

/**
 * Reads a single file at an absolute OPFS path (e.g. `.stele-dirty-set.json`'s marker file),
 * as opposed to [readOpfsFile] which takes an already-resolved file handle. Returns null if the
 * path doesn't exist or any step of directory/file resolution fails — callers rely on this for
 * crash-safe "absent or malformed marker" handling, so this must never throw.
 */
internal suspend fun opfsReadFileAtPath(path: String): String? = try {
    val root = getOpfsRoot()
    val parts = path.removePrefix("/").split("/")
    var dir: JsAny = root
    for (part in parts.dropLast(1)) {
        dir = getDirectoryHandle(dir, part, false)
    }
    val fileHandle = getFileHandle(dir, parts.last(), false)
    readOpfsFile(fileHandle)
} catch (e: Throwable) {
    null
}

private fun newJsByteArray(): JsAny = js("[]")
private fun pushJsByte(arr: JsAny, value: Int): Unit = js("arr.push(value)")
private fun jsByteArrayToBuffer(arr: JsAny): JsAny = js("new Uint8Array(arr).buffer")

/**
 * Converts a Kotlin [ByteArray] into the `ArrayBuffer`-shaped [JsAny] that [opfsWriteFileBytes]
 * expects. Marshals byte-by-byte via a JS array push loop — the same interop idiom already used
 * for SQLite bind params (`JsBindCollector`/`SqliteWorkerInterop`'s `jsArrayPush*` family) —
 * since Kotlin/Wasm has no direct typed-array boundary crossing for `ByteArray`. Acceptable for
 * markdown-page-sized paranoid-mode writes; not intended for large binary blobs.
 */
internal fun ByteArray.toJsArrayBuffer(): JsAny {
    val arr = newJsByteArray()
    for (b in this) pushJsByte(arr, b.toInt() and 0xFF)
    return jsByteArrayToBuffer(arr)
}

/**
 * Resolves to `null` the instant `document.visibilityState` becomes `"hidden"` (tab
 * backgrounded/closed) — used as a belt-and-suspenders best-effort marker flush per the Page
 * Lifecycle API's guidance that `visibilitychange` fires more reliably than `beforeunload`/
 * `pagehide` for this purpose. In environments with no `document` (e.g. some test runners) the
 * returned promise simply never resolves — a safe no-op, not a crash.
 */
internal fun jsVisibilityHiddenPromise(): kotlin.js.Promise<JsAny?> = js(
    """
    (function() {
        return new Promise(function(resolve) {
            if (typeof document === 'undefined' || typeof document.addEventListener !== 'function') {
                return;
            }
            function handler() {
                if (document.visibilityState === 'hidden') {
                    document.removeEventListener('visibilitychange', handler);
                    resolve(null);
                }
            }
            document.addEventListener('visibilitychange', handler);
        });
    })()
    """
)
