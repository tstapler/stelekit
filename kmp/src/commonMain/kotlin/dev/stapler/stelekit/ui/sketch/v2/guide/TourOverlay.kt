// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.HighlightRing
import dev.stapler.stelekit.ui.sketch.v2.PlaceRect
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import dev.stapler.stelekit.ui.sketch.v2.placed
import dev.stapler.stelekit.ui.sketch.v2.rootRect
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings
import kotlin.math.roundToInt

object TourTags {
    const val POPOVER = "sketch.tour.popover"
    const val TITLE = "sketch.tour.title"
    const val BODY = "sketch.tour.body"
    const val INDICATOR = "sketch.tour.indicator"
    const val BACK = "sketch.tour.back"
    const val NEXT = "sketch.tour.next"
    const val DONE = "sketch.tour.done"
    const val SKIP = "sketch.tour.skip"
    const val SKIP_ALL = "sketch.tour.skipall"
}

private val POPOVER_WIDTH = 320.dp
private val TARGET = 44.dp
private val ELEVATION = 6.dp

/**
 * The popover and highlight ring of whichever tour [controller] is showing, drawn over the whole shell like the first-run
 * walkthrough and placed by the same [PopoverPlacement]. Non-modal; every tour can be skipped from every step, and Esc skips it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TourOverlay(controller: TourController, modifier: Modifier = Modifier) {
    val tour = controller.active ?: return
    val step = controller.step ?: return
    var origin by remember { mutableStateOf(PlaceRect(0f, 0f, 0f, 0f)) }
    val direction = LocalLayoutDirection.current
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.rootRect() }) {
        val anchor = step.anchor?.let { controller.anchors[it] }?.let { PlaceRect(it.left - origin.left, it.top - origin.top, it.right - origin.left, it.bottom - origin.top) }
        if (anchor != null) HighlightRing(anchor)
        val container = PlaceRect(0f, 0f, origin.width, origin.height)
        val primary = remember { FocusRequester() }
        LaunchedEffect(tour.id, controller.stepIndex) { runCatching { primary.requestFocus() } }
        TourPopover(controller, tour, step, anchor, container, direction, primary)
    }
}

@Composable
private fun TourPopover(
    controller: TourController,
    tour: Tour,
    step: TourStep,
    anchor: PlaceRect?,
    container: PlaceRect,
    direction: LayoutDirection,
    primary: FocusRequester,
) {
    Surface(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val w = placeable.width.toFloat()
                val h = placeable.height.toFloat()
                val spot = if (anchor != null) placed(anchor, w, h, container, direction) else ((container.width - w) / 2f) to ((container.height - h) / 2f)
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(IntOffset(spot.first.roundToInt(), spot.second.roundToInt())) }
            }
            .widthIn(max = POPOVER_WIDTH)
            .testTag(TourTags.POPOVER)
            .onPreviewKeyEvent { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Escape).also { if (it) controller.finish() } }
            .semantics { paneTitle = tour.title },
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = ELEVATION,
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val indicator = SketchGuideStrings.indicator(controller.stepIndex + 1, tour.steps.size)
            Text(indicator, Modifier.testTag(TourTags.INDICATOR), style = MaterialTheme.typography.labelMedium)
            Text(step.title, Modifier.testTag(TourTags.TITLE), style = MaterialTheme.typography.titleSmall)
            Text(
                step.body,
                Modifier.testTag(TourTags.BODY).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "$indicator. ${step.title}. ${step.body}" },
                style = MaterialTheme.typography.bodyMedium,
            )
            TourButtons(controller, primary)
        }
    }
}

@Composable
private fun TourButtons(controller: TourController, primary: FocusRequester) {
    val min = Modifier.defaultMinSize(minWidth = TARGET, minHeight = TARGET)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        TextButton(onClick = controller::back, enabled = controller.stepIndex > 0, modifier = min.testTag(TourTags.BACK)) { Text(SketchGuideStrings.BACK) }
        TextButton(onClick = controller::next, modifier = min.focusRequester(primary).testTag(if (controller.isLast) TourTags.DONE else TourTags.NEXT)) {
            Text(if (controller.isLast) SketchGuideStrings.DONE else SketchGuideStrings.NEXT)
        }
        TextButton(onClick = controller::finish, modifier = min.testTag(TourTags.SKIP)) { Text(SketchGuideStrings.SKIP) }
        TextButton(onClick = controller::skipAll, modifier = min.testTag(TourTags.SKIP_ALL)) { Text(SketchGuideStrings.DONT_SHOW) }
    }
}

/** Reports where [stop] sits so a tour step anchored to it can point at it. */
fun Modifier.tourAnchor(controller: TourController, stop: TabStop): Modifier = onGloballyPositioned { it.rootRect().let { r -> controller.anchors[stop] = r } }
