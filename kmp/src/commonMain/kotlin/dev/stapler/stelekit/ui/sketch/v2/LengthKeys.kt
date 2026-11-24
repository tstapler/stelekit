// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** A key of the symbol row above the soft keyboard: what it types, what it shows and what a screen reader says. */
enum class LengthKey(val insert: String, val label: String, val description: String, val spaceAfter: Boolean = false) {
    FeetMark("'", K.FEET_MARK_LABEL, K.FEET_MARK, spaceAfter = true),
    InchMark("\"", K.INCH_MARK_LABEL, K.INCH_MARK),
    Slash("/", K.SLASH_LABEL, K.SLASH),
    Point(".", K.POINT_LABEL, K.POINT),
    FeetWord("ft", K.FEET_WORD_LABEL, K.FEET_WORD, spaceAfter = true),
    InchWord("in", K.INCH_WORD_LABEL, K.INCH_WORD),
}

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
}

object LengthKeyTags {
    const val ROW = "sketch.keys.row"
    fun key(key: LengthKey) = "sketch.keys.${key.name.lowercase()}"
}

private const val KEY_GAP_DP = 2
private const val KEY_RADIUS_DP = 10
private const val KEY_MIN_DP = 44

/**
 * The symbol row attached to the size field: feet mark, inch mark, fraction slash, point and the ft and in words, so a length can be
 * typed without leaving the letters keyboard. A key edits the field's value through [onKey]; it never takes focus, so the soft
 * keyboard stays open and the caret stays where it is.
 */
@Composable
fun LengthKeyRow(onKey: (LengthKey) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag(LengthKeyTags.ROW), horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
        LengthKey.entries.forEach { key -> KeyButton(key, onKey) }
    }
}

@Composable
private fun RowScope.KeyButton(key: LengthKey, onKey: (LengthKey) -> Unit) {
    Box(
        Modifier.weight(1f).defaultMinSize(minWidth = KEY_MIN_DP.dp, minHeight = KEY_MIN_DP.dp).testTag(LengthKeyTags.key(key))
            .clip(RoundedCornerShape(KEY_RADIUS_DP.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .focusProperties { canFocus = false }
            .clickable(onClick = { onKey(key) })
            .semantics { contentDescription = key.description; role = Role.Button }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(key.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}
