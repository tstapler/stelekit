package dev.stapler.stelekit.service

import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Real-OPFS round trip for [WasmMediaAttachmentService], run in headless Chromium via
 * `wasmJsBrowserTest` (see WasmBenchmarkTest.kt's doc comment — real browser APIs, not a mock).
 */
class WasmMediaAttachmentServiceTest {

    private fun uniqueGraphRoot(): String = "/stelekit-test/${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun attachBytes_writesFileToOpfsAssetsDir_forFreshName() = runTest {
        val service = WasmMediaAttachmentService(NotificationManager())
        val graphRoot = uniqueGraphRoot()

        val result = service.attachBytes(byteArrayOf(1, 2, 3, 4), "test.png", graphRoot)

        val attachment = assertNotNull(result.fold({ null }, { it }), "expected Either.Right, got $result")
        assertEquals("../assets/test.png", attachment.relativePath)
        assertEquals("test.png", attachment.displayName)
    }

    @Test
    fun attachBytes_appliesDashOneSuffix_whenNameAlreadyExists() = runTest {
        val service = WasmMediaAttachmentService(NotificationManager())
        val graphRoot = uniqueGraphRoot()

        service.attachBytes(byteArrayOf(1, 2), "dup.png", graphRoot)
        val second = service.attachBytes(byteArrayOf(3, 4), "dup.png", graphRoot)

        val attachment = assertNotNull(second.fold({ null }, { it }), "expected Either.Right, got $second")
        assertEquals("../assets/dup-1.png", attachment.relativePath)
        assertEquals("dup-1.png", attachment.displayName)
    }
}
