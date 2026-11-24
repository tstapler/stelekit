// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.annotation.AnnotationCanvas
import dev.stapler.stelekit.sketch.v2.annotation.DimensionAnnotationEngine
import dev.stapler.stelekit.sketch.v2.annotation.MeasureKind
import dev.stapler.stelekit.sketch.v2.annotation.PlacedDimension
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricUnit
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S
import kotlin.math.abs

/** Which shapes carry a persistent dimension annotation: only the selected ones, or every visible one. */
enum class DimensionScope { Selected, All }

/** The geometry types live below the UI so the exports draw the same annotations; these names keep the editor's code unchanged. */
typealias AnnotationPoint = dev.stapler.stelekit.sketch.v2.annotation.AnnotationPoint
typealias AnnotationSegment = dev.stapler.stelekit.sketch.v2.annotation.AnnotationSegment
typealias AnnotationRect = dev.stapler.stelekit.sketch.v2.annotation.AnnotationRect

/**
 * One persistent dimension drawn on a shape of a technical sheet, in dp: the dimension line with its two extension lines, the
 * value text's rectangle (axis-aligned, centred on the line) and the finger-sized [tapRect] that selects the shape and opens its
 * editor. [alpha] fades the annotation in as the zoom makes its text fit.
 */
data class DimensionAnnotation(
    val elementId: ElementId,
    val elementName: String,
    val kind: PillKind,
    val sixteenths: Long,
    val text: String,
    val line: AnnotationSegment,
    val extensions: List<AnnotationSegment>,
    val textRect: AnnotationRect,
    val tapRect: AnnotationRect,
    val alpha: Double,
    /** Which of the shape's annotations this is (a wall's segment, a room's area); empty for the usual one per kind. */
    val tag: String = "",
) {
    /** True for a closed room's floor-area label, which has no dimension line. */
    val isArea: Boolean get() = tag == DimensionAnnotationEngine.AREA_TAG
}

/**
 * The editor's view of the shared annotation engine ([DimensionAnnotationEngine], below the UI so SVG, PNG, previews and the CLI
 * run the same layout): it hands the viewport's canvas in and the pill kinds back. A function of the shapes, the viewport and the
 * canvas size.
 */
object DimensionAnnotationLayout {
    const val TEXT_SP = DimensionAnnotationEngine.TEXT_SP
    const val MAX_ANNOTATED_SHAPES = DimensionAnnotationEngine.MAX_ANNOTATED_SHAPES
    const val MAX_ROWS = DimensionAnnotationEngine.MAX_ROWS
    const val FADE_START_RATIO = DimensionAnnotationEngine.FADE_START_RATIO
    const val FADE_FULL_RATIO = DimensionAnnotationEngine.FADE_FULL_RATIO
    const val ATTEMPT_FACTOR = DimensionAnnotationEngine.ATTEMPT_FACTOR
    const val TAP_MIN_ALPHA = DimensionAnnotationEngine.TAP_MIN_ALPHA
    const val MIN_ALPHA = DimensionAnnotationEngine.MIN_ALPHA
    const val TEXT_GAP_DP = DimensionAnnotationEngine.TEXT_GAP_DP
    const val EXT_GAP_DP = DimensionAnnotationEngine.EXT_GAP_DP
    const val EXT_OVERSHOOT_DP = DimensionAnnotationEngine.EXT_OVERSHOOT_DP
    const val ROW_GAP_DP = DimensionAnnotationEngine.ROW_GAP_DP
    const val EDGE_DP = DimensionPillModel.EDGE_DP

    /** The size of the text rectangle for [text] at the user's font scale. */
    fun textSize(text: String, fontScale: Double): Pair<Double, Double> = DimensionAnnotationEngine.textSize(text, fontScale)

    /** The one place a value becomes text: the engine's [DimensionAnnotationEngine.valueText], the formatter the pills and size fields use. */
    fun valueText(kind: PillKind, sixteenths: Long, format: LengthFormat = LengthFormat.FEET_INCHES, exact: Double = sixteenths.toDouble()): String =
        DimensionAnnotationEngine.valueText(kind.measure(), sixteenths, format, exact)

    /**
     * The annotations of [candidates] on [canvas]. [bodies] are every visible shape (the candidates included): an annotation never
     * covers another shape. [reserved] are rectangles that stay free (the selected shape's pills); [textOnlyReserved] only keep text out (lines may cross). [tapMinDp] is the
     * touch floor for each annotation's [DimensionAnnotation.tapRect].
     */
    fun layout(
        candidates: List<SceneElement>,
        bodies: List<SceneElement>,
        canvas: PillCanvas,
        reserved: List<AnnotationRect>,
        fontScale: Double,
        tapMinDp: Double,
        format: LengthFormat = LengthFormat.FEET_INCHES,
        textOnlyReserved: List<AnnotationRect> = emptyList(),
    ): List<DimensionAnnotation> =
        DimensionAnnotationEngine.layout(
            candidates, bodies, AnnotationCanvas(canvas.toScreenDp, canvas.dpPerUnit, canvas.width, canvas.height),
            reserved, fontScale, tapMinDp, format, textOnlyReserved,
        ).map { it.toAnnotation() }

    /** "Width of Rectangle 1: 3 feet 7 inches" for a screen reader. */
    fun description(a: DimensionAnnotation): String = if (a.isArea) S.Annotation.areaDescription(a.elementName, a.text) else S.Annotation.description(DimensionPillModel.label(a.kind), a.elementName, SpokenLength.ofMetricText(a.text) ?: SpokenLength.of(a.sixteenths))

    private fun PlacedDimension.toAnnotation() = DimensionAnnotation(elementId, elementName, kind.pill(), sixteenths, text, line, extensions, textRect, tapRect, alpha, tag)
}

/** The pill kind and the engine's measure kind are the same five names. */
internal fun PillKind.measure(): MeasureKind = MeasureKind.valueOf(name)

internal fun MeasureKind.pill(): PillKind = PillKind.valueOf(name)

/** A length in words ("3 feet 7 and 3 eighths inches") for a screen reader; a scene unit is a sixteenth of an inch. */
object SpokenLength {
    /** A written metric value (`12.3 mm`, `Ø 1.2 m`) in words, or null when [text] is not metric. */
    fun ofMetricText(text: String): String? {
        val number = text.substringBeforeLast(' ', "").substringAfterLast(' ')
        val word = when (text.substringAfterLast(' ')) {
            MetricUnit.MM.suffix -> S.Units.SPOKEN_MM
            MetricUnit.CM.suffix -> S.Units.SPOKEN_CM
            MetricUnit.M.suffix -> S.Units.SPOKEN_M
            else -> return null
        }
        return S.Units.spokenMetric(number, word)
    }

    private const val SIXTEENTHS_PER_INCH = 16L
    private const val INCHES_PER_FOOT = 12L

    fun of(sixteenths: Long): String {
        val total = abs(sixteenths)
        val feet = total / (SIXTEENTHS_PER_INCH * INCHES_PER_FOOT)
        val rest = total % (SIXTEENTHS_PER_INCH * INCHES_PER_FOOT)
        val inches = rest / SIXTEENTHS_PER_INCH
        val frac = rest % SIXTEENTHS_PER_INCH
        val parts = ArrayList<String>()
        if (feet > 0) parts += "$feet ${if (feet == 1L) S.Spoken.FOOT else S.Spoken.FEET}"
        if (frac == 0L) {
            if (inches > 0 || feet == 0L) parts += "$inches ${if (inches == 1L) S.Spoken.INCH else S.Spoken.INCHES}"
        } else {
            val g = gcd(frac, SIXTEENTHS_PER_INCH)
            val n = frac / g
            val d = SIXTEENTHS_PER_INCH / g
            val fraction = "$n ${S.Spoken.denominator(d, plural = n > 1)}"
            parts += if (inches == 0L) "$fraction ${S.Spoken.OF_AN_INCH}" else "$inches ${S.Spoken.AND} $fraction ${S.Spoken.INCHES}"
        }
        return parts.joinToString(" ")
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
