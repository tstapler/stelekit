package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.units.Inches
import kotlin.math.abs

/** Which vertex of which primitive a non-drag move targets; [index] is zero-based. */
data class VertexRef(val primitiveId: String, val index: Int)

fun SketchScene.vertexAt(ref: VertexRef): SketchPoint? =
    primitives.firstOrNull { it.id == ref.primitiveId }?.vertices?.getOrNull(ref.index)

private const val SIXTEENTHS_PER_FOOT = 192L
private const val SIXTEENTHS_PER_INCH = 16L

/** A length as TalkBack should read it: `1 ft 3 and 3/16 in`, not the written `1' 3 3/16"`. */
fun spokenLength(sixteenths: Long): String {
    val total = abs(sixteenths)
    val feet = total / SIXTEENTHS_PER_FOOT
    val inches = total % SIXTEENTHS_PER_FOOT / SIXTEENTHS_PER_INCH
    val fraction = total % SIXTEENTHS_PER_INCH
    val inchText = when {
        fraction == 0L -> "$inches in"
        inches == 0L -> "$fraction/16 in"
        else -> "$inches and $fraction/16 in"
    }
    return when {
        feet == 0L -> inchText
        inches == 0L && fraction == 0L -> "$feet ft"
        else -> "$feet ft $inchText"
    }
}

private fun Inches.spoken(): String = (if (sixteenths < 0) "minus " else "") + spokenLength(sixteenths)

private fun SketchPoint.spoken(): String = "x ${x.spoken()}, y ${y.spoken()}"

/** One spoken sentence per primitive, each carrying its value (a length, or its end points when slanted). */
fun SketchPrimitive.describe(position: Int): String = when (this) {
    is SketchPrimitive.Line ->
        if (a.x == b.x || a.y == b.y) {
            "Line $position, ${spokenLength((b.x.sixteenths - a.x.sixteenths) + (b.y.sixteenths - a.y.sixteenths))}"
        } else {
            "Line $position from ${a.spoken()} to ${b.spoken()}"
        }
    is SketchPrimitive.Rect ->
        "Rectangle $position, ${spokenLength(b.x.sixteenths - a.x.sixteenths)} by ${spokenLength(b.y.sixteenths - a.y.sixteenths)}"
}

fun SketchScene.describe(): String =
    if (primitives.isEmpty()) "Empty sheet" else primitives.mapIndexed { i, p -> p.describe(i + 1) }.joinToString(". ")

/** TalkBack action label; vertices are numbered from 1. */
fun moveVertexLabel(index: Int): String = "Move vertex ${index + 1}"

fun NudgeDirection.label(): String = when (this) {
    NudgeDirection.LEFT -> "left"
    NudgeDirection.RIGHT -> "right"
    NudgeDirection.UP -> "up"
    NudgeDirection.DOWN -> "down"
}

fun NudgeStep.label(): String = when (this) {
    NudgeStep.ONE_PIXEL -> "1 pixel"
    NudgeStep.TEN_PIXELS -> "10 pixels"
    NudgeStep.ONE_PERCENT -> "1 percent"
}

fun nudgeDescription(direction: NudgeDirection, step: NudgeStep): String = "Nudge ${direction.label()}, ${step.label()} step"

/** Canvas semantics: a description of every shape, plus "Move vertex N" for the selected shape. */
fun Modifier.sketchSemantics(state: SketchUiState, onMoveVertex: (VertexRef) -> Unit): Modifier = semantics {
    contentDescription = state.document.scene.describe()
    val selected = state.document.scene.primitives.firstOrNull { it.id == state.selectedId }
    customActions = selected?.vertices?.indices?.map { index ->
        CustomAccessibilityAction(moveVertexLabel(index)) {
            onMoveVertex(VertexRef(selected.id, index))
            true
        }
    }.orEmpty()
}
