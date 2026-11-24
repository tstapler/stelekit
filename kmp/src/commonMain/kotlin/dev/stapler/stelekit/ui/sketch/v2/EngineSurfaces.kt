// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.binding.UnbindArrowEnd
import dev.stapler.stelekit.sketch.v2.commands.ContainerText
import dev.stapler.stelekit.sketch.v2.commands.SetArrowLabel
import dev.stapler.stelekit.sketch.v2.commands.SetContainerText
import dev.stapler.stelekit.sketch.v2.commands.SetTextContent
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.TextLimitMessage
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEngineStrings

/** The label being edited: the shape or arrow it names, whether it had one already and the text typed so far. */
data class LabelEdit(val target: ElementId, val arrow: Boolean, val hadLabel: Boolean, val text: String, val standalone: Boolean = false)

/**
 * The editor's commands that live in the engine and need a surface of their own: the label of a shape or an arrow (typed after a
 * double click or from the selection menu), freeing one end of a bound arrow, and point editing of a selected line. Reads and
 * edits the document only through the controller, so each action is one undoable step. Held by [EditorExtras].
 */
class EngineSurfaces(private val controller: SketchEditorController, private val metrics: TextMetricsProvider) {
    var labelEdit: LabelEdit? by mutableStateOf(null)
        private set

    init {
        controller.tools.onDoubleClick = ::onDoubleClick
        controller.tools.interiorAt = ::containerAt
    }

    private fun element(id: ElementId): SceneElement? = controller.doc.scene[id]

    private fun isContainer(e: SceneElement): Boolean = e is RectElement || e is EllipseElement || e is DiamondElement

    private fun labellable(e: SceneElement): Boolean = isContainer(e) || e is ArrowElement

    private fun isStandaloneText(e: SceneElement): Boolean = e is TextElement && e.containerId == null

    /** The topmost unlocked shape whose bounds hold [point]; a transparent shape is not hit by a press inside it, but a double click there still labels it. */
    private fun containerAt(point: ScenePoint): ElementId? = controller.doc.scene.elementsInOrder.asReversed().firstOrNull { e ->
        isContainer(e) && !e.base.locked && SceneBounds.of(e)?.let { point.x.value in it.minX..it.maxX && point.y.value in it.minY..it.maxY } == true
    }?.id

    private fun onDoubleClick(id: ElementId): Boolean {
        val e = element(id) ?: return false
        return e.base.groupIds.isEmpty() && (openLabel(id) || openText(id))
    }

    /** Opens the words of the stand-alone text [id] for editing; false for anything else, a locked text or a read-only sheet. */
    fun openText(id: ElementId): Boolean {
        val e = element(id) as? TextElement ?: return false
        if (controller.readOnly || e.base.locked || e.containerId != null) return false
        labelEdit = LabelEdit(id, arrow = false, hadLabel = false, text = e.originalText, standalone = true)
        return true
    }

    /** Opens the label of the shape or arrow [id]; false when it cannot carry one or the sheet is read-only. */
    fun openLabel(id: ElementId): Boolean {
        val e = element(id) ?: return false
        if (controller.readOnly || e.base.locked || !labellable(e)) return false
        val existing = ContainerText.labelOf(controller.doc.scene, id)
        labelEdit = LabelEdit(id, e is ArrowElement, existing != null, existing?.originalText.orEmpty())
        return true
    }

    /** Takes the typed text; text over the scene's cap is refused with the cap named, never cut. */
    fun typeLabel(text: String) {
        val edit = labelEdit ?: return
        val cap = controller.doc.scene.limits.maxTextChars
        if (text.length > cap) {
            controller.notifier.toast(SketchEngineStrings.Label.TOO_LONG_KEY, TextLimitMessage.tooLong(cap))
            return
        }
        labelEdit = edit.copy(text = text)
    }

    fun commitLabel() {
        val edit = labelEdit ?: return
        labelEdit = null
        if (edit.standalone) {
            controller.submit(SetTextContent(edit.target, edit.text, metrics))
            return
        }
        val textId = controller.ids.next()
        val command = if (edit.arrow) {
            SetArrowLabel(edit.target, textId, edit.text, metrics)
        } else {
            SetContainerText(edit.target, textId, edit.text, metrics)
        }
        controller.submit(command)
    }

    /** Removes the label (a blank text is the engine's removal). */
    fun removeLabel() {
        labelEdit = labelEdit?.copy(text = "")
        commitLabel()
    }

    fun cancelLabel() {
        labelEdit = null
    }

    /** The selected arrow, when exactly one is selected. */
    private fun selectedArrow(): ArrowElement? = controller.ui.selection.singleOrNull()?.let { element(ElementId(it)) as? ArrowElement }

    private fun selectedLine(): LineElement? = controller.ui.selection.singleOrNull()?.let { element(ElementId(it)) as? LineElement }

    /** The ends of the selected arrow that are bound to a shape. */
    fun boundEnds(): Set<BindingEnd> = selectedArrow()?.let { a ->
        buildSet {
            if (a.startBinding != null) add(BindingEnd.Start)
            if (a.endBinding != null) add(BindingEnd.End)
        }
    }.orEmpty()

    fun unbind(end: BindingEnd) {
        val arrow = selectedArrow() ?: return
        if (controller.submit(UnbindArrowEnd(arrow.id, end)) == null) {
            controller.notifier.toast(SketchEngineStrings.Unbind.DONE_KEY, SketchEngineStrings.Unbind.DONE)
        }
    }

    /** Hands the pointer to point editing of the selected line; false when no single line is selected. */
    fun editLinePoints(): Boolean {
        val line = selectedLine() ?: return false
        if (controller.readOnly || line.base.locked) return false
        controller.tools.editLinePoints(line.id)
        controller.notifier.toast(SketchEngineStrings.LinePoints.HINT_KEY, SketchEngineStrings.LinePoints.HINT)
        return true
    }

    /** The engine entries to add to the selection menu for the current selection. */
    fun menuItems(): List<SelectionAction> = buildList {
        val single = controller.ui.selection.singleOrNull()?.let { element(ElementId(it)) }
        if (single != null && (labellable(single) || isStandaloneText(single))) add(SelectionAction.EditLabel)
        if (single is LineElement) add(SelectionAction.EditPoints)
        val ends = boundEnds()
        if (BindingEnd.Start in ends) add(SelectionAction.UnbindStart)
        if (BindingEnd.End in ends) add(SelectionAction.UnbindEnd)
    }

    /** Runs the menu entry [action] when it is one of this class's; false otherwise. */
    fun run(action: SelectionAction): Boolean = when (action) {
        SelectionAction.EditLabel -> true.also { controller.ui.selection.singleOrNull()?.let { openLabel(ElementId(it)) || openText(ElementId(it)) } }
        SelectionAction.EditPoints -> true.also { editLinePoints() }
        SelectionAction.UnbindStart -> true.also { unbind(BindingEnd.Start) }
        SelectionAction.UnbindEnd -> true.also { unbind(BindingEnd.End) }
        else -> false
    }
}
