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

/** U-E4-11: the re-encode after a rotation used to drop the lens-relevant tags; they must survive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifOrientationFixerLensTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val preserved = mapOf(
        ExifInterface.TAG_FOCAL_LENGTH to "56/10",
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "26",
        ExifInterface.TAG_MAKE to "Acme",
        ExifInterface.TAG_MODEL to "Phone 9",
        ExifInterface.TAG_DATETIME to "2026:11:14 10:20:30",
        ExifInterface.TAG_LENS_MODEL to "Acme 5.6mm f/1.8",
    )

    private fun writeJpeg(orientation: Int): File {
        val file = tempFolder.newFile("shot.jpg")
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        preserved.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.saveAttributes()
        return file
    }

    @Test
    fun fixOrientation_should_PreserveFocalLength_FocalLengthIn35mm_Make_Model_DateTime_LensModel_AfterReencode() {
        val file = writeJpeg(ExifInterface.ORIENTATION_ROTATE_90)
        assertIs<Either.Right<*>>(ExifOrientationFixer.fixOrientation(file.absolutePath))

        val after = ExifInterface(file.absolutePath)
        preserved.forEach { (tag, value) -> assertEquals(value, after.getAttribute(tag), "tag $tag") }
        assertEquals(ExifInterface.ORIENTATION_NORMAL, after.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test
    fun fixOrientation_should_LeaveTagsUntouched_When_NoRotationIsNeeded() {
        val file = writeJpeg(ExifInterface.ORIENTATION_NORMAL)
        ExifOrientationFixer.fixOrientation(file.absolutePath)
        val after = ExifInterface(file.absolutePath)
        preserved.forEach { (tag, value) -> assertEquals(value, after.getAttribute(tag), "tag $tag") }
    }
}
