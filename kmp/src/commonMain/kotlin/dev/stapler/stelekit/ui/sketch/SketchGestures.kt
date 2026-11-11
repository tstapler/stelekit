package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.ScreenPoint
import dev.stapler.stelekit.sketch.ViewTransform
import dev.stapler.stelekit.sketch.hitPointer
import dev.stapler.stelekit.sketch.snapPointer
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.math.abs
import kotlin.math.roundToLong

/** What a tap means for the active tool. */
sealed interface TapAction {
    data class Place(val point: SketchPoint) : TapAction
    data class Select(val id: String?) : TapAction
}

/** The tool state machine's one decision: line and rectangle place a snapped point, zone-select picks the primitive under the tap. */
fun resolveTap(
    tool: SketchTool,
    scene: SketchScene,
    view: ViewTransform,
    screen: ScreenPoint,
    gridStep: Inches?,
    anchor: SketchPoint?,
): Either<ArithmeticError, TapAction> = either {
    when (tool) {
        SketchTool.ZONE_SELECT -> TapAction.Select(hitPointer(scene, view, screen).bind().primitive?.id)
        SketchTool.LINE, SketchTool.RECTANGLE -> TapAction.Place(snapPointer(scene, view, screen, gridStep, anchor).bind().point)
    }
}

/** Latches to "drag" once the pointer travels beyond the touch slop; a gesture that never latches is a tap. */
class TapOrDragClassifier(private val slopPx: Float) {
    var isDrag: Boolean = false
        private set

    fun update(dxPx: Float, dyPx: Float): Boolean {
        if (!isDrag && (abs(dxPx) > slopPx || abs(dyPx) > slopPx)) isDrag = true
        return isDrag
    }
}

private const val MILLI = 1_000L

/** Pointer pixels to exact dp. Floats exist only at this UI boundary; the transform stays rational. */
fun pxToDp(px: Float, density: Float): Rational = Rational((px / density * MILLI).roundToLong(), MILLI)

fun factorToRational(factor: Float): Rational = Rational((factor * 10 * MILLI).roundToLong().coerceAtLeast(1L), 10 * MILLI)

fun Rational.toFloat(): Float = num.toFloat() / den.toFloat()

fun Inches.toInchesFloat(): Float = sixteenths.toFloat() / Inches.SIXTEENTHS_PER_INCH.toFloat()

/** One-finger drag beyond the slop: the view follows the finger. Unchanged if the arithmetic overflows. */
fun applyDrag(view: ViewTransform, dxPx: Float, dyPx: Float, density: Float): ViewTransform =
    view.panBy(pxToDp(dxPx, density), pxToDp(dyPx, density)).getOrNull() ?: view

/** Two-pointer pan and zoom about the centroid. Unchanged if the arithmetic overflows. */
fun applyPinch(
    view: ViewTransform,
    panXPx: Float,
    panYPx: Float,
    zoomFactor: Float,
    centroidXPx: Float,
    centroidYPx: Float,
    density: Float,
): ViewTransform {
    val panned = applyDrag(view, panXPx, panYPx, density)
    val target = panned.zoom.multiply(factorToRational(zoomFactor)).getOrNull() ?: panned.zoom
    val anchor = ScreenPoint(pxToDp(centroidXPx, density), pxToDp(centroidYPx, density))
    return panned.zoomAt(anchor, target).getOrNull() ?: panned
}

/** Sheet-inches to pixels: `px = origin + inches * scale`. */
data class DrawFrame(val originX: Float, val originY: Float, val scale: Float) {
    fun px(inchesX: Float, inchesY: Float): Pair<Float, Float> = (originX + inchesX * scale) to (originY + inchesY * scale)

    /** A stroke of [dp] device-independent pixels, expressed in the sheet-inch units the paths are drawn in. */
    fun strokeInSheetUnits(dp: Float, density: Float): Float = dp * density / scale
}

fun drawFrameOf(view: ViewTransform, density: Float): DrawFrame =
    DrawFrame(view.origin.x.toFloat() * density, view.origin.y.toFloat() * density, view.dpPerInch.toFloat() * density)
