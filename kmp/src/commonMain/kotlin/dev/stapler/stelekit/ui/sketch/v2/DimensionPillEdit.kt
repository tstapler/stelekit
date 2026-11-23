// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.lengthTextOf

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
    private val entered = mutableMapOf<PillKind, Long>()

    val kinds: List<PillKind> = DimensionPillModel.editKinds(element, start)
    var current: PillKind by mutableStateOf(if (start in kinds) start else kinds.first())
        private set

    val isLast: Boolean get() = current == kinds.last()

    /** The text a field starts with: what was typed there already, else the current size. */
    fun textOf(kind: PillKind): String = lengthTextOf(entered[kind] ?: specs.first { it.kind == kind }.sixteenths)

    /** The field's text is submitted: Next goes on to the following field, Done on the last. Invalid keeps the edit open and every value as it was. */
    fun submit(text: String): PillStep = when (val parsed = DimensionPillModel.parse(text)) {
        is DimensionPillModel.ParsedLength.Invalid -> PillStep.Invalid(parsed.message)
        is DimensionPillModel.ParsedLength.Valid -> {
            entered[current] = parsed.sixteenths
            if (isLast) {
                PillStep.Done(finish())
            } else {
                current = kinds[kinds.indexOf(current) + 1]
                PillStep.Next(current)
            }
        }
    }

    /** Shift+Tab: back to the previous field, keeping what was typed here when it is a valid length; false on the first field. */
    fun back(text: String): Boolean {
        val index = kinds.indexOf(current)
        if (index == 0) return false
        (DimensionPillModel.parse(text) as? DimensionPillModel.ParsedLength.Valid)?.let { entered[current] = it.sixteenths }
        current = kinds[index - 1]
        return true
    }

    private fun finish(): ResizeElement? {
        val changed = entered.filter { (kind, value) -> specs.first { it.kind == kind }.sixteenths != value }
        return if (changed.isEmpty()) null else PillResize.command(element, changed)
    }
}

/** The pills' state, held by the editor: whether they show (on by default on a technical sheet) and the edit in progress. */
class DimensionPillsState {
    var visible: Boolean by mutableStateOf(true)
    var edit: DimensionPillEdit? by mutableStateOf(null)
        private set

    /** A rectangle was just drawn on a technical sheet: its Width field opens once the shape is selected (set by the tool session, cleared on use). */
    var autoOpenFor: ElementId? by mutableStateOf(null)

    /** Tests set this to simulate the soft keyboard covering the bottom of the canvas by this many dp; null reads the real insets. */
    var imeOverlapOverrideDp: Float? by mutableStateOf(null)

    fun toggle() {
        visible = !visible
        if (!visible) edit = null
    }

    fun open(element: SceneElement, kind: PillKind) {
        edit = DimensionPillEdit(element, kind)
    }

    fun close() {
        edit = null
    }
}
