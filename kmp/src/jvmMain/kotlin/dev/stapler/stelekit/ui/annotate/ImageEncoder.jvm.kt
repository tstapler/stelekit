package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * JVM (Desktop) JPEG encoder using [javax.imageio.ImageIO].
 */
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
        return try {
            val awtImage: java.awt.Image = bitmap.toAwtImage()
            // Convert to BufferedImage in RGB color space (JPEG does not support alpha)
            val buffered = BufferedImage(
                awtImage.getWidth(null),
                awtImage.getHeight(null),
                BufferedImage.TYPE_INT_RGB,
            )
            val g = buffered.createGraphics()
            g.drawImage(awtImage, 0, 0, null)
            g.dispose()

            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            if (!writers.hasNext()) return ByteArray(0)
            val writer = writers.next()
            val clamped = quality.coerceIn(0, 100)
            val params: ImageWriteParam = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = clamped / 100f
            }
            val out = ByteArrayOutputStream()
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(buffered, null, null), params)
            writer.dispose()
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}
