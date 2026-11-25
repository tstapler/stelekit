// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchOutlineStrings as S
import dev.stapler.stelekit.units.LengthFormat

enum class ShapeAtKind(val label: String) {
    Rectangle(S.KIND_RECTANGLE),
    Ellipse(S.KIND_ELLIPSE),
    Diamond(S.KIND_DIAMOND),
}

enum class ShapeAtField(val label: String) { X(S.FIELD_X), Y(S.FIELD_Y), Width(S.FIELD_WIDTH), Height(S.FIELD_HEIGHT) }

/** What the "Add shape at..." form holds: the shape kind and the four typed fields, as text. */
data class ShapeAtForm(val kind: ShapeAtKind = ShapeAtKind.Rectangle, val x: String = "", val y: String = "", val width: String = "", val height: String = "") {
    fun text(field: ShapeAtField): String = when (field) {
        ShapeAtField.X -> x
        ShapeAtField.Y -> y
        ShapeAtField.Width -> width
        ShapeAtField.Height -> height
    }

    fun with(field: ShapeAtField, value: String): ShapeAtForm = when (field) {
        ShapeAtField.X -> copy(x = value)
        ShapeAtField.Y -> copy(y = value)
        ShapeAtField.Width -> copy(width = value)
        ShapeAtField.Height -> copy(height = value)
    }
}

/** A refused field and the words for it, ready to show beside that field. */
data class ShapeAtError(val field: ShapeAtField, val message: String)

/** A valid form: the shape to add and what is announced once it is placed. */
data class ShapeAtPlan(val command: AddElementCommand, val announcement: String)

/**
 * "Add shape at..." without a gesture (E12, WCAG 2.1.1): X, Y, Width and Height are typed in the sheet's unit (or any unit the
 * length grammar reads) and become one rectangle, ellipse or diamond whose top-left corner is at X, Y. On a plain diagram sheet a
 * bare number is a scene unit; on a technical sheet it is read by [DimensionInput] in [format]. X and Y may be zero or negative;
 * Width and Height must be more than zero. Pure: no scene write, so the host commits the plan through the ordinary tool path.
 */
object ShapeAtModel {
    fun plan(
        form: ShapeAtForm,
        technical: Boolean,
        format: LengthFormat,
        id: ElementId,
        style: ElementStyle,
        limits: SceneLimits = SceneLimits.Default,
    ): Either<ShapeAtError, ShapeAtPlan> {
        val values = HashMap<ShapeAtField, Double>()
        for (field in ShapeAtField.entries) {
            val positive = field == ShapeAtField.Width || field == ShapeAtField.Height
            val value = read(form.text(field), positive, technical, format, limits).fold({ return ShapeAtError(field, S.fieldError(field.label, it)).left() }, { it })
            values[field] = value
        }
        val (x, y, w, h) = listOf(ShapeAtField.X, ShapeAtField.Y, ShapeAtField.Width, ShapeAtField.Height).map { values.getValue(it) }
        val element = shape(form.kind, ElementBase(id, style = style), x, y, w, h)
            ?: return ShapeAtError(ShapeAtField.X, S.fieldError(ShapeAtField.X.label, S.OUT_OF_RANGE)).left()
        val spoken = ShapeAtField.entries.map { form.text(it).trim() }
        return ShapeAtPlan(AddElementCommand(element), S.added(form.kind.label, spoken[0], spoken[1], spoken[2], spoken[3])).right()
    }

    private fun read(text: String, positive: Boolean, technical: Boolean, format: LengthFormat, limits: SceneLimits): Either<String, Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return S.NOT_A_NUMBER.left()
        val negative = !positive && trimmed.startsWith("-")
        val body = if (negative) trimmed.removePrefix("-").trim() else trimmed
        val magnitude: Double = when {
            !positive && isZero(body) -> 0.0
            technical -> DimensionInput.interpretUnits(body, format).fold({ return it.message(format).left() }, { it.units })
            else -> body.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return S.NOT_A_NUMBER.left()
        }
        if (positive && magnitude <= 0.0) return S.NOT_POSITIVE.left()
        val signed = if (negative) -magnitude else magnitude
        if (signed > limits.maxCoord || signed < -limits.maxCoord) return S.OUT_OF_RANGE.left()
        return signed.right()
    }

    private const val HALF = 2.0

    private val ZERO = Regex("""^0+(\.0*)?\s*(mm|cm|m|"|')?$""")

    private fun isZero(text: String): Boolean = ZERO.matches(text)

    private fun shape(kind: ShapeAtKind, base: ElementBase, x: Double, y: Double, w: Double, h: Double): SceneElement? {
        val ux = SceneUnit.of(x).getOrNull() ?: return null
        val uy = SceneUnit.of(y).getOrNull() ?: return null
        val uw = SceneUnit.of(w).getOrNull() ?: return null
        val uh = SceneUnit.of(h).getOrNull() ?: return null
        return when (kind) {
            ShapeAtKind.Rectangle -> RectElement(base, ux, uy, uw, uh)
            ShapeAtKind.Diamond -> DiamondElement(base, ux, uy, uw, uh)
            ShapeAtKind.Ellipse -> {
                val rx = SceneUnit.of(w / HALF).getOrNull() ?: return null
                val ry = SceneUnit.of(h / HALF).getOrNull() ?: return null
                val cx = SceneUnit.of(x + w / HALF).getOrNull() ?: return null
                val cy = SceneUnit.of(y + h / HALF).getOrNull() ?: return null
                EllipseElement(base, cx, cy, rx, ry)
            }
        }
    }
}
