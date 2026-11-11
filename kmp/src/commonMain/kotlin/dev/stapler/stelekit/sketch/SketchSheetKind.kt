package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.units.Inches

/** Which way the sheet looks. A back view mirrors x, so left-to-right label order reverses. */
enum class ViewDirection(val wire: String, val mirroredX: Boolean) {
    TOP("top", false),
    FROM_FRONT("from-front", false),
    FROM_BACK("from-back", true),
    ;

    /** [keys] as they read left to right in this view. */
    fun <T> labelOrder(keys: List<T>): List<T> = if (mirroredX) keys.reversed() else keys

    companion object {
        fun fromWire(wire: String): ViewDirection? = entries.firstOrNull { it.wire == wire }
    }
}

/** Sheet types with the defaults a blank sheet of that type starts from. */
enum class SketchSheetKind(
    val wire: String,
    val defaultDirection: ViewDirection,
    val directions: List<ViewDirection>,
    /** Grid step in sixteenths of an inch; the sheet's scale. */
    val defaultScale: Long,
) {
    PLAN("plan", ViewDirection.TOP, listOf(ViewDirection.TOP), Inches.SIXTEENTHS_PER_INCH),
    ELEVATION("elevation", ViewDirection.FROM_FRONT, listOf(ViewDirection.FROM_FRONT, ViewDirection.FROM_BACK), Inches.SIXTEENTHS_PER_INCH),
    SECTION("section", ViewDirection.FROM_FRONT, listOf(ViewDirection.FROM_FRONT, ViewDirection.FROM_BACK), Inches.SIXTEENTHS_PER_INCH / 2),
    ;

    companion object {
        fun fromWire(wire: String): SketchSheetKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A named layer. Hidden layers are not drawn or hit; locked layers refuse edits to their primitives. */
data class SketchLayer(val name: String, val visible: Boolean = true, val locked: Boolean = false) {
    fun encode(): String = name + (if (visible) "" else HIDDEN) + (if (locked) LOCKED else "")

    companion object {
        const val MAX_NAME_LENGTH = 40
        private const val SEP = ';'
        private const val HIDDEN = ";hidden"
        private const val LOCKED = ";locked"

        /** A name is trimmed, non-blank, short, and free of the separator used in storage. */
        fun isValidName(name: String): Boolean = name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME_LENGTH && SEP !in name

        fun decode(stored: String): SketchLayer? {
            val parts = stored.split(SEP)
            val name = parts.first()
            val flags = parts.drop(1)
            return if (isValidName(name) && flags.all { it == "hidden" || it == "locked" }) {
                SketchLayer(name, visible = "hidden" !in flags, locked = "locked" in flags)
            } else {
                null
            }
        }
    }
}

/**
 * Sheet-level settings that travel with undo. [scaleSixteenths] is the grid step; the primitive
 * layer `""` is the always-visible default layer and is never listed in [layers].
 */
data class SheetProps(
    val kind: SketchSheetKind = SketchSheetKind.PLAN,
    val viewDirection: ViewDirection = kind.defaultDirection,
    val scaleSixteenths: Long = kind.defaultScale,
    val layers: List<SketchLayer> = emptyList(),
) {
    val gridStep: Inches get() = Inches(scaleSixteenths)

    fun layer(name: String): SketchLayer? = layers.firstOrNull { it.name == name }

    fun isVisible(layerName: String): Boolean = layer(layerName)?.visible ?: true

    fun isLocked(layerName: String): Boolean = layer(layerName)?.locked ?: false

    /** Switches the sheet type and resets direction and scale to that type's defaults. */
    fun withKind(next: SketchSheetKind): SheetProps = copy(kind = next, viewDirection = next.defaultDirection, scaleSixteenths = next.defaultScale)

    companion object {
        const val MAX_SCALE_SIXTEENTHS = 1920L
        const val MAX_LAYERS = 32

        fun blank(kind: SketchSheetKind): SheetProps = SheetProps(kind)

        /** Reads stored wire values; an unknown kind or a direction its kind does not allow falls back to defaults. */
        fun fromStored(kind: String, direction: String, scale: Long, layers: List<String>): SheetProps {
            val k = SketchSheetKind.fromWire(kind) ?: SketchSheetKind.PLAN
            val d = ViewDirection.fromWire(direction)?.takeIf { it in k.directions } ?: k.defaultDirection
            val s = scale.takeIf { it in 1..MAX_SCALE_SIXTEENTHS } ?: k.defaultScale
            return SheetProps(k, d, s, layers.mapNotNull(SketchLayer::decode).distinctBy { it.name })
        }
    }
}
