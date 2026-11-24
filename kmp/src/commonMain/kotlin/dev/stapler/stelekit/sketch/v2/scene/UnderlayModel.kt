// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The photo or plan underlay (epic E5): one [ImageElement] with the id [ELEMENT] on the layer [LAYER]. It is a plain image
 * element, so every reader that knows images reads an old sheet and a new one alike; what makes it an underlay is the layer,
 * and what makes it calibrated is [UnderlayMeta] in the element's `extras`. The image is an asset by content hash (ADR-001).
 */
object UnderlayIds {
    val LAYER = LayerId("underlay")
    val ELEMENT = ElementId("underlay")
    const val LAYER_NAME = "Underlay"
    const val ELEMENT_NAME = "Underlay photo"
    const val EXTRAS_KEY = "underlay"

    /** Layer opacity of a freshly placed underlay: faint enough to trace over. */
    const val DEFAULT_OPACITY = 0.5
}

/** A point in the underlay's own pixels: origin top-left of the displayed (EXIF-oriented) image, u right, v down. */
data class ImagePx(val u: Double, val v: Double) {
    init {
        require(u.isFinite() && v.isFinite()) { "image point must be finite" }
    }
}

/** A known length: [a] to [b] on the photo is [lengthUnits] scene units (sixteenths of an inch) in the world. */
data class CalibrationRef(val a: ImagePx, val b: ImagePx, val lengthUnits: Long) {
    init {
        require(lengthUnits > 0L) { "a reference length must be positive" }
    }

    val pixelLength: Double get() = hypot(b.u - a.u, b.v - a.v)
}

/** What the sheet remembers about the underlay beyond the image element: its pixel size and the references it was scaled by. */
data class UnderlayMeta(val widthPx: Int, val heightPx: Int, val refs: List<CalibrationRef> = emptyList()) {
    init {
        require(widthPx > 0 && heightPx > 0) { "underlay pixel size must be positive" }
    }
}

/** Reads and writes [UnderlayMeta] in an element's `extras` (`{"underlay":{"px":[w,h],"refs":[{"a":[u,v],"b":[u,v],"len":n}]}}`). */
object UnderlayMetaCodec {
    private const val PX = "px"
    private const val REFS = "refs"
    private const val A = "a"
    private const val B = "b"
    private const val LEN = "len"
    private const val PAIR = 2

    /** The meta, or null when [extras] holds none or it is malformed (a reader never fails on it). */
    fun read(extras: JsonObject): UnderlayMeta? {
        val root = extras[UnderlayIds.EXTRAS_KEY] as? JsonObject ?: return null
        val px = root[PX] as? JsonArray ?: return null
        if (px.size != PAIR) return null
        val w = (px[0] as? JsonPrimitive)?.intOrNull ?: return null
        val h = (px[1] as? JsonPrimitive)?.intOrNull ?: return null
        if (w <= 0 || h <= 0) return null
        val refs = (root[REFS] as? JsonArray)?.map { readRef(it) ?: return null }.orEmpty()
        return UnderlayMeta(w, h, refs)
    }

    private fun readRef(e: kotlinx.serialization.json.JsonElement): CalibrationRef? {
        val o = e as? JsonObject ?: return null
        val a = point(o[A]) ?: return null
        val b = point(o[B]) ?: return null
        val len = (o[LEN] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        return if (len > 0L) CalibrationRef(a, b, len) else null
    }

    private fun point(e: kotlinx.serialization.json.JsonElement?): ImagePx? {
        val arr = e as? JsonArray ?: return null
        if (arr.size != PAIR) return null
        val u = (arr[0] as? JsonPrimitive)?.let(CanonicalJson::doubleOf) ?: return null
        val v = (arr[1] as? JsonPrimitive)?.let(CanonicalJson::doubleOf) ?: return null
        return if (u.isFinite() && v.isFinite()) ImagePx(u, v) else null
    }

    private fun num(d: Double): JsonPrimitive = CanonicalJson.number(d).getOrNull() ?: JsonPrimitive(0)

    private fun whole(v: Int): JsonPrimitive = JsonPrimitive(v)

    /** [extras] with [meta] stored; null removes it. */
    fun write(extras: JsonObject, meta: UnderlayMeta?): JsonObject {
        if (meta == null) return JsonObject(extras - UnderlayIds.EXTRAS_KEY)
        val refs = meta.refs.map { r ->
            JsonObject(
                mapOf(
                    A to JsonArray(listOf(num(r.a.u), num(r.a.v))),
                    B to JsonArray(listOf(num(r.b.u), num(r.b.v))),
                    LEN to JsonPrimitive(r.lengthUnits),
                ),
            )
        }
        val root = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put(PX, JsonArray(listOf(whole(meta.widthPx), whole(meta.heightPx))))
            if (refs.isNotEmpty()) put(REFS, JsonArray(refs))
        }
        return JsonObject(extras + (UnderlayIds.EXTRAS_KEY to JsonObject(root)))
    }
}

/** The underlay of [this] scene: its image element and meta, or null when the sheet has none. */
val SketchSceneV2.underlay: UnderlayState?
    get() {
        val e = this[UnderlayIds.ELEMENT] as? ImageElement ?: return null
        if (e.base.layerId != UnderlayIds.LAYER) return null
        val meta = UnderlayMetaCodec.read(e.base.extras) ?: return null
        return UnderlayState(e, meta, layer(UnderlayIds.LAYER) ?: Layer(UnderlayIds.LAYER, UnderlayIds.LAYER_NAME))
    }

/** The underlay element, its meta and its layer (which carries lock, visibility and opacity). */
data class UnderlayState(val element: ImageElement, val meta: UnderlayMeta, val layer: Layer) {
    val placement: UnderlayPlacement
        get() = UnderlayPlacement(element.x.value, element.y.value, element.width.value, element.height.value, element.angle)

    val locked: Boolean get() = layer.locked
    val calibrated: Boolean get() = meta.refs.isNotEmpty()
}

/** [this] without the underlay element: what every export, embed preview and page render draws unless "include underlay" is on. */
fun SketchSceneV2.withoutUnderlay(): SketchSceneV2 {
    // By id as well as by layer: deleting the layer moves the photo to another one, and it must still stay out of exports.
    val victims = elementsInOrder.filter { it.base.layerId == UnderlayIds.LAYER || it.id == UnderlayIds.ELEMENT }
    return victims.fold(this) { scene, e -> scene.remove(e.id).getOrNull() ?: scene }
}

/** Where the image sits on the sheet: top-left of its unrotated box, size, and the clockwise-on-screen angle about the box centre. */
data class UnderlayPlacement(val x: Double, val y: Double, val width: Double, val height: Double, val angle: Double) {
    init {
        require(listOf(x, y, width, height, angle).all { it.isFinite() }) { "placement must be finite" }
        require(width > 0.0 && height > 0.0) { "placement size must be positive" }
    }

    val centreX: Double get() = x + width / HALF
    val centreY: Double get() = y + height / HALF

    /** The scene position of image pixel [p] of a [widthPx] x [heightPx] image under this placement. */
    fun toScene(p: ImagePx, widthPx: Int, heightPx: Int): Pair<Double, Double> {
        val dx = (p.u / widthPx - HALF_FRACTION) * width
        val dy = (p.v / heightPx - HALF_FRACTION) * height
        val c = cos(angle)
        val s = sin(angle)
        return (centreX + dx * c - dy * s) to (centreY + dx * s + dy * c)
    }

    /** The image pixel under scene point ([sx], [sy]): the inverse of [toScene]. */
    fun toImage(sx: Double, sy: Double, widthPx: Int, heightPx: Int): ImagePx {
        val rx = sx - centreX
        val ry = sy - centreY
        val c = cos(angle)
        val s = sin(angle)
        val dx = rx * c + ry * s
        val dy = -rx * s + ry * c
        return ImagePx((dx / width + HALF_FRACTION) * widthPx, (dy / height + HALF_FRACTION) * heightPx)
    }

    /** The placement with its centre at ([cx], [cy]). */
    fun centredAt(cx: Double, cy: Double) = copy(x = cx - width / HALF, y = cy - height / HALF)

    private companion object {
        const val HALF = 2.0
        const val HALF_FRACTION = 0.5
    }
}

sealed interface CalibrationError {
    /** The two points are closer than [UnderlayCalibration.MIN_REF_PIXELS] pixels: the scale would be noise. */
    data object PointsTooClose : CalibrationError

    /** The typed length is zero, negative or not a whole number of sixteenths. */
    data object BadLength : CalibrationError

    /** No reference was given. */
    data object NoReferences : CalibrationError

    /** The resulting size or position would leave the sheet's coordinate range, or a value was not finite. */
    data object OutOfRange : CalibrationError
}

/** What a calibration decided: the new placement, the scene units one photo pixel became, and how far the references disagree. */
data class CalibrationResult(val placement: UnderlayPlacement, val unitsPerPixel: Double, val residual: Double)

/**
 * Two-point scale calibration (epic E5). One known length scales the photo so that length is true on the sheet and, with [level],
 * turns the photo so the reference runs along the nearest axis. This is one scale for the whole photo: a photo taken at an angle
 * has perspective, so lengths away from the reference are off (a second reference measures how much, see [residual]).
 * A homography would correct that and is out of scope. Pure: no clock, no scene.
 */
object UnderlayCalibration {
    /** Points nearer than this (pixels) cannot fix a scale; a tap that did not move is refused instead of dividing by zero. */
    const val MIN_REF_PIXELS = 8.0

    /** References that disagree by more than this fraction of the mean scale say the photo has perspective (the CLI's 5 % rule). */
    const val PERSPECTIVE_RATIO = 0.05

    /** A reference this close to an axis (radians, 20 degrees) is levelled; a diagonal one is left alone. */
    const val LEVEL_REACH = 0.3490658503988659

    /** How far a stored reference may drift from the photo's current scale before the note calls it out of date. */
    const val STALE_RATIO = 0.005

    private const val QUARTER_TURN = PI / 2.0
    private const val PAIR_COUNT = 2
    private const val FULL_TURN = 2.0 * PI

    /** Scene units per photo pixel that [ref] implies. */
    fun unitsPerPixel(ref: CalibrationRef): Either<CalibrationError, Double> =
        if (ref.pixelLength < MIN_REF_PIXELS) CalibrationError.PointsTooClose.left() else (ref.lengthUnits / ref.pixelLength).right()

    /** The largest relative deviation of any reference's scale from the mean scale; 0 for fewer than two references. */
    fun residual(refs: List<CalibrationRef>): Double {
        val scales = refs.map { unitsPerPixel(it).getOrNull() ?: return 0.0 }
        if (scales.size < PAIR_COUNT) return 0.0
        val mean = scales.average()
        return scales.maxOf { abs(it - mean) / mean }
    }

    fun hasPerspective(refs: List<CalibrationRef>): Boolean = residual(refs) > PERSPECTIVE_RATIO

    /**
     * The placement that makes every reference's length true: the mean scale of [refs], the midpoint of the first reference
     * left where it is on the sheet, and, when [level] is on and the first reference is within [LEVEL_REACH] of an axis, the
     * photo turned so it runs along that axis.
     */
    fun solve(placement: UnderlayPlacement, widthPx: Int, heightPx: Int, refs: List<CalibrationRef>, level: Boolean): Either<CalibrationError, CalibrationResult> {
        val first = refs.firstOrNull() ?: return CalibrationError.NoReferences.left()
        val scales = refs.map { r -> unitsPerPixel(r).getOrElse { return it.left() } }
        val k = scales.average()
        val mid = ImagePx((first.a.u + first.b.u) / 2.0, (first.a.v + first.b.v) / 2.0)
        val (mx, my) = placement.toScene(mid, widthPx, heightPx)
        val heading = placement.angle + atan2(first.b.v - first.a.v, first.b.u - first.a.u)
        val turn = if (level) levelTurn(heading) else 0.0
        val angle = if (turn == 0.0) placement.angle else normalise(placement.angle + turn)
        val width = widthPx * k
        val height = heightPx * k
        if (!inRange(width) || !inRange(height)) return CalibrationError.OutOfRange.left()
        val bare = UnderlayPlacement(0.0, 0.0, width, height, angle)
        val (ox, oy) = bare.toScene(mid, widthPx, heightPx)
        // The box centre sits at (width/2, height/2) in `bare`; shift so the reference midpoint lands back on (mx, my).
        val placed = bare.copy(x = mx - ox, y = my - oy)
        if (!inRange(placed.x) || !inRange(placed.y)) return CalibrationError.OutOfRange.left()
        return CalibrationResult(placed, k, residual(refs)).right()
    }

    private fun inRange(v: Double): Boolean = v.isFinite() && abs(v) <= SceneLimits.MAX_COORD

    /** The turn (radians) that brings [heading] onto the nearest axis, or 0 when it is farther than [LEVEL_REACH] from one. */
    fun levelTurn(heading: Double): Double {
        val nearest = round(heading / QUARTER_TURN) * QUARTER_TURN
        val turn = nearest - heading
        return if (abs(turn) <= LEVEL_REACH) turn else 0.0
    }

    /** Angle in (-pi, pi]. */
    fun normalise(angle: Double): Double {
        var a = angle % FULL_TURN
        if (a > PI) a -= FULL_TURN
        if (a <= -PI) a += FULL_TURN
        return a
    }

    /** The scale (scene units per photo pixel) the references agree on: their mean, or null when one of them cannot give a scale. */
    fun meanScale(refs: List<CalibrationRef>): Double? {
        if (refs.isEmpty()) return null
        return refs.map { unitsPerPixel(it).getOrNull() ?: return null }.average()
    }

    /** True when the photo's current scale (its width over its pixel width) no longer matches what [refs] set: it was resized after calibrating. */
    fun stale(refs: List<CalibrationRef>, placement: UnderlayPlacement, widthPx: Int): Boolean {
        val expected = meanScale(refs) ?: return false
        return abs(placement.width / widthPx - expected) > STALE_RATIO * expected
    }

    /** [value] rounded to whole sixteenths for display and storage. */
    fun wholeUnits(value: Double): Long = value.roundToLong()
}

/** The state of the calibration note: not calibrated, calibrated at a reference length, or calibrated and then rescaled. */
sealed interface CalibrationNote {
    data object None : CalibrationNote
    data class Calibrated(val lengthUnits: Long, val perspective: Boolean) : CalibrationNote
    data class OutOfDate(val lengthUnits: Long) : CalibrationNote

    companion object {
        fun of(state: UnderlayState?): CalibrationNote {
            val first = state?.meta?.refs?.firstOrNull() ?: return None
            val p = state.placement
            return if (UnderlayCalibration.stale(state.meta.refs, p, state.meta.widthPx)) OutOfDate(first.lengthUnits)
            else Calibrated(first.lengthUnits, UnderlayCalibration.hasPerspective(state.meta.refs))
        }
    }
}

/**
 * Move, turn and (optionally) scale the photo with one or two fingers (epic E5). The photo point that lay under each pointer at
 * the start stays under it: one pointer moves; two pointers also turn by the change of their heading and, when [scale] is on,
 * scale by the change of their distance. [scale] is off for a calibrated photo so a pinch cannot undo the calibration.
 */
object UnderlayGesture {
    /** Two pointers closer than this (scene units) give no usable heading or scale, so the gesture stays a move. */
    const val MIN_SPAN = 1.0e-6
    private const val PAIR_COUNT = 2

    fun transform(
        base: UnderlayPlacement,
        from: List<Pair<Double, Double>>,
        to: List<Pair<Double, Double>>,
        scale: Boolean,
        sizeRange: ClosedFloatingPointRange<Double>,
    ): UnderlayPlacement {
        if (from.isEmpty() || from.size != to.size) return base
        val fromMid = mid(from)
        val toMid = mid(to)
        var turn = 0.0
        var factor = 1.0
        if (from.size >= PAIR_COUNT) {
            val f = from[0] to from[1]
            val t = to[0] to to[1]
            val fromSpan = hypot(f.second.first - f.first.first, f.second.second - f.first.second)
            val toSpan = hypot(t.second.first - t.first.first, t.second.second - t.first.second)
            if (fromSpan > MIN_SPAN && toSpan > MIN_SPAN) {
                turn = atan2(t.second.second - t.first.second, t.second.first - t.first.first) - atan2(f.second.second - f.first.second, f.second.first - f.first.first)
                if (scale) factor = toSpan / fromSpan
            }
        }
        val longer = maxOf(base.width, base.height)
        val bounded = (longer * factor).coerceIn(sizeRange.start, sizeRange.endInclusive) / longer
        val c = cos(turn)
        val s = sin(turn)
        val rx = base.centreX - fromMid.first
        val ry = base.centreY - fromMid.second
        val cx = toMid.first + bounded * (rx * c - ry * s)
        val cy = toMid.second + bounded * (rx * s + ry * c)
        return UnderlayPlacement(cx - base.width * bounded / 2.0, cy - base.height * bounded / 2.0, base.width * bounded, base.height * bounded, UnderlayCalibration.normalise(base.angle + turn))
    }

    private fun mid(points: List<Pair<Double, Double>>) = points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size
}
