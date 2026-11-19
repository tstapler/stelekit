// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.ZOrderMove
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

/** The group and z-order actions of the selection bar and the Arrange menu (Task 3.5.1b, UX-11); each maps to a keyboard [KeyAction]. */
enum class ArrangeActionId(val label: String, val keymapId: String, val key: KeyAction) {
    Group("Group", "group", KeyAction.Group),
    Ungroup("Ungroup", "ungroup", KeyAction.Ungroup),
    BringToFront("Bring to front", "bringToFront", KeyAction.Arrange(ZOrderMove.ToFront)),
    BringForward("Bring forward", "bringForward", KeyAction.Arrange(ZOrderMove.Forward)),
    SendBackward("Send backward", "sendBackward", KeyAction.Arrange(ZOrderMove.Backward)),
    SendToBack("Send to back", "sendToBack", KeyAction.Arrange(ZOrderMove.ToBack)),
}

/** One button: [disabledReason] is null when it is enabled, else the text shown beside it and read by a screen reader. */
data class ArrangeAction(val id: ArrangeActionId, val shortcut: String, val disabledReason: String?) {
    val enabled: Boolean get() = disabledReason == null
    val label: String get() = id.label

    /** What a screen reader reads: the label, the shortcut and, when disabled, why. */
    val description: String
        get() = buildString {
            append(label)
            if (shortcut.isNotEmpty()) append(", shortcut ").append(shortcut)
            disabledReason?.let { append(", disabled: ").append(it) }
        }
}

/**
 * Arrange actions over a selection. Enabled state comes from really running the command on a scratch document, so a
 * button is enabled exactly when pressing it would change something; the command itself is the one the keyboard map
 * plans, so the keys, the bar and the menu can never disagree.
 */
object ArrangeModel {
    const val NOTHING_SELECTED = "Select an element first"
    const val NEEDS_TWO = "Select two or more elements"
    const val NOT_GROUPED = "The selection is not grouped"
    const val ALREADY_THERE = "Already there"
    const val ALL_LOCKED = "Locked elements cannot be changed"

    fun actions(scene: SketchSceneV2, selection: List<ElementId>, env: KeymapEnv): List<ArrangeAction> =
        ArrangeActionId.entries.map { id -> ArrangeAction(id, shortcutOf(id, env), disabledReason(id, scene, selection)) }

    /** The plan for [id] on [selection]: a null command when it would do nothing. Locked elements are skipped and reported. */
    fun plan(id: ArrangeActionId, scene: SketchSceneV2, selection: List<ElementId>, ids: ElementIdSource): EditPlan =
        KeyActionPlanner.plan(id.key, scene, selection, ids)

    private fun disabledReason(id: ArrangeActionId, scene: SketchSceneV2, selection: List<ElementId>): String? {
        val present = selection.filter { it in scene }
        if (present.isEmpty()) return NOTHING_SELECTED
        val plan = KeyActionPlanner.plan(id.key, scene, present, ProbeIds())
        val command = plan.command
        if (command == null) {
            return when {
                present.all { it in plan.skippedLocked } -> ALL_LOCKED
                id == ArrangeActionId.Group -> NEEDS_TWO
                else -> NOT_GROUPED
            }
        }
        val changed = command.apply(SketchDocumentV2(scene)).getOrNull()?.changedIds.orEmpty()
        return when {
            changed.isNotEmpty() -> null
            id == ArrangeActionId.Ungroup -> NOT_GROUPED
            else -> ALREADY_THERE
        }
    }

    private fun shortcutOf(id: ArrangeActionId, env: KeymapEnv): String {
        val binding = KeymapSpec.entries.firstOrNull { it.id == id.keymapId }?.bindings?.firstOrNull { KeymapSpec.isAvailable(it, env) }
        return binding?.let { KeymapSpec.displayLabel(it, env) }.orEmpty()
    }

    /** Fresh ids for the probe only; nothing it plans is ever committed. */
    private class ProbeIds : ElementIdSource {
        private var n = 0
        override fun next(): ElementId = ElementId("probe-${n++}")
    }
}
