// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformImageDownscalerAndroidTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeJpeg(name: String, width: Int, height: Int, orientation: Int? = null): File {
        val file = tempFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 50, it) }
        bitmap.recycle()
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun dims(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun `20 MP source yields thumbnail and display within their edges and original is untouched`() {
        val file = writeJpeg("big.jpg", 5000, 4000)
        val source = file.readBytes()
        val before = source.copyOf()

        val out = PlatformImageDownscaler.downscale(source, listOf(DISPLAY_MAX_EDGE_PX, THUMBNAIL_MAX_EDGE_PX))

        assertNotNull(out)
        val (dw, dh) = dims(out[0]!!)
        val (tw, th) = dims(out[1]!!)
        assertEquals(DISPLAY_MAX_EDGE_PX, maxOf(dw, dh))
        assertEquals(THUMBNAIL_MAX_EDGE_PX, maxOf(tw, th))
        assertContentEquals(before, source)
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `small source produces no derivative`() {
        val out = PlatformImageDownscaler.downscale(writeJpeg("s.jpg", 200, 100).readBytes(), listOf(THUMBNAIL_MAX_EDGE_PX))
        assertNotNull(out)
        assertNull(out[0])
    }

    @Test
    fun `exif rotation is baked into the derivative`() {
        val bytes = writeJpeg("r.jpg", 2000, 1000, orientation = ExifInterface.ORIENTATION_ROTATE_90).readBytes()
        val out = PlatformImageDownscaler.downscale(bytes, listOf(DISPLAY_MAX_EDGE_PX))
        assertNotNull(out)
        assertEquals(800 to 1600, dims(out[0]!!))
    }

    @Test
    fun `undecodable input does not throw`() {
        // Robolectric's BitmapFactory shadow does not validate bytes, so only the no-throw contract
        // is asserted here; the real decoder returns null for garbage.
        PlatformImageDownscaler.downscale(ByteArray(64) { 3 }, listOf(THUMBNAIL_MAX_EDGE_PX))
    }
}
