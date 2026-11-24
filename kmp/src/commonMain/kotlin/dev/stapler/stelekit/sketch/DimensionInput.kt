package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.flatMap
import arrow.core.right
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.InchesError
import dev.stapler.stelekit.units.MetricLength
import dev.stapler.stelekit.units.MetricParse
import dev.stapler.stelekit.units.MetricUnit
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength
import dev.stapler.stelekit.units.roundToInches
import dev.stapler.stelekit.units.toInches

enum class DimensionInputError(val reason: String) {
    EMPTY("Type a length."),
    NOT_A_LENGTH("That is not a length."),
    NOT_A_SINGLE_LENGTH("Use one length, not a range or a list."),
    NOT_WHOLE_SIXTEENTH("Lengths snap to a sixteenth of an inch."),
    NOT_POSITIVE("A length must be more than zero."),
    TOO_LARGE("That length is too large."),
    ;

    /** Inline text for the field: the reason plus a working example. */
    val message: String get() = "$reason Try ${DimensionInput.EXAMPLES}."

    /** [message] with examples in the unit the sheet shows: metric examples first in a metric display. */
    fun message(format: LengthFormat): String =
        if (MetricLength.isMetric(format)) "$reason Try ${DimensionInput.METRIC_EXAMPLES}." else message
}

/**
 * A typed length ready to commit. [roundedFrom] is the written text when the sheet's sixteenth-inch resolution could not hold it
 * exactly and [inches] is the nearest sixteenth; null when the value is exact.
 */
data class TypedLength(val inches: Inches, val roundedFrom: String? = null) {
    val wasRounded: Boolean get() = roundedFrom != null
}

/**
 * A typed length in scene units. [units] is a whole number of sixteenths for an inch length and may be fractional for a metric one
 * (typed to 0.1 mm); [metricTenthsMm] is set when the text was metric. [roundedFrom] is the written text when it had to be rounded.
 */
data class TypedUnits(val units: Double, val roundedFrom: String? = null, val metricTenthsMm: Long? = null) {
    val wasRounded: Boolean get() = roundedFrom != null

    /** The nearest whole sixteenth, halves up: what a sixteenth-only consumer would store. */
    val nearestInches: Inches get() = Inches(kotlin.math.floor(units + HALF).toLong())

    private companion object {
        const val HALF = 0.5
    }
}

/** Turns what the user typed into a sheet length, through the wiki length grammar. */
object DimensionInput {
    const val EXAMPLES = "12 3/16\", 3' 4\" or 40"
    const val METRIC_EXAMPLES = "25 mm, 1.2 m or 3' 4\""

    private const val MAX_SIXTEENTHS = SketchScene.MAX_COORD_SIXTEENTHS

    fun parse(text: String): Either<DimensionInputError, Inches> {
        if (text.isBlank()) return DimensionInputError.EMPTY.left()
        // Metric text is exact only when it is how the app writes a whole sixteenth (`1.6 mm` for 1/16"); anything else needs interpret.
        metricReading(text, LengthFormat.FEET_INCHES, null)?.let { read ->
            return read.flatMap { reading ->
                val nearest = nearestSixteenth(reading)
                if (MetricLength.tenthsOfSixteenths(nearest.sixteenths) == reading.tenthsMm && nearest.sixteenths in 1..MAX_SIXTEENTHS) nearest.right()
                else DimensionInputError.NOT_WHOLE_SIXTEENTH.left()
            }
        }
        val parsed = parseLength(text).getOrNull() ?: return DimensionInputError.NOT_A_LENGTH.left()
        val measured = parsed as? ParsedLength.Measured ?: return DimensionInputError.NOT_A_SINGLE_LENGTH.left()
        val inches = measured.value.toInches().fold(
            { e -> return (if (e is InchesError.NotRepresentable) DimensionInputError.NOT_WHOLE_SIXTEENTH else DimensionInputError.TOO_LARGE).left() },
            { it },
        )
        return when {
            inches.sixteenths <= 0L -> DimensionInputError.NOT_POSITIVE.left()
            inches.sixteenths > MAX_SIXTEENTHS -> DimensionInputError.TOO_LARGE.left()
            else -> inches.right()
        }
    }

    /**
     * Like [parse], but a length between two sixteenths (`0.3"`, `37.9"`) is rounded to the nearest sixteenth and reported in
     * [TypedLength.roundedFrom] so the caller can say so, instead of being refused. A value that rounds to zero stays an error.
     * Never applies a grid or neighbour snap: the result is the typed value or its nearest sixteenth, nothing else.
     */
    fun interpret(text: String): Either<DimensionInputError, TypedLength> = interpret(text, LengthFormat.FEET_INCHES)

    private fun interpretInches(text: String): Either<DimensionInputError, TypedLength> {
        when (val exact = parse(text)) {
            is Either.Right -> return TypedLength(exact.value).right()
            is Either.Left -> if (exact.value != DimensionInputError.NOT_WHOLE_SIXTEENTH) return exact.value.left()
        }
        val measured = parseLength(text).getOrNull() as? ParsedLength.Measured ?: return DimensionInputError.NOT_A_LENGTH.left()
        val rounded = measured.value.roundToInches().getOrNull() ?: return DimensionInputError.TOO_LARGE.left()
        return when {
            rounded.sixteenths <= 0L -> DimensionInputError.NOT_POSITIVE.left()
            rounded.sixteenths > MAX_SIXTEENTHS -> DimensionInputError.TOO_LARGE.left()
            else -> TypedLength(rounded, text.trim()).right()
        }
    }

    /** `5/16"` or `3' 7 3/4"`: the rounded length as shown in the "Rounded to" notice, always the form that re-parses to the same value. */
    fun roundedNoticeText(inches: Inches): String = LengthFormatter.format(inches, LengthFormat.FEET_INCHES)

    /** In a metric display the notice leads with the stored metric value and keeps the inch spelling in brackets: `25.4 mm (1")`. */
    fun roundedNoticeText(inches: Inches, format: LengthFormat): String =
        if (MetricLength.isMetric(format)) "${LengthFormatter.format(inches, format)} (${roundedNoticeText(inches)})" else roundedNoticeText(inches)

    /**
     * Like [interpret] for a store that only holds whole sixteenths, in a display of [format]: metric text (`25 mm`, `1,5 cm`, or a
     * bare number in the display's unit) is read to 0.1 mm and then rounded to the nearest sixteenth, and [TypedLength.roundedFrom]
     * says so whenever that changed the value.
     */
    fun interpret(text: String, format: LengthFormat, bare: MetricUnit? = null): Either<DimensionInputError, TypedLength> {
        val metric = metricReading(text, format, bare) ?: return interpretInches(text)
        return metric.flatMap { reading ->
            val rounded = nearestSixteenth(reading)
            when {
                rounded.sixteenths <= 0L -> DimensionInputError.NOT_POSITIVE.left()
                rounded.sixteenths > MAX_SIXTEENTHS -> DimensionInputError.TOO_LARGE.left()
                MetricLength.tenthsOfSixteenths(rounded.sixteenths) == reading.tenthsMm -> TypedLength(rounded).right()
                else -> TypedLength(rounded, text.trim()).right()
            }
        }
    }

    /**
     * The typed length in scene units for a store that holds fractional units (the v2 scene does: a `Double` per coordinate). Inch
     * text behaves as [interpret], rounded to the nearest sixteenth; metric text is kept exact to 0.1 mm and only text finer than
     * that is rounded. [bare] overrides the unit a plain number means (the unit typed in an earlier field of the same edit).
     */
    fun interpretUnits(text: String, format: LengthFormat, bare: MetricUnit? = null): Either<DimensionInputError, TypedUnits> {
        val metric = metricReading(text, format, bare)
            ?: return interpretInches(text).map { TypedUnits(it.inches.sixteenths.toDouble(), it.roundedFrom) }
        return metric.map { TypedUnits(MetricLength.toUnits(it.tenthsMm), if (it.rounded) text.trim() else null, it.tenthsMm) }
    }

    private fun nearestSixteenth(reading: dev.stapler.stelekit.units.MetricReading): Inches =
        Inches(kotlin.math.floor(MetricLength.toUnits(reading.tenthsMm) + 0.5).toLong())

    /** Null when [text] is not metric (the caller reads it as inches); a reading, or the reason it cannot be one. */
    private fun metricReading(text: String, format: LengthFormat, bare: MetricUnit?): Either<DimensionInputError, dev.stapler.stelekit.units.MetricReading>? =
        when (val m = MetricLength.parse(text, bare ?: MetricLength.bareUnitOf(format))) {
            MetricParse.NotMetric -> null
            MetricParse.TooLarge -> DimensionInputError.TOO_LARGE.left()
            is MetricParse.Ok -> if (m.reading.tenthsMm <= 0L) DimensionInputError.NOT_POSITIVE.left() else m.reading.right()
        }
}
