package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation

/**
 * Bakes annotation overlays into an [ImageBitmap] and encodes the result as a JPEG [ByteArray].
 *
 * The exported image shows all committed [measurements] drawn over the [sourceImage],
 * exactly as they appear in [AnnotationEditorScreen].
 *
 * Platform-specific JPEG encoding is delegated to [ImageEncoder] (expect/actual).
 */
object AnnotationExporter {

    /** Font cell size at the reference width: a 7-row glyph is 14 px tall, as before. */
    private const val LABEL_CELL_PX = 2f

    /**
     * Draw [measurements] onto [sourceImage] and return the composited [ImageBitmap].
     *
     * The returned bitmap has the same dimensions as [sourceImage].
     */
    fun bakeAnnotations(
        sourceImage: ImageBitmap,
        measurements: List<MeasurementAnnotation>,
    ): ImageBitmap {
        val width = sourceImage.width
        val height = sourceImage.height
        val output = ImageBitmap(width, height)
        val canvas = Canvas(output)
        val paint = Paint()
        val canvasSize = IntSize(width, height)
        val scale = exportScale(width)

        // Draw source image
        canvas.drawImage(sourceImage, Offset.Zero, paint)

        // Draw each annotation
        val path = Path()
        measurements.forEach { annotation ->
            drawAnnotationOnCanvas(canvas, annotation, path, canvasSize, paint, scale)
        }

        return output
    }

    /**
     * Bake annotations into [sourceImage] and encode as JPEG bytes using [ImageEncoder].
     *
     * @param quality JPEG quality (0–100)
     * @param encode JPEG encoder; any Throwable it (or the bake) raises becomes [Either.Left].
     * @return [Either.Right] with the encoded bytes, or [Either.Left] with
     *   [DomainError.ExportError.EncodingFailed] if [ImageEncoder.encodeToJpeg] returned no bytes
     *   (its empty-[ByteArray] failure contract, unchanged on every platform).
     */
    fun bakeAndEncode(
        sourceImage: ImageBitmap,
        measurements: List<MeasurementAnnotation>,
        quality: Int = 90,
        encode: (ImageBitmap, Int) -> ByteArray = { bitmap, q -> ImageEncoder.encodeToJpeg(bitmap, q) },
    ): Either<DomainError.ExportError, ByteArray> = try {
        mapEncodeResult(encode(bakeAnnotations(sourceImage, measurements), quality))
    } catch (e: Throwable) {
        // OutOfMemoryError is an Error; on Android an uncaught one kills the process, so report it instead.
        DomainError.ExportError.EncodingFailed("Could not export this image: ${e.message ?: e::class.simpleName}").left()
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun drawAnnotationOnCanvas(
        canvas: Canvas,
        annotation: MeasurementAnnotation,
        path: Path,
        canvasSize: IntSize,
        paint: Paint,
        scale: Float,
    ) {
        val screenPoints = annotation.normalizedPoints.map {
            Offset(
                (it.x * canvasSize.width).toFloat(),
                (it.y * canvasSize.height).toFloat(),
            )
        }
        if (screenPoints.isEmpty()) return

        val color = annotationExportColor(annotation.annotationType)
        paint.color = color
        paint.strokeWidth = 3f * scale
        paint.style = PaintingStyle.Stroke

        when (annotation.annotationType) {
            AnnotationType.DISTANCE, AnnotationType.GRID_REF -> {
                if (screenPoints.size >= 2) {
                    canvas.drawLine(screenPoints[0], screenPoints[1], paint)
                    paint.style = PaintingStyle.Fill
                    canvas.drawCircle(screenPoints[0], 5f * scale, paint)
                    canvas.drawCircle(screenPoints[1], 5f * scale, paint)
                }
            }

            AnnotationType.AREA -> {
                if (screenPoints.size >= 3) {
                    path.reset()
                    path.moveTo(screenPoints[0].x, screenPoints[0].y)
                    screenPoints.drop(1).forEach { path.lineTo(it.x, it.y) }
                    path.close()
                    paint.style = PaintingStyle.Fill
                    paint.color = color.copy(alpha = 0.3f)
                    canvas.drawPath(path, paint)
                    paint.style = PaintingStyle.Stroke
                    paint.color = color
                    canvas.drawPath(path, paint)
                }
            }

            AnnotationType.ANGLE -> {
                if (screenPoints.size >= 3) {
                    canvas.drawLine(screenPoints[0], screenPoints[1], paint)
                    canvas.drawLine(screenPoints[1], screenPoints[2], paint)
                }
            }

            AnnotationType.LABEL -> {
                if (screenPoints.isNotEmpty()) {
                    paint.style = PaintingStyle.Fill
                    canvas.drawCircle(screenPoints[0], 6f * scale, paint)
                }
            }
        }

        // Draw value label if present
        val labelText = annotation.valueDisplay ?: annotation.label
        if (labelText != null && labelText.isNotBlank()) {
            val anchor = annotationExportMidpoint(screenPoints, annotation.annotationType)
            drawLabel(canvas, labelText, anchor, paint, scale)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, anchor: Offset, paint: Paint, scale: Float) {
        val cell = LABEL_CELL_PX * scale
        val padding = 4f * scale
        val textWidth = ExportLabelFont.textWidth(text, cell)
        val textHeight = ExportLabelFont.textHeight(cell)
        val centerY = anchor.y - 40f * scale
        val textLeft = anchor.x - textWidth / 2
        val textTop = centerY - textHeight / 2

        paint.color = Color(0xCC000000)
        paint.style = PaintingStyle.Fill
        canvas.drawRoundRect(
            textLeft - padding, textTop - padding, textLeft + textWidth + padding, textTop + textHeight + padding,
            4f * scale, 4f * scale, paint,
        )

        paint.color = Color.White
        ExportLabelFont.layout(text, textLeft, textTop, cell) { l, t, r, b -> canvas.drawRect(l, t, r, b, paint) }
    }

    private fun annotationExportColor(type: AnnotationType): Color = when (type) {
        AnnotationType.DISTANCE -> Color(0xFFE53935)
        AnnotationType.AREA -> Color(0xFF1E88E5)
        AnnotationType.ANGLE -> Color(0xFF43A047)
        AnnotationType.LABEL -> Color(0xFFFB8C00)
        AnnotationType.GRID_REF -> Color(0xFF8E24AA)
    }

    private fun annotationExportMidpoint(
        screenPoints: List<Offset>,
        type: AnnotationType,
    ): Offset {
        if (screenPoints.isEmpty()) return Offset.Zero
        return when (type) {
            AnnotationType.DISTANCE, AnnotationType.GRID_REF -> {
                if (screenPoints.size >= 2) {
                    Offset(
                        (screenPoints[0].x + screenPoints[1].x) / 2,
                        (screenPoints[0].y + screenPoints[1].y) / 2,
                    )
                } else screenPoints[0]
            }
            AnnotationType.AREA -> {
                Offset(
                    screenPoints.map { it.x }.average().toFloat(),
                    screenPoints.map { it.y }.average().toFloat(),
                )
            }
            AnnotationType.ANGLE -> if (screenPoints.size >= 2) screenPoints[1] else screenPoints[0]
            AnnotationType.LABEL -> screenPoints[0]
        }
    }
}

/**
 * Maps [ImageEncoder.encodeToJpeg]'s empty-[ByteArray] failure contract to
 * [DomainError.ExportError.EncodingFailed]. Pulled out of [AnnotationExporter.bakeAndEncode] so
 * the failure branch is testable directly (a real 0-byte encoder failure is hard to force through
 * the public `ImageBitmap`/`ImageEncoder` API on every platform — e.g. `ImageBitmap(0, 0)` itself
 * throws on construction rather than reaching the encoder).
 */
internal fun mapEncodeResult(bytes: ByteArray): Either<DomainError.ExportError, ByteArray> =
    if (bytes.isEmpty()) {
        DomainError.ExportError.EncodingFailed("JPEG encode returned no bytes").left()
    } else {
        bytes.right()
    }
