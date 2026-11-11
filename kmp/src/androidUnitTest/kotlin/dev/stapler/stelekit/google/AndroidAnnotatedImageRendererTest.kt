// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.platform.google.AndroidAnnotatedImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Export decode: EXIF orientation is honoured and an OutOfMemoryError becomes a Left, not a crash. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidAnnotatedImageRendererTest {
    private val annotation = ImageAnnotation(
        uuid = "img-1", blockUuid = "blk-1", pageUuid = "page-1", graphPath = "/graph", filePath = "/graph/p.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
    )

    private fun jpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File.createTempFile("orient", ".jpg")
        try {
            file.outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun render(bytes: ByteArray): Either<DomainError, ByteArray> {
        val fs = FakeFileSystem().apply { writeFileBytes(annotation.filePath, bytes) }
        return runBlocking { AndroidAnnotatedImageRenderer(fs).renderJpeg(annotation, emptyList()) }
    }

    private fun dimensions(jpeg: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun render_should_SwapAxes_When_OrientationIsRotate90() {
        val jpeg = assertIs<Either.Right<ByteArray>>(render(jpegWithOrientation(64, 48, ExifInterface.ORIENTATION_ROTATE_90))).value
        assertEquals(48 to 64, dimensions(jpeg))
    }

    @Test
    fun render_should_KeepAxes_When_OrientationIsNormalOrFlipped() {
        val normal = assertIs<Either.Right<ByteArray>>(render(jpegWithOrientation(64, 48, ExifInterface.ORIENTATION_NORMAL))).value
        val flipped = assertIs<Either.Right<ByteArray>>(render(jpegWithOrientation(64, 48, ExifInterface.ORIENTATION_FLIP_HORIZONTAL))).value
        assertEquals(64 to 48, dimensions(normal))
        assertEquals(64 to 48, dimensions(flipped))
    }

    @Test
    fun render_should_ReturnLeft_When_DecoderRunsOutOfMemory() {
        val fs = FakeFileSystem().apply { writeFileBytes(annotation.filePath, byteArrayOf(1)) }
        val renderer = AndroidAnnotatedImageRenderer(fs) { throw OutOfMemoryError("test") }

        val error = assertIs<Either.Left<DomainError>>(runBlocking { renderer.renderJpeg(annotation, emptyList()) }).value

        assertTrue(error.message.contains("test"), error.message)
    }
}
