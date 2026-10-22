// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageTiersTest {
    private val graph = EffectiveNotesPath("/g")
    private val original = "/g/assets/images/2026-01-01-abcd1234.jpg"

    @Test
    fun resolverFallsBackThumbnailThenDisplayThenOriginal() {
        val both = ImageDerivativeSet("/t", "/d")
        assertEquals("/t", ImageTierResolver.pick(ImageTier.THUMBNAIL, original, both))
        assertEquals("/d", ImageTierResolver.pick(ImageTier.THUMBNAIL, original, ImageDerivativeSet(null, "/d")))
        assertEquals(original, ImageTierResolver.pick(ImageTier.THUMBNAIL, original, ImageDerivativeSet.NONE))
        assertEquals("/d", ImageTierResolver.pick(ImageTier.DISPLAY, original, both))
        assertEquals(original, ImageTierResolver.pick(ImageTier.DISPLAY, original, ImageDerivativeSet("/t", null)))
        assertEquals(original, ImageTierResolver.pick(ImageTier.ORIGINAL, original, both))
    }

    @Test
    fun derivativePathsAreDeterministicDistinctAndOutsideAssets() {
        val thumb = ImageDerivativePaths.pathFor(graph, original, ImageTier.THUMBNAIL)!!
        val display = ImageDerivativePaths.pathFor(graph, original, ImageTier.DISPLAY)!!
        assertEquals(thumb, ImageDerivativePaths.pathFor(graph, original, ImageTier.THUMBNAIL))
        assertNotEquals(thumb, display)
        assertTrue(thumb.startsWith("/g/.stelekit/cache/images/") && thumb.endsWith(".thumb.jpg"), thumb)
        assertTrue(display.endsWith(".display.jpg"), display)
        assertNotEquals(thumb, ImageDerivativePaths.pathFor(graph, "$original.2", ImageTier.THUMBNAIL))
        assertNull(ImageDerivativePaths.pathFor(graph, original, ImageTier.ORIGINAL))
    }

    @Test
    fun localOriginalPathAcceptsAssetsAndRejectsTraversalAndRemote() {
        assertEquals("/g/assets/images/a.jpg", ImageTierResolver.localOriginalPath("../assets/images/a.jpg", graph))
        assertNull(ImageTierResolver.localOriginalPath("../assets/../secret.jpg", graph))
        assertNull(ImageTierResolver.localOriginalPath("../assets/%2e%2e/x.jpg", graph))
        assertNull(ImageTierResolver.localOriginalPath("../assets/a.jpg", null))
        assertNull(ImageTierResolver.localOriginalPath("https://example.com/a.jpg", graph))
        assertEquals("/abs/a.jpg", ImageTierResolver.localOriginalPath("/abs/a.jpg", graph))
        assertEquals("saf://x/a.jpg", ImageTierResolver.localOriginalPath("saf://x/a.jpg", graph))
    }

    @Test
    fun sampleSizeKeepsDecodeAtLeastTargetAndWithinBudget() {
        // 50 MP: edge 1600 allows 1/4 (2040x1530 >= 1600), well under the 16 MP budget.
        assertEquals(4, inSampleSizeForEdge(8160, 6120, DISPLAY_MAX_EDGE_PX))
        assertEquals(16, inSampleSizeForEdge(8160, 6120, THUMBNAIL_MAX_EDGE_PX))
        assertEquals(1, inSampleSizeForEdge(1000, 800, DISPLAY_MAX_EDGE_PX))
        assertEquals(1, inSampleSizeForEdge(0, 0, DISPLAY_MAX_EDGE_PX))
        // Budget wins when the target edge alone would allow a bigger decode.
        assertTrue(inSampleSizeForEdge(8160, 6120, 8000) == 2)
    }

    @Test
    fun exifOrientationIsReadFromJpegAndDefaultsToOne() {
        assertEquals(6, ExifOrientationReader.read(jpegWithOrientation(6, little = true)))
        assertEquals(8, ExifOrientationReader.read(jpegWithOrientation(8, little = false)))
        assertEquals(1, ExifOrientationReader.read(byteArrayOf(1, 2, 3)))
        assertEquals(1, ExifOrientationReader.read(jpegWithOrientation(6, little = true).copyOf(14)))
        assertEquals(OrientationTransform(90, false), ExifOrientationReader.transformFor(6))
        assertTrue(ExifOrientationReader.transformFor(1).isIdentity)
    }

    private fun jpegWithOrientation(value: Int, little: Boolean): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (little) u16(v) + u16(0) else u16(0) + u16(v)
        val tiff = (if (little) "II".encodeToByteArray() else "MM".encodeToByteArray()) +
            u16(42) + u32(8) + u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(value) + u16(0) + u32(0)
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), (len shr 8).toByte(), len.toByte()) +
            payload + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private class CountingDownscaler(private val behaviour: (Int) -> List<ByteArray?>?) : ImageDownscalerPort {
        var calls = 0
        var bytesDecoded = 0L
        override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
            calls++
            bytesDecoded += source.size
            return behaviour(calls)
        }
    }

    private fun fsWithOriginal(): FakeFileSystem = FakeFileSystem().also { it.writeFileBytes(original, ByteArray(100) { 7 }) }

    @Test
    fun ensureGeneratesBothTiersOnceAndIsIdempotent() = runTest {
        val fs = fsWithOriginal()
        val originalBytes = fs.readFileBytes(original)!!.copyOf()
        val scaler = CountingDownscaler { listOf(byteArrayOf(1, 1), byteArrayOf(2)) }
        val service = ImageDerivativeService(scaler, Dispatchers.Unconfined)

        val first = service.ensure(fs, graph, original)
        assertEquals(ImageDerivativePaths.pathFor(graph, original, ImageTier.THUMBNAIL), first.thumbnail)
        assertEquals(ImageDerivativePaths.pathFor(graph, original, ImageTier.DISPLAY), first.display)
        // wanted order is [DISPLAY, THUMBNAIL]
        assertEquals(listOf<Byte>(1, 1), fs.readFileBytes(first.display!!)!!.toList())
        assertEquals(listOf<Byte>(2), fs.readFileBytes(first.thumbnail!!)!!.toList())

        val second = service.ensure(fs, graph, original)
        assertEquals(first, second)
        assertEquals(1, scaler.calls, "second call must not re-decode")
        assertEquals(originalBytes.toList(), fs.readFileBytes(original)!!.toList(), "original untouched")
    }

    @Test
    fun failureOrUnsupportedPlatformFallsBackToOriginalAndIsNotRetried() = runTest {
        val fs = fsWithOriginal()
        val throwing = CountingDownscaler { throw OutOfMemoryError("simulated") }
        val service = ImageDerivativeService(throwing, Dispatchers.Unconfined)
        val result = service.ensure(fs, graph, original)
        assertEquals(ImageDerivativeSet.NONE, result)
        assertEquals(original, ImageTierResolver.pick(ImageTier.THUMBNAIL, original, result))
        service.ensure(fs, graph, original)
        assertEquals(1, throwing.calls, "a failed image is not retried within the session")

        val unsupported = CountingDownscaler { null }
        assertEquals(ImageDerivativeSet.NONE, ImageDerivativeService(unsupported, Dispatchers.Unconfined).ensure(fs, graph, original))
    }

    @Test
    fun partialResultsKeepWhatSucceeded() = runTest {
        val fs = fsWithOriginal()
        val scaler = CountingDownscaler { listOf(null, byteArrayOf(9)) } // display skipped (source small), thumb ok
        val result = ImageDerivativeService(scaler, Dispatchers.Unconfined).ensure(fs, graph, original)
        assertNull(result.display)
        assertNotNull(result.thumbnail)
        assertEquals(result.thumbnail, ImageTierResolver.pick(ImageTier.THUMBNAIL, original, result))
        assertEquals(original, ImageTierResolver.pick(ImageTier.DISPLAY, original, result))
    }

    @Test
    fun existingDoesNotDecode() = runTest {
        val fs = fsWithOriginal()
        val scaler = CountingDownscaler { listOf(byteArrayOf(1), byteArrayOf(2)) }
        val service = ImageDerivativeService(scaler, Dispatchers.Unconfined)
        assertEquals(ImageDerivativeSet.NONE, service.existing(fs, graph, original))
        assertEquals(0, scaler.calls)
    }
}
