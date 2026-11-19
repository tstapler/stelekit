// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** A point in scene units. */
data class ScenePoint(val x: SceneUnit, val y: SceneUnit)

enum class BindingMode { Inside, Orbit, Skip }

/** Arrow endpoint link; `fixedX`/`fixedY` are fractions of the target's box. Legacy focus/gap is converted on import. */
data class Binding(val elementId: ElementId, val fixedX: Double, val fixedY: Double, val mode: BindingMode = BindingMode.Orbit) {
    init {
        require(fixedX.isFinite() && fixedY.isFinite()) { "binding fixed point must be finite" }
    }
}

enum class BoundKind { Arrow, Text }

/** Reference from a container to a bound arrow or label. */
data class BoundRef(val id: ElementId, val kind: BoundKind)

enum class Arrowhead { None, Arrow, Bar, Circle, CircleOutline, Triangle, TriangleOutline, Diamond, DiamondOutline }

enum class TextAlign { Left, Center, Right }

enum class VerticalAlign { Top, Middle, Bottom }

/** Sealed element hierarchy; every `when` over it stays exhaustive. */
sealed interface SceneElement {
    val base: ElementBase
    val id: ElementId get() = base.id

    /** The same element with new shared fields. */
    fun withBase(base: ElementBase): SceneElement
}

/** Elements with an axis-aligned box and a rotation about its centre. */
sealed interface BoxElement : SceneElement {
    val x: SceneUnit
    val y: SceneUnit
    val width: SceneUnit
    val height: SceneUnit
    val angle: Double
}

data class RectElement(
    override val base: ElementBase,
    override val x: SceneUnit,
    override val y: SceneUnit,
    override val width: SceneUnit,
    override val height: SceneUnit,
    override val angle: Double = 0.0,
    val boundElements: List<BoundRef> = emptyList(),
) : BoxElement {
    init { requireFiniteAngle(angle) }
    override fun withBase(base: ElementBase) = copy(base = base)
}

data class DiamondElement(
    override val base: ElementBase,
    override val x: SceneUnit,
    override val y: SceneUnit,
    override val width: SceneUnit,
    override val height: SceneUnit,
    override val angle: Double = 0.0,
    val boundElements: List<BoundRef> = emptyList(),
) : BoxElement {
    init { requireFiniteAngle(angle) }
    override fun withBase(base: ElementBase) = copy(base = base)
}

/** A circle is an ellipse with equal radii ([isCircle]); only circles are constraint targets (OD-8). */
data class EllipseElement(
    override val base: ElementBase,
    val cx: SceneUnit,
    val cy: SceneUnit,
    val rx: SceneUnit,
    val ry: SceneUnit,
    val angle: Double = 0.0,
    val boundElements: List<BoundRef> = emptyList(),
) : SceneElement {
    init {
        requireFiniteAngle(angle)
        require(rx.value > 0.0 && ry.value > 0.0) { "ellipse radii must be positive" }
    }

    val isCircle: Boolean get() = rx == ry

    override fun withBase(base: ElementBase) = copy(base = base)

    companion object {
        /** Non-throwing construction: radii must be strictly positive. */
        fun create(base: ElementBase, cx: SceneUnit, cy: SceneUnit, rx: SceneUnit, ry: SceneUnit, angle: Double = 0.0): Either<ValueError, EllipseElement> = when {
            rx.value <= 0.0 -> ValueError.NotPositive(rx.value).left()
            ry.value <= 0.0 -> ValueError.NotPositive(ry.value).left()
            !angle.isFinite() -> ValueError.NonFinite(angle).left()
            else -> EllipseElement(base, cx, cy, rx, ry, angle).right()
        }
    }
}

data class LineElement(
    override val base: ElementBase,
    val points: List<ScenePoint>,
    val polygon: Boolean = false,
) : SceneElement {
    override fun withBase(base: ElementBase) = copy(base = base)
}

data class ArrowElement(
    override val base: ElementBase,
    val points: List<ScenePoint>,
    val startBinding: Binding? = null,
    val endBinding: Binding? = null,
    val startArrowhead: Arrowhead = Arrowhead.None,
    val endArrowhead: Arrowhead = Arrowhead.Arrow,
    val elbowed: Boolean = false,
) : SceneElement {
    override fun withBase(base: ElementBase) = copy(base = base)
}

/** The three arrow types of the style panel (FB-SP-14). Elbow is stored only: it draws as a polyline, no router (OD-7). */
enum class ArrowType { Sharp, Round, Elbow }

val ArrowElement.arrowType: ArrowType
    get() = when {
        elbowed -> ArrowType.Elbow
        base.style.roundness != null -> ArrowType.Round
        else -> ArrowType.Sharp
    }

/** The arrow as [type]: Round carries a roundness (a kept one wins), Sharp and Elbow clear it, Elbow sets the stored flag. */
fun ArrowElement.withArrowType(type: ArrowType): ArrowElement {
    val roundness = if (type == ArrowType.Round) base.style.roundness ?: Roundness(RoundnessKind.ProportionalRadius) else null
    return copy(base = base.copy(style = base.style.copy(roundness = roundness)), elbowed = type == ArrowType.Elbow)
}

data class FreedrawElement(
    override val base: ElementBase,
    val points: List<ScenePoint>,
    val pressures: List<Double> = emptyList(),
    val simulatePressure: Boolean = true,
) : SceneElement {
    init { require(pressures.all { it.isFinite() }) { "pressures must be finite" } }
    override fun withBase(base: ElementBase) = copy(base = base)
}

data class TextElement(
    override val base: ElementBase,
    override val x: SceneUnit,
    override val y: SceneUnit,
    override val width: SceneUnit,
    override val height: SceneUnit,
    override val angle: Double = 0.0,
    val text: String,
    val originalText: String = text,
    val fontSize: Double = DEFAULT_FONT_SIZE,
    val fontFamily: Int = 1,
    val textAlign: TextAlign = TextAlign.Left,
    val verticalAlign: VerticalAlign = VerticalAlign.Top,
    val containerId: ElementId? = null,
    val lineHeight: Double = DEFAULT_LINE_HEIGHT,
    val autoResize: Boolean = true,
) : BoxElement {
    init {
        requireFiniteAngle(angle)
        require(fontSize.isFinite() && lineHeight.isFinite()) { "font metrics must be finite" }
    }

    override fun withBase(base: ElementBase) = copy(base = base)

    companion object {
        const val DEFAULT_FONT_SIZE = 20.0
        const val DEFAULT_LINE_HEIGHT = 1.25
    }
}

/** Crop rectangle in image pixels. */
data class ImageCrop(val x: Double, val y: Double, val width: Double, val height: Double, val naturalWidth: Double, val naturalHeight: Double) {
    init {
        require(listOf(x, y, width, height, naturalWidth, naturalHeight).all { it.isFinite() }) { "crop must be finite" }
    }
}

data class ImageElement(
    override val base: ElementBase,
    override val x: SceneUnit,
    override val y: SceneUnit,
    override val width: SceneUnit,
    override val height: SceneUnit,
    override val angle: Double = 0.0,
    val fileId: String?,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: ImageCrop? = null,
) : BoxElement {
    init { requireFiniteAngle(angle) }
    override fun withBase(base: ElementBase) = copy(base = base)
}

/** Clipping frame; members point at it through `ElementBase.frameId`, its title is `ElementBase.name`. */
data class FrameElement(
    override val base: ElementBase,
    override val x: SceneUnit,
    override val y: SceneUnit,
    override val width: SceneUnit,
    override val height: SceneUnit,
    override val angle: Double = 0.0,
) : BoxElement {
    init { requireFiniteAngle(angle) }
    override fun withBase(base: ElementBase) = copy(base = base)
}

/** Technical-geometry free point; a constraint target (OD-8). */
data class PointElement(
    override val base: ElementBase,
    val x: SceneUnit,
    val y: SceneUnit,
) : SceneElement {
    override fun withBase(base: ElementBase) = copy(base = base)
}

/** Technical-geometry circular arc, angles in radians counter-clockwise from +x; a constraint target (OD-8). */
data class ArcElement(
    override val base: ElementBase,
    val cx: SceneUnit,
    val cy: SceneUnit,
    val radius: SceneUnit,
    val startAngle: Double,
    val endAngle: Double,
) : SceneElement {
    init {
        require(radius.value > 0.0) { "arc radius must be positive" }
        require(startAngle.isFinite() && endAngle.isFinite()) { "arc angles must be finite" }
    }

    override fun withBase(base: ElementBase) = copy(base = base)

    companion object {
        /** Non-throwing construction: finite angles and a strictly positive radius. */
        fun create(base: ElementBase, cx: SceneUnit, cy: SceneUnit, radius: SceneUnit, startAngle: Double, endAngle: Double): Either<ValueError, ArcElement> = when {
            radius.value <= 0.0 -> ValueError.NotPositive(radius.value).left()
            !startAngle.isFinite() -> ValueError.NonFinite(startAngle).left()
            !endAngle.isFinite() -> ValueError.NonFinite(endAngle).left()
            else -> ArcElement(base, cx, cy, radius, startAngle, endAngle).right()
        }
    }
}

private fun requireFiniteAngle(angle: Double) = require(angle.isFinite()) { "angle must be finite" }
