package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.InchesError
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength
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
}

/** Turns what the user typed into a sheet length, through the wiki length grammar. */
object DimensionInput {
    const val EXAMPLES = "12 3/16\", 3' 4\" or 40"

    private const val MAX_SIXTEENTHS = SketchScene.MAX_COORD_SIXTEENTHS

    fun parse(text: String): Either<DimensionInputError, Inches> {
        if (text.isBlank()) return DimensionInputError.EMPTY.left()
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
}
