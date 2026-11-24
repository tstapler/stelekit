// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object StylePanelTags {
    const val HEADING = "sketch.style.heading"
    const val MIXED_PREFIX = "sketch.style.mixed."
    fun swatch(name: String) = "sketch.style.swatch.$name"
    fun choice(label: String) = "sketch.style.choice.$label"
    const val OPACITY_FIELD = "sketch.style.opacity.field"
    const val OPACITY_MINUS = "sketch.style.opacity.minus"
    const val OPACITY_PLUS = "sketch.style.opacity.plus"
    const val OPACITY_NOTE = "sketch.style.opacity.note"
    const val WIDTH_REASON = "sketch.style.width.reason"
    const val ELBOW_NOTE = "sketch.style.elbow.note"
    const val STATS_PREFIX = "sketch.stats."
}

private const val PERCENT = 100
private const val OPACITY_STEP = 10
private val TARGET = 44.dp
private const val HEX_LENGTH = 7

/**
 * The style panel (Story 3.5.4a). [selection] is what is selected; with nothing selected a change goes to
 * [onDefaults] (the tool defaults), else to [onCommand] as one undoable command. A mixed value shows a "Mixed" chip
 * and no choice is marked, so the panel never claims a value the selection does not share.
 */
@Composable
fun StylePanel(
    scene: SketchSceneV2,
    selection: List<SceneElement>,
    defaults: ElementStyle,
    tool: ToolId,
    onCommand: (Command) -> Unit,
    onDefaults: (StylePatch) -> Unit,
    modifier: Modifier = Modifier,
    metrics: TextMetricsProvider? = null,
    onTextSize: (TextSizeChange) -> Unit = {},
) {
    val model = StylePanelModel.of(selection, defaults, tool, scene)
    val ids = selection.map { it.id }
    fun send(patch: StylePatch) = if (model.editsSelection) onCommand(StyleElements(ids, patch)) else onDefaults(patch)

    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(model.heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(StylePanelTags.HEADING))
        SwatchRow("Stroke", ColorPalette.stroke, model.strokeColor) { send(StyleActions.strokeColor(it)) }
        SwatchRow("Fill", ColorPalette.background, model.backgroundColor) { send(StyleActions.backgroundColor(it)) }
        ChoiceRow("Fill style", FillStyle.entries.map { it to it.name }, model.fillStyle) { send(StyleActions.fillStyle(it)) }
        val widthReason = model.strokeWidthDisabledReason
        ChoiceRow("Stroke width", StrokeWidthChoice.entries.map { it to it.label }, model.strokeWidth.asChoice { StrokeWidthChoice.of(it) }, enabled = widthReason == null) { send(StyleActions.strokeWidth(it)) }
        widthReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(StylePanelTags.WIDTH_REASON)) }
        ChoiceRow("Stroke style", StrokeStyle.entries.map { it to it.name }, model.strokeStyle) { send(StyleActions.strokeStyle(it)) }
        ChoiceRow("Sloppiness", SloppinessChoice.entries.map { it to it.label }, model.sloppiness) { send(StyleActions.sloppiness(it)) }
        ChoiceRow("Edges", EdgesChoice.entries.map { it to it.label }, model.edges) { send(StyleActions.edges(it)) }
        model.fontFamily?.let { family ->
            ChoiceRow("Font", FontChoice.entries.map { it to it.label }, family.asChoice { FontChoice.of(it) }) { onCommand(StyleActions.font(ids, it, metrics)) }
            model.fontSize?.let { size ->
                TextSizeModel.current(selection)?.let { TextSizeRow(it, onTextSize) }
                ChoiceRow("Font size", FontSizeChoice.entries.map { it to it.label }, size.asChoice { FontSizeChoice.of(it) }) { onCommand(StyleActions.fontSize(ids, it, metrics)) }
            }
        }
        TextAlignRows(model, ids, onCommand)
        ArrowRows(model, ids, onCommand)
        model.opacity?.let { opacity ->
            OpacityRow(opacity) { send(StyleActions.opacity(it)) }
            model.effectiveOpacityNote()?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(StylePanelTags.OPACITY_NOTE)) }
        }
    }
}

@Composable
private fun TextAlignRows(model: StylePanelModel, ids: List<ElementId>, onCommand: (Command) -> Unit) {
    model.textAlign?.let { align ->
        ChoiceRow(SketchStrings.Style.TEXT_ALIGN, TextAlign.entries.map { it to it.name }, align) { onCommand(StyleActions.textAlign(ids, it)) }
    }
    model.verticalAlign?.let { align ->
        ChoiceRow(SketchStrings.Style.VERTICAL_ALIGN, VerticalAlign.entries.map { it to it.name }, align) { onCommand(StyleActions.verticalAlign(ids, it)) }
    }
}

/** Arrow type and the two arrowheads of a selection that holds arrows; the elbow type says it is drawn as a polyline (OD-7). */
@Composable
private fun ArrowRows(model: StylePanelModel, ids: List<ElementId>, onCommand: (Command) -> Unit) {
    val type = model.arrowType ?: return
    ChoiceRow(SketchStrings.Style.ARROW_TYPE, ArrowType.entries.map { it to it.name }, type) { onCommand(StyleActions.arrowType(ids, it)) }
    if ((type as? StyleValue.Same)?.value == ArrowType.Elbow) {
        Text(SketchStrings.Style.ELBOW_NOTE, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(StylePanelTags.ELBOW_NOTE))
    }
    model.startArrowhead?.let { head ->
        ChoiceRow(SketchStrings.Style.START_ARROWHEAD, Arrowhead.entries.map { it to it.name }, head) { onCommand(StyleActions.arrowheads(ids, it, null)) }
    }
    model.endArrowhead?.let { head ->
        ChoiceRow(SketchStrings.Style.END_ARROWHEAD, Arrowhead.entries.map { it to it.name }, head) { onCommand(StyleActions.arrowheads(ids, null, it)) }
    }
}

private inline fun <T, R> StyleValue<T>.asChoice(map: (T) -> R?): StyleValue<R> = when (this) {
    is StyleValue.Same -> map(value)?.let { StyleValue.Same(it) } ?: StyleValue.Mixed
    StyleValue.Mixed -> StyleValue.Mixed
}

@Composable
private fun SwatchRow(label: String, colors: List<NamedColor>, current: StyleValue<NamedColor>, onPick: (NamedColor) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.widthIn(min = 72.dp), color = MaterialTheme.colorScheme.onSurface)
        for (color in colors) {
            val selected = (current as? StyleValue.Same)?.value?.css == color.css
            Box(
                Modifier.size(TARGET).testTag(StylePanelTags.swatch(color.name))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(color) })
                    .semantics { contentDescription = "$label ${color.name}" },
            ) {
                Box(
                    Modifier.fillMaxSize().padding(8.dp).background(parseCss(color.css), CircleShape)
                        .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                )
            }
        }
        if (current is StyleValue.Mixed) MixedChip(label)
    }
}

@Composable
private fun MixedChip(label: String) {
    Text("Mixed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag(StylePanelTags.MIXED_PREFIX + label))
}

@Composable
private fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, current: StyleValue<T>, enabled: Boolean = true, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, Modifier.widthIn(min = 72.dp))
        for ((value, text) in choices) {
            val selected = (current as? StyleValue.Same)?.value == value
            Box(
                Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(StylePanelTags.choice("$label.$text"))
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(value) })
                    .semantics { contentDescription = "$label $text" }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium) }
        }
        if (current is StyleValue.Mixed) MixedChip(label)
    }
}

/** Opacity is a number with minus and plus buttons (a slider alone would be a drag, WCAG 2.5.7). */
@Composable
private fun OpacityRow(opacity: StyleValue<Int>, onSet: (Int) -> Unit) {
    val value = (opacity as? StyleValue.Same)?.value
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity", Modifier.widthIn(min = 72.dp))
        TextButton(onClick = { onSet(((value ?: PERCENT) - OPACITY_STEP).coerceAtLeast(0)) }, modifier = Modifier.heightIn(min = TARGET).testTag(StylePanelTags.OPACITY_MINUS)) { Text("-10") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; it.toIntOrNull()?.takeIf { v -> v in 0..PERCENT }?.let(onSet) },
            singleLine = true,
            modifier = Modifier.widthIn(max = 96.dp).testTag(StylePanelTags.OPACITY_FIELD),
        )
        TextButton(onClick = { onSet(((value ?: 0) + OPACITY_STEP).coerceAtMost(PERCENT)) }, modifier = Modifier.heightIn(min = TARGET).testTag(StylePanelTags.OPACITY_PLUS)) { Text("+10") }
        if (opacity is StyleValue.Mixed) MixedChip("Opacity")
    }
}

/** `#rrggbb` to a Compose colour; `transparent` and anything unparseable draw as transparent. */
internal fun parseCss(css: String): Color {
    if (css.length != HEX_LENGTH || css[0] != '#') return Color.Transparent
    val rgb = css.substring(1).toLongOrNull(radix = 16) ?: return Color.Transparent
    return Color(0xFF000000L or rgb)
}
