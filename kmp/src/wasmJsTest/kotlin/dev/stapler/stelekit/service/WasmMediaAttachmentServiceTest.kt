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
}
