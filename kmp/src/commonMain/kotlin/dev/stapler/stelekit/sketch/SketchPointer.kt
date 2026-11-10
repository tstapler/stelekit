package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Inches

/**
 * Turns a raw pointer position into a snapped sheet point: screen -> sheet through [view], then
 * [SnapEngine.snap] with the 12 dp hit tolerance converted to sheet inches.
 */
fun snapPointer(
    scene: SketchScene,
    view: ViewTransform,
    pointer: ScreenPoint,
    gridStep: Inches?,
    axisAnchor: SketchPoint? = null,
): Either<ArithmeticError, SnapResult> = either {
    val cursor = view.toSheet(pointer).bind()
    SnapEngine.snap(scene, cursor, view.hitToleranceInches().bind(), gridStep, axisAnchor).bind()
}

/** The primitive under a raw pointer position, within the 12 dp hit tolerance. */
fun hitPointer(scene: SketchScene, view: ViewTransform, pointer: ScreenPoint): Either<ArithmeticError, HitResult> =
    either {
        SnapEngine.hitTest(scene, view.toSheet(pointer).bind(), view.hitToleranceInches().bind()).bind()
    }
