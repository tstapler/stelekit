package dev.stapler.stelekit.platform

import kotlinx.coroutines.await

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

private fun fileArrayBuffer(file: JsAny): kotlin.js.Promise<JsAny> = js("file.arrayBuffer()")

/** Reads a file's raw bytes back out of OPFS, for tests and callers that need binary content. */
internal suspend fun readOpfsFileBytes(fileHandle: JsAny): ByteArray {
    val file: JsAny = fileHandleGetFile(fileHandle).await()
    val buffer: JsAny = fileArrayBuffer(file).await()
    return jsArrayBufferToByteArray(buffer)
}

private fun fileHandleCreateWritable(handle: JsAny): kotlin.js.Promise<JsAny> = js("handle.createWritable()")
private fun writableWrite(writable: JsAny, content: String): kotlin.js.Promise<JsAny> = js("writable.write(content)")
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

private fun writableWriteBytes(writable: JsAny, bytes: JsAny): kotlin.js.Promise<JsAny> =
    js("writable.write(bytes)")

/**
 * Writes [bytes] to OPFS at [path], creating parent directories as needed.
 *
 * Unlike [opfsWriteFile], this does not catch/swallow failures — a rejected write promise
 * propagates as a thrown [Throwable] so the caller (e.g. `WasmMediaAttachmentService`) can
 * convert it into a proper `Either.Left` instead of silently losing the attachment.
 */
internal suspend fun opfsWriteFileBytes(path: String, bytes: ByteArray) {
    val root = getOpfsRoot()
    val parts = path.removePrefix("/").split("/")
    var dir: JsAny = root
    for (part in parts.dropLast(1)) {
        dir = getDirectoryHandle(dir, part, true)
    }
    val fileName = parts.last()
    val fileHandle = getFileHandle(dir, fileName, true)
    val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
    @Suppress("UNUSED_VARIABLE") val _write: JsAny = writableWriteBytes(writable, bytes.toJsUint8Array()).await()
    @Suppress("UNUSED_VARIABLE") val _close: JsAny = writableClose(writable).await()
}

/** Probes live OPFS state for [fileName] under [dirPath], creating parent directories as needed. */
internal suspend fun opfsFileExists(dirPath: String, fileName: String): Boolean = try {
    val root = getOpfsRoot()
    var dir: JsAny = root
    for (part in dirPath.removePrefix("/").split("/")) {
        dir = getDirectoryHandle(dir, part, true)
    }
    getFileHandle(dir, fileName, false)
    true
} catch (e: Throwable) {
    false
}

/**
 * Async sibling of [dev.stapler.stelekit.service.uniqueFileName] that probes live OPFS state
 * instead of a synchronous `okio.FileSystem` — dedup suffix counter (photo.jpg → photo-1.jpg).
 */
internal suspend fun uniqueOpfsFileName(dirPath: String, stem: String, ext: String): String {
    val safeStem = dev.stapler.stelekit.service.sanitizeFileNameComponent(stem, fallback = "attachment")
    val safeExt = dev.stapler.stelekit.service.sanitizeFileNameComponent(ext, fallback = "")
    val base = if (safeExt.isBlank()) safeStem else "$safeStem.$safeExt"
    if (!opfsFileExists(dirPath, base)) return base
    var counter = 1
    while (true) {
        val candidate = if (safeExt.isBlank()) "$safeStem-$counter" else "$safeStem-$counter.$safeExt"
        if (!opfsFileExists(dirPath, candidate)) return candidate
        counter++
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
