// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.PanelTab
import dev.stapler.stelekit.ui.sketch.v2.PlaceRect
import dev.stapler.stelekit.ui.sketch.v2.placed
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings

/** What the tour popover shows for the step on screen; the overlay only draws it. */
data class TourPresentation(
    val tourTitle: String,
    val indicator: String,
    val title: String,
    val body: String,
    /** What a screen reader says for the step: the indicator, the title and the text as one string. */
    val announcement: String,
    val last: Boolean,
    val canGoBack: Boolean,
) {
    companion object {
        /** The presentation of [controller]'s current step, or null when no tour is showing. */
        fun of(controller: TourController): TourPresentation? {
            val tour = controller.active ?: return null
            val step = controller.step ?: return null
            val indicator = SketchGuideStrings.indicator(controller.stepIndex + 1, tour.steps.size)
            return TourPresentation(tour.title, indicator, step.title, step.body, "$indicator. ${step.title}. ${step.body}", controller.isLast, controller.stepIndex > 0)
        }
    }
}

/** Where the popover and ring go: the anchor in the overlay's own space, and the popover's top-left. */
object TourPlacement {
    /** [anchor] (root pixels) relative to the overlay's [origin]; null when the step has no anchor on screen. */
    fun relative(anchor: PlaceRect?, origin: PlaceRect): PlaceRect? =
        anchor?.let { PlaceRect(it.left - origin.left, it.top - origin.top, it.right - origin.left, it.bottom - origin.top) }

    /** The popover's top-left: beside the anchor on the side with room, or centred in [container] when there is no anchor. */
    fun spot(anchor: PlaceRect?, width: Float, height: Float, container: PlaceRect, direction: LayoutDirection): Pair<Float, Float> =
        if (anchor != null) placed(anchor, width, height, container, direction) else ((container.width - width) / 2f) to ((container.height - height) / 2f)
}

/** Which feature each place in the editor counts as the first use of. */
object GuideTriggers {
    fun ofPanel(tab: PanelTab?): String? = when (tab) {
        PanelTab.Constraints -> GuideFeatures.CONSTRAINTS
        PanelTab.Technical -> GuideFeatures.TECHNICAL_TAB
        else -> null
    }
}

/** Starts a feature's tour the first time the user reaches it: a constraint tool, menu or panel, the Technical tab, a selected line, or the first shape selected on a technical sheet. */
@Composable
fun TourTriggers(tours: TourController?, tool: ToolId, constrainMenuOpen: Boolean, panel: PanelTab?, lineSelected: Boolean, technicalShapeSelected: Boolean = false) {
    if (tours == null) return
    LaunchedEffect(tool) { GuideFeatures.ofTool(tool)?.let(tours::onFeatureFirstUse) }
    LaunchedEffect(constrainMenuOpen) { if (constrainMenuOpen) tours.onFeatureFirstUse(GuideFeatures.CONSTRAINTS) }
    LaunchedEffect(panel) { GuideTriggers.ofPanel(panel)?.let(tours::onFeatureFirstUse) }
    LaunchedEffect(lineSelected) { if (lineSelected) tours.onFeatureFirstUse(GuideFeatures.POINT_DRAG) }
    LaunchedEffect(technicalShapeSelected) { if (technicalShapeSelected) tours.onFeatureFirstUse(GuideFeatures.TECHNICAL_SHAPE) }
}

/** What running a search result does in the editor; the host implements it over its controller. */
interface GuideEffects {
    fun undo()
    fun redo()
    fun delete()
    fun constrain()
    fun shortcuts()
    fun goToElement()
    fun fit()
    fun grid()
    fun snap()
    fun export()
    fun import()
    fun useTool(tool: ToolId)
}

object GuideRunner {
    /** The editor actions this host can offer in search, given which optional ones it supports. */
    fun offered(constraints: Boolean, export: Boolean, import: Boolean): List<String> = buildList {
        addAll(listOf(GuideActions.UNDO, GuideActions.REDO, GuideActions.DELETE, GuideActions.SHORTCUTS, GuideActions.GO_TO_ELEMENT, GuideActions.FIT, GuideActions.GRID, GuideActions.SNAP))
        if (constraints) add(GuideActions.CONSTRAIN)
        if (export) add(GuideActions.EXPORT)
        if (import) add(GuideActions.IMPORT)
    }

    /** Runs [action]; a topic opens inside the dialog, so it does nothing here. */
    fun run(action: SearchAction, fx: GuideEffects) {
        when (action) {
            is SearchAction.UseTool -> fx.useTool(action.tool)
            is SearchAction.OpenTopic -> Unit
            is SearchAction.Run -> when (action.action) {
                GuideActions.UNDO -> fx.undo()
                GuideActions.REDO -> fx.redo()
                GuideActions.DELETE -> fx.delete()
                GuideActions.CONSTRAIN -> fx.constrain()
                GuideActions.SHORTCUTS -> fx.shortcuts()
                GuideActions.GO_TO_ELEMENT -> fx.goToElement()
                GuideActions.FIT -> fx.fit()
                GuideActions.GRID -> fx.grid()
                GuideActions.SNAP -> fx.snap()
                GuideActions.EXPORT -> fx.export()
                GuideActions.IMPORT -> fx.import()
            }
        }
    }
}
