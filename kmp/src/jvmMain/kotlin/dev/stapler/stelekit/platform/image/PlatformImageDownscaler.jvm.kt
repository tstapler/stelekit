// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageWriteParam

actual object PlatformImageDownscaler : ImageDownscalerPort {
    override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? = try {
        decode(source, maxEdges, quality)
    } catch (_: Throwable) {
        null
    }

    private fun decode(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(source)) ?: return null
        input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val targets = maxEdges.map { edge -> if (maxOf(width, height) > edge) edge else null }
                val largest = targets.filterNotNull().maxOrNull() ?: return targets.map { null }
                val sample = inSampleSizeForEdge(width, height, largest)
                val param: ImageReadParam = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val decoded = reader.read(0, param)
                val transform = ExifOrientationReader.transformFor(ExifOrientationReader.read(source))
                return targets.map { edge -> edge?.let { encodeScaled(decoded, width, height, it, transform, quality) } }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun encodeScaled(
        decoded: BufferedImage,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int,
        transform: OrientationTransform,
        quality: Int,
    ): ByteArray? = try {
        val (tw, th) = capLongestEdge(fullWidth, fullHeight, maxEdge)
        val swap = transform.rotateDegrees == 90 || transform.rotateDegrees == 270
        val ow = if (swap) th else tw
        val oh = if (swap) tw else th
        val out = BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB)
        val at = AffineTransform()
        // Rotate about the output centre, then mirror, matching the Android matrix order.
        at.translate(ow / 2.0, oh / 2.0)
        if (transform.flipHorizontal) at.scale(-1.0, 1.0)
        at.rotate(Math.toRadians(transform.rotateDegrees.toDouble()))
        at.translate(-tw / 2.0, -th / 2.0)
        at.scale(tw.toDouble() / decoded.width, th.toDouble() / decoded.height)
        // AffineTransformOp, not Graphics2D: it needs no display, so it works headless.
        AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR).filter(decoded, out)
        encodeJpeg(out, quality)
    } catch (_: Throwable) {
        null
    }

    private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        return try {
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0, 100) / 100f
            }
            val bytes = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(bytes).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), params)
            }
            bytes.toByteArray()
        } finally {
            writer.dispose()
        }
    }
}
