package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Covers ADR-002's Base64 `ArrayBuffer` <-> `ByteArray` bridge via a real OPFS write/read round
 * trip in headless Chromium, including the 0x8000 `String.fromCharCode` chunk boundary and a
 * realistic 8 MB image-sized measurement (pre-mortem P1 #2's "no realistic-size gate" concern).
 */
class ByteBufferInteropTest {

    private fun uniqueGraphRoot(): String = "/stelekit-test/bytebuffer-${Random.nextInt(0, Int.MAX_VALUE)}"

    private suspend fun roundTrip(path: String, original: ByteArray): ByteArray {
        opfsWriteFileBytes(path, original.toJsUint8Array())
        val root = getOpfsRoot()
        val parts = path.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts.dropLast(1)) {
            dir = getDirectoryHandle(dir, part, false)
        }
        val fileHandle = getFileHandle(dir, parts.last(), false)
        return readOpfsFileBytes(fileHandle)
    }

    @Test
    fun byteBuffer_roundTripsEmptyByteArray_withoutThrowing() = runTest {
        val decoded = roundTrip("${uniqueGraphRoot()}/empty.bin", ByteArray(0))

        assertContentEquals(ByteArray(0), decoded)
    }

    @Test
    fun byteBuffer_roundTripsThroughOpfs_for70KbBufferCrossingChunkBoundary() = runTest {
        val original = ByteArray(70_000) { (it * 7 % 256).toByte() }

        val decoded = roundTrip("${uniqueGraphRoot()}/chunk-boundary.bin", original)

        assertContentEquals(original, decoded)
    }

    @Test
    fun byteBuffer_roundTripsRealisticSizeImage_underThreeSecondThreshold() = runTest {
        val original = ByteArray(8_000_000) { (it % 256).toByte() }

        val elapsed = measureTime {
            val decoded = roundTrip("${uniqueGraphRoot()}/realistic-size.bin", original)
            assertContentEquals(original, decoded)
        }

        assertTrue(
            elapsed.inWholeMilliseconds < 3000,
            "8MB OPFS round trip took ${elapsed.inWholeMilliseconds}ms, exceeding the 3s threshold " +
                "(project_plans/wasm-image-drop/implementation/plan.md Story 5.1.3)",
        )
    }
}
