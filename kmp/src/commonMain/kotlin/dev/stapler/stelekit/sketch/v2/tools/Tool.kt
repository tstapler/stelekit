// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.SceneElement

/** The frozen tool inventory (requirements.md metric 1, 12 tools plus Point and Arc under OD-8); adding one is a requirements change. */
enum class ToolId { Select, Rectangle, Diamond, Ellipse, Arrow, Line, Freedraw, Text, Image, Eraser, Frame, Hand, Point, Arc }

/**
 * What a finished gesture asks the document to do. Tools never edit the scene; the bridge (Story 1.3.2) maps a
 * [ToolCommand] to an undoable `Command`. Stories 3.2.x add the concrete kinds beside [AddElementCommand].
 */
interface ToolCommand

data class AddElementCommand(val element: SceneElement) : ToolCommand

/** The in-progress shape drawn over the canvas while a gesture runs; rendered through `DrawListBuilder`, never persisted. */
data class ToolPreview(val elements: List<SceneElement>)

/**
 * Outcome of one event. [command] is emitted at most once per gesture, on commit. [preview] is what to show now
 * (null clears it). [next] is the tool's next state; null keeps the current state object.
 */
data class ToolResult(val command: ToolCommand? = null, val preview: ToolPreview? = null, val next: Tool? = null) {
    companion object {
        /** No command, no preview, same state. */
        val Nothing = ToolResult()
    }
}

/**
 * A pure state machine: no Compose, no coroutines, no clock. An immutable instance is one state; [onEvent]
 * returns the following state in [ToolResult.next], so a scenario test is a fold over events.
 */
interface Tool {
    val id: ToolId

    fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult
}
