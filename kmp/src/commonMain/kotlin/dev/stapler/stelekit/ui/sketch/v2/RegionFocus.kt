// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

/** One entry of the tab order once the walkthrough popover is part of it: a region of the editor, or the popover itself. */
sealed interface TabItem {
    data class Region(val stop: TabStop) : TabItem

    data object Popover : TabItem
}

/**
 * The regions of the editor that can take keyboard focus by name (D-52, UX-117): F6 and the walkthrough popover move focus to
 * a region through its requester. A region registers while it is composed ([region] and [mark]), so [present] is exactly the
 * set of regions on screen and a retired region is never a target. Pure Compose state apart from the requesters.
 */
class RegionFocus(seed: Map<TabStop, FocusRequester> = emptyMap()) {
    private val requesters = HashMap<TabStop, FocusRequester>(seed)
    private val shown = mutableStateSetOf<TabStop>()

    /** The regions composed right now. */
    val present: Set<TabStop> get() = shown.toSet()

    fun requester(stop: TabStop): FocusRequester = requesters.getOrPut(stop) { FocusRequester() }

    internal fun register(stop: TabStop) {
        shown += stop
    }

    internal fun unregister(stop: TabStop) {
        shown -= stop
    }

    /** Moves focus into [stop]; false when it is not on screen or refused the focus. */
    fun focus(stop: TabStop): Boolean = stop in shown && runCatching { requester(stop).requestFocus() }.isSuccess

    /** The requester of the first present region after [stop] in the tab order (before it when [backward]), or null. */
    fun neighbour(stop: TabStop, backward: Boolean): FocusRequester? {
        val order = KeyboardRegions.tabOrder(present).filter { it in shown }
        val at = order.indexOf(stop)
        val next = if (backward) order.getOrNull(at - 1) else order.getOrNull(at + 1)
        return next?.let(::requester)
    }
}

/**
 * Marks a container as the region [stop]: focus can be moved into it by name and its controls form one focus group. [onExit]
 * overrides where Tab leaves the group (the walkthrough sends the anchor's Tab to its popover).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.region(focus: RegionFocus, stop: TabStop, onExit: ((FocusDirection) -> FocusRequester)? = null): Modifier {
    DisposableEffect(focus, stop) {
        focus.register(stop)
        onDispose { focus.unregister(stop) }
    }
    return this
        .focusProperties { if (onExit != null) exit = onExit }
        .focusRequester(focus.requester(stop))
        .focusGroup()
}

/** Registers [stop] as present for a region that is already focusable by itself (the canvas, the overflow button). */
@Composable
fun Modifier.mark(focus: RegionFocus, stop: TabStop): Modifier {
    DisposableEffect(focus, stop) {
        focus.register(stop)
        onDispose { focus.unregister(stop) }
    }
    return this
}
