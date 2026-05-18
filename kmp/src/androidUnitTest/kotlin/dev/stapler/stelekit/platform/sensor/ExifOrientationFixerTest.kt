package dev.stapler.stelekit.platform.sensor

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import arrow.core.Either
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Regression tests for [ExifOrientationFixer] verifying that Samsung-style EXIF
 * orientation tags (written at capture time) are baked into pixel data and reset to NORMAL.
 *
 * Uses Robolectric to run Android Bitmap / ExifInterface APIs on the JVM.
 *
 * Orientations tested:
 * - 1 (NORMAL)     — no rotation applied, file copied/left unchanged
 * - 3 (ROTATE 180) — bitmap rotated 180°
 * - 6 (ROTATE 90)  — typical Samsung portrait-mode capture, rotated 90° CW
 * - 8 (ROTATE 270) — landscape-flipped capture, rotated 90° CCW
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifOrientationFixerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Write a minimal 10×10 JPEG to [file] and inject the given EXIF [orientation].
     *
     * Uses [Bitmap.compress] (Robolectric provides a shadow that produces a real JPEG
     * in the temp filesystem) then sets the orientation tag via [ExifInterface].
     */
    private fun writeJpegWithOrientation(file: File, orientation: Int) {
        // Create a 10×10 RGB bitmap — small enough that decoding is fast.
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        // Inject the desired EXIF orientation tag.
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
    }

    /** Read the EXIF orientation tag from [file]. Returns the integer value. */
    private fun readOrientation(file: File): Int {
        val exif = ExifInterface(file.absolutePath)
        return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }

    // ── Orientation 1 (NORMAL): no transformation needed ─────────────────────

    @Test
    fun `orientation NORMAL returns success and leaves file unchanged`() {
        val input = tempFolder.newFile("normal.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_NORMAL)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        assertEquals(input.absolutePath, result.value.outputPath)
        // Tag should still be NORMAL (or absent) after a no-op pass.
        val orientationAfter = readOrientation(input)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationAfter)
    }

    // ── Orientation 3 (ROTATE_180): 180° rotation ────────────────────────────

    @Test
    fun `orientation ROTATE_180 fixes file and resets tag to NORMAL`() {
        val input = tempFolder.newFile("rotate180.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_ROTATE_180)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        // After fixing, the tag must be reset to NORMAL (= 1).
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "Orientation tag must be reset to NORMAL after rotation correction")
    }

    // ── Orientation 6 (ROTATE_90): Samsung portrait capture ──────────────────

    @Test
    fun `orientation ROTATE_90 fixes file and resets tag to NORMAL`() {
        val input = tempFolder.newFile("rotate90.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_ROTATE_90)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "Orientation tag must be reset to NORMAL after 90° CW correction")
    }

    // ── Orientation 8 (ROTATE_270): 90° CCW / landscape-flipped capture ──────

    @Test
    fun `orientation ROTATE_270 fixes file and resets tag to NORMAL`() {
        val input = tempFolder.newFile("rotate270.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_ROTATE_270)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "Orientation tag must be reset to NORMAL after 90° CCW correction")
    }

    // ── Output path override ──────────────────────────────────────────────────

    @Test
    fun `explicit outputPath writes corrected image to separate file`() {
        val input = tempFolder.newFile("input.jpg")
        val output = tempFolder.newFile("output.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_ROTATE_90)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath, output.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        assertEquals(output.absolutePath, result.value.outputPath)
        // Original file should be untouched.
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, readOrientation(input))
        // Output file should have NORMAL orientation.
        assertEquals(ExifInterface.ORIENTATION_NORMAL, readOrientation(output))
    }

    // ── EXIF metadata extraction ──────────────────────────────────────────────

    @Test
    fun `fixOrientation extracts focal length when present`() {
        val input = tempFolder.newFile("focal.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_NORMAL)

        // Inject a focal length rational value (e.g. 4.25mm expressed as "4250/1000").
        val exif = ExifInterface(input.absolutePath)
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "4250/1000")
        exif.saveAttributes()

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        val focal = result.value.focalLengthMm
        assert(focal != null && focal > 4.0 && focal < 5.0) {
            "Expected focal length ~4.25mm but got $focal"
        }
    }

    @Test
    fun `fixOrientation returns null focal length when EXIF tag absent`() {
        val input = tempFolder.newFile("nofocal.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_NORMAL)
        // No focal length tag injected.

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        assertNull(result.value.focalLengthMm)
    }

    // ── Orientation 7 (TRANSVERSE): Samsung edge case — 270° + horizontal flip ─

    /**
     * ORIENTATION_TRANSVERSE (value = 7) is the Samsung edge-case orientation:
     * rotate 270° then flip horizontally. This must produce a corrected JPEG
     * with the orientation tag reset to NORMAL.
     *
     * Regression test for the Samsung Galaxy capture bug where TRANSVERSE images
     * appear mirrored and sideways in consumers that ignore EXIF tags.
     */
    @Test
    fun `orientation TRANSVERSE (Samsung edge case) fixes file and resets tag to NORMAL`() {
        val input = tempFolder.newFile("transverse.jpg")
        writeJpegWithOrientation(input, ExifInterface.ORIENTATION_TRANSVERSE)

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "ORIENTATION_TRANSVERSE must be baked in and tag reset to NORMAL"
        )
    }

    // ── Landscape capture with incorrect ROTATE_90 tag ────────────────────────

    /**
     * Simulates a landscape photo (wider than tall) that has an incorrect ROTATE_90
     * EXIF orientation tag — common on some Android OEMs that write the sensor-native
     * portrait orientation even for landscape captures.
     *
     * After fixing, the output file must have ORIENTATION_NORMAL so that downstream
     * consumers (annotation canvas, Coil) display the image correctly.
     */
    @Test
    fun `landscape image with incorrect ROTATE_90 tag is corrected and tag reset`() {
        // Create a landscape bitmap: wider (20px) than tall (10px).
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val input = tempFolder.newFile("landscape_wrong_tag.jpg")
        FileOutputStream(input).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        // Tag incorrectly as ROTATE_90 (portrait capture tag applied to a landscape file).
        val exif = ExifInterface(input.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "Landscape image with incorrect ROTATE_90 tag must be corrected to NORMAL"
        )
    }

    // ── Google Pixel portrait: ORIENTATION_NORMAL — no spurious rotation ──────

    /**
     * Google Pixel phones capture portrait images with ORIENTATION_NORMAL (tag = 1)
     * because the sensor is mounted in portrait orientation. No rotation must be
     * applied; the pixel data must not be modified.
     *
     * Regression test ensuring [ExifOrientationFixer] never spuriously rotates a
     * file that is already correctly oriented.
     */
    @Test
    fun `Google Pixel portrait (ORIENTATION_NORMAL) is not spuriously rotated`() {
        // Create a portrait bitmap: taller (20px) than wide (10px).
        val bitmap = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)
        val input = tempFolder.newFile("pixel_portrait.jpg")
        FileOutputStream(input).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        // Pixel sets ORIENTATION_NORMAL — correct as-is.
        val exif = ExifInterface(input.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.saveAttributes()

        val originalSize = input.length()

        val result = ExifOrientationFixer.fixOrientation(input.absolutePath)

        assertIs<Either.Right<ExifOrientationFixer.FixResult>>(result)
        // Tag must remain NORMAL.
        val orientationAfter = readOrientation(File(result.value.outputPath))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL, orientationAfter,
            "Google Pixel portrait with ORIENTATION_NORMAL must not be rotated"
        )
        // The file should not have been re-encoded (in-place no-op path).
        assertEquals(
            originalSize, input.length(),
            "No-op path must not re-encode the file (file size must be unchanged)"
        )
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    fun `fixOrientation returns CaptureFailed for non-existent file`() {
        val result = ExifOrientationFixer.fixOrientation("/tmp/does_not_exist_abc123.jpg")
        assertIs<Either.Left<dev.stapler.stelekit.error.DomainError.SensorError.CaptureFailed>>(result)
    }
}
