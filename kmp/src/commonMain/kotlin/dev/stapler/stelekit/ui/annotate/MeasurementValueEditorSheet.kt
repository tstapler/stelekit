// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.metersToDisplay
import dev.stapler.stelekit.model.metersToDisplayString
import kotlin.math.round

/** What a typed distance means (ADR-002): it sets the photo's scale, or only changes the label. */
enum class ValueEditMode { KNOWN_DISTANCE, LABEL_ONLY }

/**
 * Everything the editor needs about the measurement being edited; the host fills it from the ViewModel.
 *
 * @property segmentPixelLength image-pixel length of the line; 0 means the line is too short to set a scale
 * @property currentMetersPerPixel the photo's present scale, null when uncalibrated
 * @property lastChoice the choice made earlier in this editor session, or null for the first edit
 */
data class ValueEditorContext(
    val initialMeters: Double?,
    val displayUnit: MeasurementUnit,
    val photoCalibrated: Boolean,
    val segmentPixelLength: Double,
    val currentMetersPerPixel: Double?,
    val otherMeasurementCount: Int,
    val legacyBasis: Boolean = false,
    val lastChoice: ValueEditMode? = null,
    val title: String = "Distance",
)

/** The user's committed entry; [meters] is already converted from the entry unit. */
data class ValueEditorResult(val meters: Double, val mode: ValueEditMode, val entryUnit: MeasurementUnit)

/** Pure rules of the value editor, kept apart from the composable so they are cheap to test. */
object ValueEditorRules {
    const val ZERO_LENGTH_REASON = "This line is too short to set a scale"
    const val SAVE_FAILED = "Couldn't save the change. Try again"
    const val HELPER = "Enter a length, e.g. 90, 2.5 ft, 5'3\""
    const val NEEDS_POSITIVE = "Enter a length greater than 0"
    const val IMPLAUSIBLE_VALUE = "That length looks too large or too small. Check the unit."
    const val ASSISTIVE_UNDO_NOTE = "Undo stays available in the toolbar"

    /** A known-distance scale this many times (or 1/this) the current one is probably a unit slip. */
    const val IMPLAUSIBLE_RATIO = 3.0
    private const val TENTH = 10.0

    val entryUnits = listOf(
        MeasurementUnit.CENTIMETERS, MeasurementUnit.METERS, MeasurementUnit.MILLIMETERS,
        MeasurementUnit.FEET, MeasurementUnit.INCHES,
    )

    /** Why "Use as known distance" is unavailable, or null when it can be chosen. */
    fun knownDistanceBlockedReason(ctx: ValueEditorContext): String? = when {
        ctx.legacyBasis -> CalibrationStatePresenter.LEGACY_BASIS_REASON
        ctx.segmentPixelLength <= 0.0 -> ZERO_LENGTH_REASON
        else -> null
    }

    /** One rule (ux 6.1): uncalibrated preselects known distance; calibrated first edit preselects nothing; later edits repeat the last choice. */
    fun initialChoice(ctx: ValueEditorContext): ValueEditMode? = when {
        knownDistanceBlockedReason(ctx) != null -> ValueEditMode.LABEL_ONLY
        !ctx.photoCalibrated -> ValueEditMode.KNOWN_DISTANCE
        else -> ctx.lastChoice
    }

    /** New scale divided by current scale, or null when either is unknown. */
    fun scaleRatio(ctx: ValueEditorContext, meters: Double): Double? {
        val current = ctx.currentMetersPerPixel ?: return null
        if (ctx.segmentPixelLength <= 0.0 || current <= 0.0) return null
        return (meters / ctx.segmentPixelLength) / current
    }

    fun implausibleScaleMessage(ratio: Double?): String? = when {
        ratio == null -> null
        ratio > IMPLAUSIBLE_RATIO -> "This is ${times(ratio)}x the current scale. Check the unit."
        ratio < 1.0 / IMPLAUSIBLE_RATIO -> "This is 1/${times(1.0 / ratio)} of the current scale. Check the unit."
        else -> null
    }

    fun knownConsequence(otherCount: Int): String = when (otherCount) {
        0 -> "Sets the photo's scale. No other measurements to change."
        1 -> "Sets the photo's scale. 1 other measurement will change."
        else -> "Sets the photo's scale. $otherCount other measurements will change."
    }

    const val LABEL_ONLY_CONSEQUENCE =
        "Shows this value on the line. The line's measured length, the scale and other measurements stay."

    /** "Scale: 1 px = 0.21 cm -> 0.19 cm", or null for an uncalibrated photo or a zero-length line. */
    fun scaleChangeText(ctx: ValueEditorContext, meters: Double): String? {
        val current = ctx.currentMetersPerPixel ?: return null
        if (ctx.segmentPixelLength <= 0.0) return null
        val unit = ctx.displayUnit.takeUnless { it == MeasurementUnit.FEET_INCHES } ?: MeasurementUnit.FEET
        fun px(mPerPx: Double) = "${round(metersToDisplay(mPerPx, unit) * HUNDRED) / HUNDRED}"
        return "Scale: 1 px = ${px(current)} ${unit.symbol()} -> ${px(meters / ctx.segmentPixelLength)} ${unit.symbol()}"
    }

    fun errorText(error: MeasurementValueEdit.Error): String = when (error) {
        MeasurementValueEdit.Error.EMPTY, MeasurementValueEdit.Error.NOT_POSITIVE -> NEEDS_POSITIVE
        MeasurementValueEdit.Error.NOT_A_NUMBER -> HELPER
        MeasurementValueEdit.Error.IMPLAUSIBLE -> IMPLAUSIBLE_VALUE
    }

    /** Undo snackbars never time out under touch exploration or a switch service (UX-61). */
    fun undoDuration(assistiveServiceActive: Boolean): SnackbarDuration =
        if (assistiveServiceActive) SnackbarDuration.Indefinite else SnackbarDuration.Long

    private fun times(v: Double) = "${round(v * TENTH) / TENTH}".removeSuffix(".0")
    private const val HUNDRED = 100.0
}

/** Shows a snackbar with an Undo action using the UX-61 duration rule; returns true when Undo was tapped. */
suspend fun SnackbarHostState.showUndo(message: String, assistiveServiceActive: Boolean): Boolean =
    showSnackbar(
        message = message,
        actionLabel = "Undo",
        withDismissAction = assistiveServiceActive,
        duration = ValueEditorRules.undoDuration(assistiveServiceActive),
    ) == SnackbarResult.ActionPerformed

/** Bottom-sheet host of [MeasurementValueEditorContent]; all dismiss paths call [onDismiss] and change nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementValueEditorSheet(
    context: ValueEditorContext,
    onApply: (ValueEditorResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
    solverFooter: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        MeasurementValueEditorContent(
            context = context,
            onApply = onApply,
            onDismiss = onDismiss,
            saveFailed = saveFailed,
            solverFooter = solverFooter,
        )
    }
}

@Composable
fun MeasurementValueEditorContent(
    context: ValueEditorContext,
    onApply: (ValueEditorResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
    solverFooter: String? = null,
) {
    var text by remember(context) {
        val prefill = context.initialMeters?.let { metersToDisplayString(it, context.displayUnit) }.orEmpty()
        mutableStateOf(TextFieldValue(prefill, TextRange(0, prefill.length)))
    }
    var entryUnit by remember { mutableStateOf(context.displayUnit.takeUnless { it == MeasurementUnit.FEET_INCHES } ?: MeasurementUnit.FEET) }
    var choice by remember { mutableStateOf(ValueEditorRules.initialChoice(context)) }
    var touched by remember { mutableStateOf(false) }

    val parsed = MeasurementValueEdit.parse(text.text, entryUnit)
    val meters = (parsed as? Either.Right)?.value
    val error = (parsed as? Either.Left)?.value
    val blocked = ValueEditorRules.knownDistanceBlockedReason(context)
    val known = choice == ValueEditMode.KNOWN_DISTANCE
    val warning = if (known && meters != null) ValueEditorRules.implausibleScaleMessage(ValueEditorRules.scaleRatio(context, meters)) else null
    val canApply = meters != null && choice != null

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            EditorHeader(context.title, onDismiss)
            EntryRow(
                text = text,
                onTextChange = { text = it; touched = true },
                entryUnit = entryUnit,
                onUnitChange = { entryUnit = it },
                invalid = touched && error != null,
            )
            val helper = when {
                touched && error != null -> ValueEditorRules.errorText(error)
                meters != null && text.text.isNotBlank() -> "Reads as ${metersToDisplayString(meters, context.displayUnit)}"
                else -> ValueEditorRules.HELPER
            }
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = if (touched && error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = "What does this mean?",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp).semantics { heading() },
            )
            Column(Modifier.selectableGroup()) {
                ChoiceRow(
                    label = "Use as known distance",
                    consequence = blocked ?: ValueEditorRules.knownConsequence(context.otherMeasurementCount),
                    detail = if (known && meters != null) ValueEditorRules.scaleChangeText(context, meters) else null,
                    selected = known,
                    enabled = blocked == null,
                    onSelect = { choice = ValueEditMode.KNOWN_DISTANCE },
                )
                ChoiceRow(
                    label = "Change label only",
                    consequence = ValueEditorRules.LABEL_ONLY_CONSEQUENCE,
                    detail = null,
                    selected = choice == ValueEditMode.LABEL_ONLY,
                    enabled = true,
                    onSelect = { choice = ValueEditMode.LABEL_ONLY },
                )
            }
            Notices(warning = warning, saveFailed = saveFailed, solverFooter = solverFooter)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                onClick = { if (meters != null && choice != null) onApply(ValueEditorResult(meters, choice!!, entryUnit)) },
                enabled = canApply,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (warning != null) "Apply anyway" else "Apply") }
        }
    }
}

@Composable
private fun EditorHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
    }
}

@Composable
private fun EntryRow(
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    entryUnit: MeasurementUnit,
    onUnitChange: (MeasurementUnit) -> Unit,
    invalid: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Length") },
            isError = invalid,
            singleLine = true,
            // Text, not Decimal: the Decimal keypad has no ' or " for 5'3".
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
        var open by remember { mutableStateOf(false) }
        Column {
            AssistChip(
                onClick = { open = true },
                label = { Text(entryUnit.symbol()) },
                modifier = Modifier.semantics {
                    role = Role.DropdownList
                    contentDescription = "Entry unit: ${entryUnit.symbol()}. This entry only"
                },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (unit in ValueEditorRules.entryUnits) {
                    DropdownMenuItem(
                        text = { Text(unit.displayName()) },
                        onClick = { onUnitChange(unit); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    consequence: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(consequence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Notices(warning: String?, saveFailed: Boolean, solverFooter: String?) = Column {
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (warning != null) Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary, modifier = live.padding(top = 8.dp))
    if (saveFailed) Text(ValueEditorRules.SAVE_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = live.padding(top = 8.dp))
    if (solverFooter != null) Text(solverFooter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** True when the line shows a user-typed value rather than its measured length. */
fun dev.stapler.stelekit.model.MeasurementAnnotation.isValueEdited(): Boolean =
    value is dev.stapler.stelekit.model.MeasurementValue.Overridden

/** The label text with a pencil glyph in front of an edited value (UX-26); plain text otherwise. */
fun dev.stapler.stelekit.model.MeasurementAnnotation.valueLabelWithMarker(): String? =
    valueDisplay?.let { if (isValueEdited()) "✎ $it" else it }
