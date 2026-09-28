package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-OPFS coverage for the byte-write/dedup plumbing added for wasm-image-drop, run in
 * headless Chromium via `wasmJsBrowserTest`.
 */
class OpfsInteropTest {

    private fun uniqueDir(): String = "/stelekit-test/opfs-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun opfsWriteFileBytes_writesBytesReadableImmediatelyAfterAwait() = runTest {
        val dir = uniqueDir()
        val bytes = byteArrayOf(9, 8, 7, 6)

        opfsWriteFileBytes("$dir/photo.png", bytes)

        assertTrue(opfsFileExists(dir, "photo.png"))
    }

    @Test
    fun opfsWriteFileBytes_propagatesThrowable_whenWritableRejects() = runTest {
        val dir = uniqueDir()

        assertFailsWith<Throwable> {
            opfsWriteFileBytes("$dir/", byteArrayOf(1))
        }
    }

    @Test
    fun uniqueOpfsFileName_returnsBaseName_whenNoExistingFile() = runTest {
        val dir = uniqueDir()

        val name = uniqueOpfsFileName(dir, "photo", "png")

        assertEquals("photo.png", name)
    }

    @Test
    fun uniqueOpfsFileName_returnsDashTwoSuffix_whenBaseAndDashOneExist() = runTest {
        val dir = uniqueDir()
        opfsWriteFileBytes("$dir/photo.png", byteArrayOf(1))
        opfsWriteFileBytes("$dir/photo-1.png", byteArrayOf(2))

        val name = uniqueOpfsFileName(dir, "photo", "png")

        assertEquals("photo-2.png", name)
    }
}
