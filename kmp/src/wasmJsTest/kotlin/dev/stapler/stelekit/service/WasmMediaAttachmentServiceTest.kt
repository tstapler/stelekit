package dev.stapler.stelekit.service

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors FolderSyncSettingsMoveDestinationPickerTest.kt's established idiom for this codebase).
// Monkeypatches FileSystemFileHandle.prototype.createWritable to force a rejected Promise, so
// opfsWriteFileBytes throws without touching real OPFS write behavior for any other test.
private fun monkeypatchCreateWritableToReject(): JsAny? = js(
    """
    (function() {
        var original = FileSystemFileHandle.prototype.createWritable;
        FileSystemFileHandle.prototype.createWritable = function() {
            return Promise.reject(new Error('simulated OPFS write failure'));
        };
        return original || null;
    })()
    """,
)

private fun restoreCreateWritable(original: JsAny?): Unit = js(
    "(function() { FileSystemFileHandle.prototype.createWritable = original; })()",
)

// Rejects at the write() step instead of createWritable() — createWritable() must succeed so a
// real writable stream is obtained first, otherwise opfsWriteFileBytes's abort-on-failure path
// (OpfsInterop.kt's `if (handle != null) writableAbort(handle)`) is never reached, since `writable`
// is only assigned after createWritable() resolves.
private fun monkeypatchWritableWriteToReject(): JsAny? = js(
    """
    (function() {
        var original = FileSystemWritableFileStream.prototype.write;
        FileSystemWritableFileStream.prototype.write = function() {
            return Promise.reject(new Error('simulated OPFS write failure'));
        };
        return original || null;
    })()
    """,
)

private fun restoreWritableWrite(original: JsAny?): Unit = js(
    "(function() { FileSystemWritableFileStream.prototype.write = original; })()",
)

// Spies on abort() (records a call, then delegates) so the write-failure test can assert the
// OPFS lock-leak fix actually ran, not just that the operation failed.
private fun spyOnWritableAbort(): JsAny? = js(
    """
    (function() {
        var original = FileSystemWritableFileStream.prototype.abort;
        globalThis.__stelekitWritableAbortCalled = false;
        FileSystemWritableFileStream.prototype.abort = function() {
            globalThis.__stelekitWritableAbortCalled = true;
            return original ? original.call(this) : Promise.resolve();
        };
        return original || null;
    })()
    """,
)

private fun restoreWritableAbort(original: JsAny?): Unit = js(
    "(function() { FileSystemWritableFileStream.prototype.abort = original; })()",
)

private fun wasWritableAbortCalled(): Boolean = js("!!globalThis.__stelekitWritableAbortCalled")

/**
 * Real-OPFS round trip for [WasmMediaAttachmentService], run in headless Chromium via
 * `wasmJsBrowserTest` (see WasmBenchmarkTest.kt's doc comment — real browser APIs, not a mock).
 */
class WasmMediaAttachmentServiceTest {

    private fun uniqueGraphRoot(): String = "/stelekit-test/${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newService(): WasmMediaAttachmentService {
        val service = WasmMediaAttachmentService(PlatformFileSystem())
        service.attachNotificationManager(NotificationManager())
        return service
    }

    @Test
    fun attachBytes_writesFileToOpfsAssetsDir_forFreshName() = runTest {
        val service = newService()
        val graphRoot = uniqueGraphRoot()

        val result = service.attachBytes(byteArrayOf(1, 2, 3, 4), "test.png", graphRoot)

        val attachment = assertNotNull(result.fold({ null }, { it }), "expected Either.Right, got $result")
        assertEquals("../assets/test.png", attachment.relativePath)
        assertEquals("test.png", attachment.displayName)
    }

    @Test
    fun attachBytes_appliesDashOneSuffix_whenNameAlreadyExists() = runTest {
        val service = newService()
        val graphRoot = uniqueGraphRoot()

        service.attachBytes(byteArrayOf(1, 2), "dup.png", graphRoot)
        val second = service.attachBytes(byteArrayOf(3, 4), "dup.png", graphRoot)

        val attachment = assertNotNull(second.fold({ null }, { it }), "expected Either.Right, got $second")
        assertEquals("../assets/dup-1.png", attachment.relativePath)
        assertEquals("dup-1.png", attachment.displayName)
    }

    @Test
    fun attachBytes_returnsCopyFailedLeft_whenOpfsWriteThrows() = runTest {
        val service = newService()
        val graphRoot = uniqueGraphRoot()

        val original = monkeypatchCreateWritableToReject()
        val result = try {
            service.attachBytes(byteArrayOf(1, 2, 3), "fail.png", graphRoot)
        } finally {
            restoreCreateWritable(original)
        }

        val error = assertNotNull(result.fold({ it }, { null }), "expected Either.Left, got $result")
        assertIs<DomainError.AttachmentError.CopyFailed>(error)
    }

    @Test
    fun attachBytes_abortsWritableStream_whenWriteFailsAfterCreatingWritable() = runTest {
        // Rejects write() (not createWritable()) so a real writable stream exists first, and spies
        // on abort() — this is the one path the test above can't cover: createWritable() succeeding
        // then write() failing is exactly the case opfsWriteFileBytes's writableAbort() call exists
        // for (PR #361 Gate-2 review, MAJOR 4: an un-aborted stream leaks OPFS's exclusive file lock).
        val service = newService()
        val graphRoot = uniqueGraphRoot()

        val originalWrite = monkeypatchWritableWriteToReject()
        val originalAbort = spyOnWritableAbort()
        val result = try {
            service.attachBytes(byteArrayOf(1, 2, 3), "fail-write.png", graphRoot)
        } finally {
            restoreWritableWrite(originalWrite)
            restoreWritableAbort(originalAbort)
        }

        val error = assertNotNull(result.fold({ it }, { null }), "expected Either.Left, got $result")
        assertIs<DomainError.AttachmentError.CopyFailed>(error)
        assertEquals(true, wasWritableAbortCalled(), "expected writable.abort() to be called after write() rejected")
    }
}
