// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.ui.sketch.lengthTextOfUnits
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength
import dev.stapler.stelekit.units.MetricUnit

/** What a submitted field does: show the parser's message, move to the next field, or finish with one command (null when nothing changed). */
sealed interface PillStep {
    data class Invalid(val message: String) : PillStep

    data class Next(val kind: PillKind) : PillStep

    data class Done(val command: ResizeElement?) : PillStep
}

/**
 * One on-canvas edit of an element's size: the fields in order (Width then Height), the lengths typed so far and the field
 * on screen. Lengths typed before the last field are held, not applied, so the whole edit lands as ONE undo step on Done and
 * Esc leaves the element untouched. The text a field shows comes from [textOf]; it is never re-derived while the edit is open,
 * so a pointer move or a pan cannot change what the user typed.
 */
class DimensionPillEdit(val element: SceneElement, start: PillKind) {
    private val specs = DimensionPillModel.specs(element)
    private val entered = mutableMapOf<PillKind, Double>()

    /**
     * The display the field is read in: a plain number means this display's unit when it is metric. The host sets it from the
     * unit chip; changing it forgets [stickyUnit], so a unit switch starts the numbers over in the new unit.
     */
    var format: LengthFormat = LengthFormat.FEET_INCHES
        set(value) {
            if (value != field) stickyUnit = null
            field = value
        }

    /** The metric unit last written in this edit (`25 cm`): a later field's plain number reads in it, so `25 cm` then `10` is 10 cm. */
    var stickyUnit: MetricUnit? = null
        private set

    val kinds: List<PillKind> = DimensionPillModel.editKinds(element, start)
    var current: PillKind by mutableStateOf(if (start in kinds) start else kinds.first())
        private set

    val isLast: Boolean get() = current == kinds.last()

    /** The text a field starts with: what was typed there already, else the current size. */
    fun textOf(kind: PillKind, format: LengthFormat = LengthFormat.FEET_INCHES): String {
        entered[kind]?.let { return lengthTextOfUnits(it, format) }
        val spec = specs.first { it.kind == kind }
        return if (MetricLength.isMetric(format)) lengthTextOfUnits(spec.exact, format) else lengthTextOf(spec.sixteenths, format)
    }

    /** The field's text is submitted: Next goes on to the following field, Done on the last. Invalid keeps the edit open and every value as it was. */
    fun submit(text: String, untouched: Boolean = false): PillStep = when (val parsed = DimensionPillModel.parse(text, format, stickyUnit)) {
        is DimensionPillModel.ParsedLength.Invalid -> PillStep.Invalid(parsed.message)
        is DimensionPillModel.ParsedLength.Valid -> {
            // The prefill passed on untouched is not a typed value: a shape of 80.4 units keeps it rather than becoming 80.
            // Text still equal to what an entered value is shown as is not a new value: re-reading it would round a metric value to a sixteenth.
            val shownAsEntered = current in entered && text == textOf(current, format)
            if (!(untouched && current !in entered) && !shownAsEntered) {
                entered[current] = parsed.units
                if (MetricLength.isMetric(format)) MetricLength.explicitUnitOf(text)?.let { stickyUnit = it }
            }
            if (isLast) {
                PillStep.Done(finish())
            } else {
                current = kinds[kinds.indexOf(current) + 1]
                PillStep.Next(current)
            }
        }
    }

    /**
     * Applies what is typed in the open field and every later field as it stands, for an edit that ends without Done (a tap on
     * another shape's value). Invalid keeps the edit open; Done carries the one resize, or null when nothing changed.
     */
    fun commitPending(text: String, untouched: Boolean): PillStep {
        var step = submit(text, untouched)
        while (step is PillStep.Next) step = submit(textOf(current, format), untouched = true)
        return step
    }

    /** Shift+Tab: back to the previous field, keeping what was typed here when it is a valid length; false on the first field. */
    fun back(text: String, untouched: Boolean = false): Boolean {
        val index = kinds.indexOf(current)
        if (index == 0) return false
        if (!(untouched && current !in entered)) (DimensionPillModel.parse(text, format, stickyUnit) as? DimensionPillModel.ParsedLength.Valid)?.let { entered[current] = it.units }
        current = kinds[index - 1]
        return true
    }

    private fun finish(): ResizeElement? {
        // Against the exact size, not the rounded pill: typing "5\"" on a 5.025" shape makes it exactly 5".
        val changed = entered.filter { (kind, value) -> specs.first { it.kind == kind }.exact != value }
        return if (changed.isEmpty()) null else PillResize.commandExact(element, changed)
    }
}

/** What the open size field holds right now; [touched] is false while it still shows the app's own prefill. */
class PillDraft(val edit: DimensionPillEdit, val kind: PillKind, val text: String, val touched: Boolean)

/** The pills' state, held by the editor: whether they show (on by default on a technical sheet) and the edit in progress. */
class DimensionPillsState(private val settings: FingerDrawingSettings? = null) {
    var visible: Boolean by mutableStateOf(settings?.dimensionsOn ?: true)
        private set

    /** Whether the annotations of a technical sheet show on every shape or only on the selected ones; kept per device with [visible]. */
    var scope: DimensionScope by mutableStateOf(if (settings?.dimensionsAll == false) DimensionScope.Selected else DimensionScope.All)
        private set
    var edit: DimensionPillEdit? by mutableStateOf(null)
        private set

    /** A rectangle was just drawn on a technical sheet: its Width field opens once the shape is selected (set by the tool session, cleared on use). */
    var autoOpenFor: ElementId? by mutableStateOf(null)

    /** Tests set this to simulate the soft keyboard covering the bottom of the canvas by this many dp; null reads the real insets. */
    var imeOverlapOverrideDp: Float? by mutableStateOf(null)

    fun toggle() {
        visible = !visible
        if (!visible) edit = null
        persist()
    }

    /** Takes the sheet's own choice when the file holds one; it overrides the per-device default and is not written back to it. */
    fun adopt(sheetChoice: Boolean?) {
        if (sheetChoice == null || sheetChoice == visible) return
        visible = sheetChoice
        if (!visible) edit = null
    }

    fun toggleScope() {
        scope = if (scope == DimensionScope.All) DimensionScope.Selected else DimensionScope.All
        persist()
    }

    private fun persist() = settings?.chooseDimensions(visible, scope == DimensionScope.All)

    fun open(element: SceneElement, kind: PillKind) {
        edit = DimensionPillEdit(element, kind)
    }

    fun close() {
        edit = null
    }

    /** The open field's text, kept so an edit cut short by a tap elsewhere can still be applied. */
    var draft: PillDraft? = null

    /**
     * Applies the typed, unsubmitted size through [apply] before the edit goes away. Returns null when there was nothing to apply or it
     * was applied, else the message to show: the edit then stays open with the typed text.
     */
    fun commitPending(apply: (ResizeElement) -> CommandError?): String? {
        val open = edit ?: return null
        val typed = draft?.takeIf { it.edit === open && it.kind == open.current && it.touched } ?: return null
        return when (val step = open.commitPending(typed.text, untouched = false)) {
            is PillStep.Invalid -> step.message
            is PillStep.Next -> null
            is PillStep.Done -> step.command?.let(apply)?.let { SketchDimensionStrings.Pill.REFUSED }
        }
    }

    /** The pill that gets focus back after Done, Esc or Cancel (not after a tap elsewhere, which already moved focus). */
    var refocus: PillKind? by mutableStateOf(null)

    /** True while the size field has focus: only then does it own Tab and Esc instead of the canvas. */
    var fieldFocused: Boolean by mutableStateOf(false)

    fun finish() {
        refocus = edit?.current
        edit = null
    }
}
