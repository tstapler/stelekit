// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.sketch.v2.image.ImageFormat
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageStoreError
import dev.stapler.stelekit.sketch.v2.runtime.image.StoredImage
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.util.ContentHasher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4.3: bytes stored once under assets by SHA-256, bounded by SceneLimits, header read before anything is written. */
class ImageAssetStoreTest {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun png(w: Int, h: Int, padding: Int = 0): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52) + be32(w) + be32(h) +
            byteArrayOf(8, 6, 0, 0, 0) + ByteArray(padding) { it.toByte() }

    private val fs = FakeFileSystem()
    private val store = ImageAssetStore(fs, "/graph/assets/")

    private fun stored(r: Either<ImageStoreError, StoredImage>) = (r as Either.Right).value

    @Test
    fun store_should_WriteOnceUnderAssetsBySha256_And_DedupeTheSecondCall() {
        val bytes = png(100, 50, padding = 1_200)
        val first = stored(store.store(bytes))
        assertEquals(ContentHasher.sha256(bytes), first.fileId)
        assertEquals("/graph/assets/${first.fileId}.png", first.path)
        assertFalse(first.alreadyStored)
        assertContentEquals(bytes, fs.readFileBytes(first.path))

        val second = stored(store.store(bytes))
        assertEquals(first.fileId, second.fileId)
        assertTrue(second.alreadyStored)
        assertEquals(1, fs.listFiles("/graph/assets").size)
    }

    @Test
    fun read_should_ReturnStoredBytes_And_ExistsShouldDriveThePlaceholder() {
        val bytes = png(8, 8)
        val id = stored(store.store(bytes)).fileId
        assertTrue(store.exists(id))
        assertContentEquals(bytes, store.read(id))
        assertFalse(store.exists("0".repeat(64)))
        assertNull(store.read("../../etc/passwd"))
        assertFalse(store.exists("not-a-hash"))
    }

    @Test
    fun store_should_RefuseOverMaxBytes_WithTheStatedCap() {
        val small = ImageAssetStore(fs, "/g/assets", SceneLimits(maxImageBytes = 100))
        val e = assertIs<ImageStoreError.TooLarge>((small.store(png(1, 1, padding = 200)) as Either.Left).value)
        assertEquals(100L, e.maxBytes)
        assertEquals(0, fs.listFiles("/g/assets").size)
    }

    @Test
    fun store_should_RefuseOverMaxPixels_FromTheHeader_BeforeWriting() {
        val side = 4_001
        val e = assertIs<ImageStoreError.TooManyPixels>((store.store(png(side, side)) as Either.Left).value)
        assertEquals(SceneLimits.Default.maxImagePixels, e.maxPixels)
        assertEquals(0, fs.listFiles("/graph/assets").size)
        val wide = assertIs<ImageStoreError.TooManyPixels>((store.store(png(SceneLimits.Default.maxImageSidePx + 1, 1)) as Either.Left).value)
        assertEquals(SceneLimits.Default.maxImageSidePx, wide.maxSidePx)
    }

    @Test
    fun store_should_AcceptExactlyMaxPixels() {
        val exact = ImageAssetStore(fs, "/x/assets", SceneLimits(maxImagePixels = 64))
        assertTrue(exact.store(png(8, 8)) is Either.Right)
        assertTrue(exact.store(png(8, 9)) is Either.Left)
    }

    @Test
    fun store_should_RefuseUnsupportedAndDamagedFiles() {
        assertEquals(ImageStoreError.Unsupported, (store.store("<svg/>".encodeToByteArray()) as Either.Left).value)
        assertEquals(ImageStoreError.Unreadable, (store.store(png(2, 2).copyOf(10)) as Either.Left).value)
        assertEquals(ImageFormat.Png, stored(store.store(png(2, 2))).header.format)
    }

    @Test
    fun errorMessages_should_StateTheCap() {
        assertEquals("Image too large (max 60 MB)", ImageStoreError.TooLarge(61L * 1_048_576, SceneLimits.Default.maxImageBytes).message)
    }
}
