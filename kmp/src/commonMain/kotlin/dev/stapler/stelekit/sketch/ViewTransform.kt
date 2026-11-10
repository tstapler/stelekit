package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.roundToInches

/** A screen position in dp, kept exact so conversions never drift. */
data class ScreenPoint(val x: Rational, val y: Rational)

/**
 * Sheet to screen mapping: `screen = origin + sheetInches * dpPerInch`, with
 * `dpPerInch = BASE_DP_PER_INCH * zoom`. Everything is [Rational], so `toSheet(toScreen(p)) == p`
 * exactly for any [Inches] point at every zoom in [MIN_ZOOM]..[MAX_ZOOM].
 */
class ViewTransform private constructor(val zoom: Rational, val origin: ScreenPoint) {

    val dpPerInch: Rational
        get() = Rational(BASE_DP_PER_INCH).multiply(zoom).fold({ error("zoom out of range") }, { it })

    fun toScreen(p: SketchPoint): Either<ArithmeticError, ScreenPoint> = either {
        val scale = dpPerInch
        ScreenPoint(
            origin.x.add(inchesToRational(p.x).multiply(scale).bind()).bind(),
            origin.y.add(inchesToRational(p.y).multiply(scale).bind()).bind(),
        )
    }

    /** Rounds to the nearest sixteenth (half away from zero). */
    fun toSheet(s: ScreenPoint): Either<ArithmeticError, SketchPoint> = either {
        val scale = dpPerInch
        val x = s.x.subtract(origin.x).bind().divide(scale).bind().roundToInches().bind()
        val y = s.y.subtract(origin.y).bind().divide(scale).bind().roundToInches().bind()
        SketchPoint(x, y)
    }

    /** Sheet-space hit tolerance: 12 dp divided by the scale, in inches. */
    fun hitToleranceInches(): Either<ArithmeticError, Rational> =
        Rational(HIT_TOLERANCE_DP).divide(dpPerInch)

    fun panBy(dx: Rational, dy: Rational): Either<ArithmeticError, ViewTransform> = either {
        ViewTransform(zoom, ScreenPoint(origin.x.add(dx).bind(), origin.y.add(dy).bind()))
    }

    /** Zooms (clamped to the legal range) keeping the sheet point under [anchor] fixed. */
    fun zoomAt(anchor: ScreenPoint, newZoom: Rational): Either<ArithmeticError, ViewTransform> = either {
        val z = clampZoom(newZoom)
        val ratio = z.divide(zoom).bind()
        fun pivot(anchorV: Rational, originV: Rational): Rational =
            anchorV.subtract(anchorV.subtract(originV).bind().multiply(ratio).bind()).bind()
        ViewTransform(z, ScreenPoint(pivot(anchor.x, origin.x), pivot(anchor.y, origin.y)))
    }

    companion object {
        const val BASE_DP_PER_INCH: Long = 96L
        const val HIT_TOLERANCE_DP: Long = 12L
        val MIN_ZOOM = Rational(1, 10)
        val MAX_ZOOM = Rational(20)

        fun clampZoom(z: Rational): Rational = if (z < MIN_ZOOM) MIN_ZOOM else if (z > MAX_ZOOM) MAX_ZOOM else z

        fun of(zoom: Rational = Rational.ONE, origin: ScreenPoint = ScreenPoint(Rational.ZERO, Rational.ZERO)) =
            ViewTransform(clampZoom(zoom), origin)

        private fun inchesToRational(i: Inches): Rational = Rational(i.sixteenths, Inches.SIXTEENTHS_PER_INCH)
    }
}
