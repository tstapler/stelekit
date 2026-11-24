// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.StyleArrowType
import dev.stapler.stelekit.sketch.v2.commands.StyleArrowheads
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.commands.StyleText
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.arrowType
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId

/** A property value that is the same across the selection, or the "Mixed" chip (ux.md section 7). */
sealed interface StyleValue<out T> {
    data class Same<T>(val value: T) : StyleValue<T>

    data object Mixed : StyleValue<Nothing>

    companion object {
        fun <T> of(values: List<T>): StyleValue<T>? = when {
            values.isEmpty() -> null
            values.toSet().size == 1 -> Same(values.first())
            else -> Mixed
        }
    }
}

/** A named colour: [name] is what a screen reader and a tooltip say, never the hex alone (UX-15). */
data class NamedColor(val css: String, val name: String)

object ColorPalette {
    const val TRANSPARENT = "transparent"

    val stroke: List<NamedColor> = listOf(
        NamedColor("#1e1e1e", "Black"), NamedColor("#e03131", "Red 600"), NamedColor("#2f9e44", "Green 700"),
        NamedColor("#1971c2", "Blue 700"), NamedColor("#f08c00", "Orange 600"),
    )

    val background: List<NamedColor> = listOf(
        NamedColor(TRANSPARENT, "Transparent"), NamedColor("#ffc9c9", "Red 100"), NamedColor("#b2f2bb", "Green 100"),
        NamedColor("#a5d8ff", "Blue 100"), NamedColor("#ffec99", "Yellow 100"),
    )

    private val byCss: Map<String, String> = (stroke + background).associate { it.css.lowercase() to it.name }

    /** The palette name, else "Custom" with the hex so a screen reader never gets a bare code. */
    fun nameOf(css: String): String = byCss[css.lowercase()] ?: "Custom $css"
}

enum class StrokeWidthChoice(val label: String, val width: Double) {
    Thin("Thin", 1.0), Bold("Bold", 2.0), ExtraBold("Extra bold", 4.0);

    companion object {
        fun of(width: Double): StrokeWidthChoice? = entries.firstOrNull { it.width == width }
    }
}

enum class SloppinessChoice(val label: String, val roughness: Int) {
    Clean("Clean", 0), Sketchy("Sketchy", 1);

    companion object {
        fun of(roughness: Int): SloppinessChoice = if (roughness <= 0) Clean else Sketchy
    }
}

enum class EdgesChoice(val label: String) { Sharp("Sharp"), Round("Round") }

enum class FontChoice(val label: String, val family: Int) {
    HandDrawn("Hand-drawn", 1), Normal("Normal", 2), Code("Code", 3);

    companion object {
        fun of(family: Int): FontChoice? = entries.firstOrNull { it.family == family }
    }
}

enum class FontSizeChoice(val label: String, val size: Double) {
    S("S", 16.0), M("M", 20.0), L("L", 28.0), XL("XL", 36.0);

    companion object {
        fun of(size: Double): FontSizeChoice? = entries.firstOrNull { it.size == size }
    }
}

/** The properties of the panel (`link` and cardinality arrowheads stay out, D-22); Task 3.5.4c resolves its ids against these. */
enum class StyleProperty {
    StrokeColor, BackgroundColor, FillStyle, StrokeWidth, StrokeStyle, Sloppiness, Edges, FontFamily, FontSize,
    TextAlign, VerticalAlign, ArrowheadStart, ArrowheadEnd, ArrowType, Opacity, Layer,
}

/** What the panel shows for [elements], or for the tool defaults when nothing is selected. */
class StylePanelModel private constructor(
    val heading: String,
    val editsSelection: Boolean,
    private val elements: List<SceneElement>,
    private val defaults: ElementStyle,
    private val layers: List<Layer>,
) {
    private fun styles(): List<ElementStyle> = if (editsSelection) elements.map { it.base.style } else listOf(defaults)

    val strokeColor: StyleValue<NamedColor> get() = color { it.strokeColor }
    val backgroundColor: StyleValue<NamedColor> get() = color { it.backgroundColor }
    val fillStyle: StyleValue<FillStyle> get() = StyleValue.of(styles().map { it.fillStyle })!!
    val strokeWidth: StyleValue<Double> get() = StyleValue.of(styles().map { it.strokeWidth })!!
    val strokeStyle: StyleValue<StrokeStyle> get() = StyleValue.of(styles().map { it.strokeStyle })!!
    val sloppiness: StyleValue<SloppinessChoice> get() = StyleValue.of(styles().map { SloppinessChoice.of(it.roughness) })!!
    val edges: StyleValue<EdgesChoice> get() = StyleValue.of(styles().map { if (it.roundness == null) EdgesChoice.Sharp else EdgesChoice.Round })!!

    val opacity: StyleValue<Int>? get() = if (editsSelection) StyleValue.of(elements.map { it.base.opacity }) else null

    val fontFamily: StyleValue<Int>? get() = StyleValue.of(elements.filterIsInstance<TextElement>().map { it.fontFamily })
    val fontSize: StyleValue<Double>? get() = StyleValue.of(elements.filterIsInstance<TextElement>().map { it.fontSize })
    val textAlign: StyleValue<TextAlign>? get() = StyleValue.of(elements.filterIsInstance<TextElement>().map { it.textAlign })
    val verticalAlign: StyleValue<VerticalAlign>? get() = StyleValue.of(elements.filterIsInstance<TextElement>().map { it.verticalAlign })
    val arrowType: StyleValue<ArrowType>? get() = StyleValue.of(elements.filterIsInstance<ArrowElement>().map { it.arrowType })
    val startArrowhead: StyleValue<Arrowhead>? get() = StyleValue.of(elements.filterIsInstance<ArrowElement>().map { it.startArrowhead })
    val endArrowhead: StyleValue<Arrowhead>? get() = StyleValue.of(elements.filterIsInstance<ArrowElement>().map { it.endArrowhead })

    /**
     * What the panel shows for [property] over this selection: a value, "Mixed", or null when no selected element has it
     * (a font row with no text). Task 3.5.4c resolves each style id of the Excalidraw baseline through this one entry.
     */
    fun valueOf(property: StyleProperty): StyleValue<Any>? = when (property) {
        StyleProperty.StrokeColor -> strokeColor
        StyleProperty.BackgroundColor -> backgroundColor
        StyleProperty.FillStyle -> fillStyle
        StyleProperty.StrokeWidth -> strokeWidth
        StyleProperty.StrokeStyle -> strokeStyle
        StyleProperty.Sloppiness -> sloppiness
        StyleProperty.Edges -> edges
        StyleProperty.FontFamily -> fontFamily
        StyleProperty.FontSize -> fontSize
        StyleProperty.TextAlign -> textAlign
        StyleProperty.VerticalAlign -> verticalAlign
        StyleProperty.ArrowheadStart -> startArrowhead
        StyleProperty.ArrowheadEnd -> endArrowhead
        StyleProperty.ArrowType -> arrowType
        StyleProperty.Opacity -> opacity
        StyleProperty.Layer -> layerValue()
    }

    private fun layerValue(): StyleValue<Any>? = if (editsSelection) StyleValue.of(elements.map { it.base.layerId }) else null

    /** The stroke-width row is disabled for an image-only selection, with the reason shown beside it. */
    val strokeWidthDisabledReason: String? get() =
        if (editsSelection && elements.isNotEmpty() && elements.all { it is ImageElement }) "Stroke width does not apply to images" else null

    /** "Effective 50% (layer 50%)" when a layer multiplies the opacity of the selection; null otherwise. */
    fun effectiveOpacityNote(): String? {
        if (!editsSelection) return null
        val layerOpacities = elements.mapNotNull { e -> layers.firstOrNull { it.id == e.base.layerId }?.opacity }.toSet()
        val layer = layerOpacities.singleOrNull() ?: return null
        if (layer >= 1.0) return null
        val own = (opacity as? StyleValue.Same)?.value ?: return null
        val layerPercent = (layer * PERCENT).toInt()
        return "Effective ${own * layerPercent / PERCENT}% (layer $layerPercent%)"
    }

    private fun color(pick: (ElementStyle) -> String): StyleValue<NamedColor> =
        StyleValue.of(styles().map { pick(it) })!!.let { v ->
            when (v) {
                is StyleValue.Same -> StyleValue.Same(NamedColor(v.value, ColorPalette.nameOf(v.value)))
                StyleValue.Mixed -> StyleValue.Mixed
            }
        }

    companion object {
        private const val PERCENT = 100

        fun of(selection: List<SceneElement>, defaults: ElementStyle, tool: ToolId, scene: SketchSceneV2): StylePanelModel =
            if (selection.isEmpty()) {
                StylePanelModel("Defaults for ${tool.name}", false, emptyList(), defaults, scene.layers)
            } else {
                StylePanelModel("Selection (${selection.size})", true, selection, defaults, scene.layers)
            }
    }
}

/** Panel actions as commands (selection) or as a new default style (nothing selected): one undo step each. */
object StyleActions {
    fun fillStyle(v: FillStyle) = StylePatch(fillStyle = v)

    fun strokeWidth(v: StrokeWidthChoice) = StylePatch(strokeWidth = v.width)

    fun strokeStyle(v: StrokeStyle) = StylePatch(strokeStyle = v)

    fun sloppiness(v: SloppinessChoice) = StylePatch(roughness = v.roughness)

    fun edges(v: EdgesChoice) = if (v == EdgesChoice.Sharp) StylePatch(sharpCorners = true) else StylePatch(roundness = Roundness(RoundnessKind.AdaptiveRadius))

    fun strokeColor(v: NamedColor) = StylePatch(strokeColor = v.css)

    fun backgroundColor(v: NamedColor) = StylePatch(backgroundColor = v.css)

    fun opacity(percent: Int) = StylePatch(opacity = percent.coerceIn(0, ElementBase.FULL_OPACITY))

    fun font(ids: List<ElementId>, v: FontChoice, metrics: TextMetricsProvider? = null): Command = StyleText(ids, fontFamily = v.family, metrics = metrics)

    fun fontSize(ids: List<ElementId>, v: FontSizeChoice, metrics: TextMetricsProvider? = null): Command = StyleText(ids, fontSize = v.size, metrics = metrics)

    fun textAlign(ids: List<ElementId>, v: TextAlign): Command = StyleText(ids, textAlign = v)

    fun verticalAlign(ids: List<ElementId>, v: VerticalAlign): Command = StyleText(ids, verticalAlign = v)

    fun arrowType(ids: List<ElementId>, v: ArrowType): Command = StyleArrowType(ids, v)

    fun arrowheads(ids: List<ElementId>, start: Arrowhead?, end: Arrowhead?): Command = StyleArrowheads(ids, start, end)

    /** The tool defaults after [patch], for the nothing-selected case (the same field rules as `StyleElements`). */
    fun applyToDefaults(defaults: ElementStyle, patch: StylePatch): ElementStyle = defaults.copy(
        strokeColor = patch.strokeColor ?: defaults.strokeColor,
        backgroundColor = patch.backgroundColor ?: defaults.backgroundColor,
        fillStyle = patch.fillStyle ?: defaults.fillStyle,
        strokeWidth = patch.strokeWidth ?: defaults.strokeWidth,
        strokeStyle = patch.strokeStyle ?: defaults.strokeStyle,
        roughness = patch.roughness ?: defaults.roughness,
        roundness = if (patch.sharpCorners) null else patch.roundness ?: defaults.roundness,
    )
}
