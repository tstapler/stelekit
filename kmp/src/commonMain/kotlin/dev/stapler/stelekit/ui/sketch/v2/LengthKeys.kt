// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings.Keys as K
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength

/** A key of the symbol row above the soft keyboard: what it types, what it shows and what a screen reader says. */
enum class LengthKey(val insert: String, val label: String, val description: String, val spaceAfter: Boolean = false) {
    FeetMark("'", K.FEET_MARK_LABEL, K.FEET_MARK, spaceAfter = true),
    InchMark("\"", K.INCH_MARK_LABEL, K.INCH_MARK),
    Slash("/", K.SLASH_LABEL, K.SLASH),
    Point(".", K.POINT_LABEL, K.POINT),
    FeetWord("ft", K.FEET_WORD_LABEL, K.FEET_WORD, spaceAfter = true),
    InchWord("in", K.INCH_WORD_LABEL, K.INCH_WORD),
}

/**
 * The Measure keypad's keys beyond the symbol row: the common fractions of an inch, the metric unit words and the decimal comma.
 * A key with [spaceBefore] puts a space between a number before the caret and what it types (`3` then `1/2` gives `3 1/2`).
 */
enum class MeasureKey(val insert: String, val label: String, val description: String, val spaceBefore: Boolean = true) {
    Half("1/2", K.HALF_LABEL, K.HALF),
    Quarter("1/4", K.QUARTER_LABEL, K.QUARTER),
    Eighth("1/8", K.EIGHTH_LABEL, K.EIGHTH),
    Sixteenth("1/16", K.SIXTEENTH_LABEL, K.SIXTEENTH),
    Millimetre("mm", K.MM_LABEL, K.MM),
    Centimetre("cm", K.CM_LABEL, K.CM),
    Metre("m", K.M_LABEL, K.M),
    Comma(",", K.COMMA_LABEL, K.COMMA, spaceBefore = false),
    ;

    companion object {
        val FRACTIONS = listOf(Half, Quarter, Eighth, Sixteenth)
        val METRIC = listOf(Millimetre, Centimetre, Metre, Comma)
    }
}

/** The keypad's action key: Next on every field but the last, Done on the last. */
data class LengthAdvanceKey(val label: String, val description: String, val onAdvance: () -> Unit)

/** A field's text with its selection: [start] and [end] are caret offsets, in either order (a collapsed selection is the caret). */
data class CaretText(val text: String, val start: Int, val end: Int)

/** Where [LengthKeyInsertion.apply] leaves the field: the new [text] with the caret after what was typed. */
data class CaretResult(val text: String, val caret: Int)

object LengthKeyInsertion {
    /**
     * Types [key] at the caret, replacing the selection. A key that ends a feet part (`'`, `ft`) also adds a space when the
     * text after the caret does not already start with one, so `3` then `'` gives `3' ` and the inches can follow.
     */
    fun apply(current: CaretText, key: LengthKey): CaretResult {
        val lo = current.start.coerceIn(0, current.text.length).coerceAtMost(current.end.coerceIn(0, current.text.length))
        val hi = maxOf(current.start, current.end).coerceIn(0, current.text.length)
        val after = current.text.substring(hi)
        val typed = if (key.spaceAfter && (after.isEmpty() || !after.first().isWhitespace())) key.insert + " " else key.insert
        return CaretResult(current.text.substring(0, lo) + typed + after, lo + typed.length)
    }

    /** Types a [MeasureKey] at the caret, replacing the selection, with a space after a number that comes right before it. */
    fun apply(current: CaretText, key: MeasureKey): CaretResult {
        val lo = current.start.coerceIn(0, current.text.length).coerceAtMost(current.end.coerceIn(0, current.text.length))
        val hi = maxOf(current.start, current.end).coerceIn(0, current.text.length)
        val before = current.text.substring(0, lo)
        val last = before.lastOrNull()?.toString().orEmpty()
        val afterNumber = last.firstOrNull()?.isDigit() == true || last == LengthKey.FeetMark.insert || last == LengthKey.InchMark.insert
        val typed = if (key.spaceBefore && afterNumber) " " + key.insert else key.insert
        return CaretResult(before + typed + current.text.substring(hi), lo + typed.length)
    }
}

object LengthKeyTags {
    const val ROW = "sketch.keys.row"
    const val MEASURE_ROW = "sketch.keys.measure-row"
    const val ADVANCE = "sketch.keys.advance"
    fun key(key: LengthKey) = "sketch.keys.${key.name.lowercase()}"
    fun key(key: MeasureKey) = "sketch.keys.${key.name.lowercase()}"
}

private const val KEY_GAP_DP = 2
private const val KEY_RADIUS_DP = 10
private const val KEY_MIN_DP = 44
private const val ROW_GAP_DP = 4

/**
 * The keypad attached to the size field. An inch display gets the symbol row (feet mark, inch mark, slash, point, ft, in) and a row
 * of the common fractions; a metric display gets the unit words (mm, cm, m) and the decimal comma. [advance], when given, adds the
 * Next or Done key at the end of the last row. A key edits the field's value through [onKey]; it never takes focus, so the soft
 * keyboard stays open and the caret stays where it is.
 */
@Composable
fun LengthKeyRow(
    onKey: (LengthKey) -> Unit,
    modifier: Modifier = Modifier,
    format: LengthFormat = LengthFormat.FEET_INCHES,
    onMeasureKey: (MeasureKey) -> Unit = {},
    advance: LengthAdvanceKey? = null,
) {
    val metric = MetricLength.isMetric(format)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        if (!metric) Row(Modifier.fillMaxWidth().testTag(LengthKeyTags.ROW), horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
            LengthKey.entries.forEach { key -> KeyButton(key.label, key.description, LengthKeyTags.key(key)) { onKey(key) } }
        }
        Row(Modifier.fillMaxWidth().testTag(LengthKeyTags.MEASURE_ROW), horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
            (if (metric) MeasureKey.METRIC else MeasureKey.FRACTIONS).forEach { key -> KeyButton(key.label, key.description, LengthKeyTags.key(key)) { onMeasureKey(key) } }
            advance?.let { KeyButton(it.label, it.description, LengthKeyTags.ADVANCE, it.onAdvance) }
        }
    }
}

@Composable
private fun RowScope.KeyButton(label: String, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).defaultMinSize(minWidth = KEY_MIN_DP.dp, minHeight = KEY_MIN_DP.dp).testTag(tag)
            .clip(RoundedCornerShape(KEY_RADIUS_DP.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}
