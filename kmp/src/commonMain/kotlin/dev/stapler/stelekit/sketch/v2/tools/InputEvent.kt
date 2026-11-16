// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint

/** Splits navigate from act: touch pans unless Finger drawing is on, a stylus or mouse acts, an eraser tip erases. */
enum class PointerKind { Touch, Mouse, Stylus, Eraser }

enum class InputType {
    Down,
    Move,
    Up,

    /** The platform cancelled the gesture (palm rejection, focus loss). */
    Cancel,

    /** Esc: abandons the gesture in progress (UX-08). */
    Escape,
}

data class Modifiers(val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) {
    companion object {
        val None = Modifiers()
    }
}

/**
 * A normalised input event in scene space, produced by the pointer adapter (Story 3.1.2). [point] is where the event
 * happened; an [InputType.Escape] event has no position and carries the origin. [timestamp] is milliseconds from a
 * monotonic source the adapter owns, never the wall clock.
 */
data class InputEvent(
    val type: InputType,
    val point: ScenePoint,
    val pressure: Double? = null,
    val pointerKind: PointerKind = PointerKind.Mouse,
    val pointerId: Int = PRIMARY_POINTER,
    val timestamp: Long = 0L,
    val modifiers: Modifiers = Modifiers.None,
) {
    init {
        require(pressure?.isFinite() ?: true) { "pressure must be finite" }
    }

    companion object {
        const val PRIMARY_POINTER = 0

        val Origin = ScenePoint(SceneUnit.ZERO, SceneUnit.ZERO)

        fun escape(timestamp: Long = 0L): InputEvent = InputEvent(InputType.Escape, Origin, timestamp = timestamp)
    }
}
