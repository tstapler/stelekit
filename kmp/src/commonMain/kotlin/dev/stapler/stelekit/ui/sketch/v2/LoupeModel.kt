// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import kotlin.math.hypot

/** Where the touch loupe sits: [centre] is the bubble's centre in canvas pixels, [side] says which way it was moved off the finger. */
data class LoupePlacement(val centre: ScreenPx, val side: LoupeSide)

enum class LoupeSide { Above, Right, Left, Below }

/** Where the loupe is in its life: nothing, a finger down that has not yet earned a bubble, or the bubble showing. */
enum class LoupePhase { Hidden, Armed, Showing }

/**
 * One touch contact the loupe follows. [at] is the point the tool uses, in canvas pixels; [start] is where the finger went down.
 * Only a touch that reaches a tool is ever armed, so a pan or a pinch (which the adapter keeps from the tool) never shows a loupe.
 */
data class LoupeState(
    val phase: LoupePhase = LoupePhase.Hidden,
    val pointerId: Int = 0,
    val start: ScreenPx = ScreenPx(0.0, 0.0),
    val at: ScreenPx = ScreenPx(0.0, 0.0),
) {
    val visible: Boolean get() = phase == LoupePhase.Showing
}

/** Sizes in dp and the rules for when and where the loupe shows; every pixel value comes from dp times `pxPerDp`. */
object LoupeSpec {
    const val DIAMETER_DP = 112.0

    /** Magnification of the scene around the contact point, on top of the editor's own zoom. */
    const val MAGNIFICATION = 2.5

    /** Clear space between the fingertip and the bubble's nearest edge, so the finger does not cover it. */
    const val GAP_DP = 36.0
    const val MARGIN_DP = 8.0

    /** A touch that moves this far (dp) from where it went down is a drag, so the bubble shows at once. */
    const val MOVE_SLOP_DP = 6.0

    /** A touch held this long (ms) without moving shows the bubble, so a precise placement can be checked before moving. */
    const val HOLD_MS = 180L

    private const val HALF = 2.0
    private const val SIDE_RAISE = 3.0

    fun diameterPx(pxPerDp: Double): Double = DIAMETER_DP * pxPerDp

    /**
     * Places the bubble for a contact at [contact] on a [width] x [height] px canvas. Order of preference: above the finger (clamped
     * sideways), then beside it on the roomier side, then the other side, then below. A preference is taken only when the whole
     * bubble fits inside the margin; when none fits (a canvas smaller than the bubble) the farthest clamped candidate wins.
     */
    fun place(contact: ScreenPx, width: Double, height: Double, pxPerDp: Double): LoupePlacement {
        val d = diameterPx(pxPerDp)
        val half = d / HALF
        val gap = GAP_DP * pxPerDp
        val margin = MARGIN_DP * pxPerDp
        val towardRoom = if (contact.x < width / HALF) 1.0 else -1.0
        val sideY = contact.y - d / SIDE_RAISE
        val raw = listOf(
            LoupeSide.Above to ScreenPx(contact.x, contact.y - gap - half),
            (if (towardRoom > 0) LoupeSide.Right else LoupeSide.Left) to ScreenPx(contact.x + towardRoom * (gap + half), sideY),
            (if (towardRoom > 0) LoupeSide.Left else LoupeSide.Right) to ScreenPx(contact.x - towardRoom * (gap + half), sideY),
            LoupeSide.Below to ScreenPx(contact.x, contact.y + gap + half),
        )
        val clamped = raw.map { (side, c) -> LoupePlacement(ScreenPx(clamp(c.x, half + margin, width - half - margin), clamp(c.y, half + margin, height - half - margin)), side) }
        val fitting = clamped.indices.firstOrNull { fits(raw[it].second, Room(width, height, half, margin), clamped[it].side) }
        if (fitting != null) return clamped[fitting]
        return clamped.maxBy { distance(it.centre, contact) }
    }

    /** True when the candidate [c] keeps the bubble in the canvas on the axis it was moved along (the other axis is clamped, not rejected). */
    private fun fits(c: ScreenPx, room: Room, side: LoupeSide): Boolean = when (side) {
        LoupeSide.Above -> c.y - room.half >= room.margin
        LoupeSide.Below -> c.y + room.half <= room.height - room.margin
        LoupeSide.Left, LoupeSide.Right -> c.x - room.half >= room.margin && c.x + room.half <= room.width - room.margin
    }

    private class Room(val width: Double, val height: Double, val half: Double, val margin: Double)

    /** [value] held between [low] and [high]; a range that is inverted (a canvas smaller than the bubble) collapses to its middle. */
    private fun clamp(value: Double, low: Double, high: Double): Double = if (low > high) (low + high) / HALF else value.coerceIn(low, high)

    private fun distance(a: ScreenPx, b: ScreenPx): Double = hypot(a.x - b.x, a.y - b.y)

    /** True when the bubble may show for [state]: the setting is on, and the touch has moved or been held (phase Showing). */
    fun shouldShow(state: LoupeState, enabled: Boolean): Boolean = enabled && state.visible

    /** Folds one tool input into [state]; [at] is the input's point in canvas pixels. Pure, so the gating is testable without Compose. */
    fun reduce(state: LoupeState, event: InputEvent, at: ScreenPx, pxPerDp: Double): LoupeState = when (event.type) {
        InputType.Down -> down(state, event, at)
        InputType.Move -> move(state, event, at, pxPerDp)
        else -> LoupeState()
    }

    private fun down(state: LoupeState, event: InputEvent, at: ScreenPx): LoupeState = when {
        event.pointerKind != PointerKind.Touch -> LoupeState()
        // A second finger means pinch or pan: the tool is cancelled, so the loupe goes too.
        state.phase != LoupePhase.Hidden && state.pointerId != event.pointerId -> LoupeState()
        else -> LoupeState(LoupePhase.Armed, event.pointerId, at, at)
    }

    private fun move(state: LoupeState, event: InputEvent, at: ScreenPx, pxPerDp: Double): LoupeState {
        if (state.phase == LoupePhase.Hidden || state.pointerId != event.pointerId) return state
        val travelled = distance(state.start, at)
        val showing = state.visible || travelled >= MOVE_SLOP_DP * pxPerDp
        return state.copy(phase = if (showing) LoupePhase.Showing else LoupePhase.Armed, at = at)
    }

    /** The hold timer fired for [pointerId]: an armed touch that is still down shows the bubble. */
    fun holdElapsed(state: LoupeState, pointerId: Int): LoupeState =
        if (state.phase == LoupePhase.Armed && state.pointerId == pointerId) state.copy(phase = LoupePhase.Showing) else state

    /**
     * The transform that magnifies a full-canvas layer by [MAGNIFICATION] about [contact] and moves that point to [bubbleCentre]:
     * a point at offset `p` from the contact lands at `bubbleCentre + MAGNIFICATION * p`.
     */
    fun layerTransform(contact: ScreenPx, bubbleCentre: ScreenPx): LoupeTransform =
        LoupeTransform(MAGNIFICATION, contact, bubbleCentre.x - contact.x, bubbleCentre.y - contact.y)
}

/** Scale [scale] about [origin] (canvas pixels), then a shift of ([translateX], [translateY]) pixels. */
data class LoupeTransform(val scale: Double, val origin: ScreenPx, val translateX: Double, val translateY: Double) {
    fun map(p: ScreenPx): ScreenPx = ScreenPx(origin.x + scale * (p.x - origin.x) + translateX, origin.y + scale * (p.y - origin.y) + translateY)
}
