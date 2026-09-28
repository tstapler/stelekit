package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Real-OPFS coverage for the byte-write plumbing added for wasm-image-drop, run in headless
 * Chromium via `wasmJsBrowserTest`. Dedup (photo.jpg -> photo-1.jpg) is covered by
 * `WasmMediaAttachmentServiceTest` instead, via the shared `uniqueFileName`/`FileSystem`-cache
 * path `attachBytes` and `pickAndAttach` both actually use.
 */
class OpfsInteropTest {

    private fun uniqueDir(): String = "/stelekit-test/opfs-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun opfsWriteFileBytes_writesBytesReadableImmediatelyAfterAwait() = runTest {
        val dir = uniqueDir()
        val bytes = byteArrayOf(9, 8, 7, 6)
        val path = "$dir/photo.png"

        opfsWriteFileBytes(path, bytes.toJsUint8Array())

        var handle: JsAny = getOpfsRoot()
        for (part in dir.removePrefix("/").split("/")) {
            handle = getDirectoryHandle(handle, part, false)
        }
        val fileHandle = getFileHandle(handle, "photo.png", false)
        assertContentEquals(bytes, readOpfsFileBytes(fileHandle))
    }

    @Test
    fun opfsWriteFileBytes_propagatesThrowable_whenWritableRejects() = runTest {
        val dir = uniqueDir()

        assertFailsWith<Throwable> {
            opfsWriteFileBytes("$dir/", byteArrayOf(1).toJsUint8Array())
        }
    }
}
