// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.annotation.ElementFrame
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.PI

/** The numbers of the Stats section for one selected element (Task 3.5.4b): position, size and angle in degrees. */
data class ElementStats(val x: Double, val y: Double, val width: Double, val height: Double, val angleDegrees: Double) {
    companion object {
        private const val HALF_TURN_DEGREES = 180.0

        /**
         * Null for an element with no extent (an empty line). A box or ellipse reports its own unrotated box, the one
         * [ResizeElement] writes, so a rotated element's width and height are not its rotated bounds. The angle is 0 for a kind that cannot rotate.
         */
        fun of(element: SceneElement): ElementStats? =
            ElementFrame.of(element)?.let { ElementStats(it.x, it.y, it.width, it.height, it.angle * HALF_TURN_DEGREES / PI) }

        fun radians(degrees: Double): Double = degrees * PI / HALF_TURN_DEGREES
    }
}

enum class StatField { X, Y, Width, Height, Angle }

/** A typed value that is not a finite number keeps the previous value and says so inline (ux.md section 7). */
object ElementStatsEdits {
    const val NOT_A_NUMBER = "Not a number. Try 12.5"

    /** The command for committing [text] into [field] of [element], or the message to show beside the field. */
    fun commit(element: SceneElement, field: StatField, text: String): CommitResult {
        val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return CommitResult.Invalid(NOT_A_NUMBER)
        val stats = ElementStats.of(element) ?: return CommitResult.Invalid("This element has no size")
        val command: Command = when (field) {
            StatField.X -> MoveElements(listOf(element.id), value - stats.x, 0.0)
            StatField.Y -> MoveElements(listOf(element.id), 0.0, value - stats.y)
            StatField.Width -> ResizeElement(element.id, stats.x, stats.y, value, stats.height)
            StatField.Height -> ResizeElement(element.id, stats.x, stats.y, stats.width, value)
            StatField.Angle -> RotateElement(element.id, ElementStats.radians(value))
        }
        return CommitResult.Edit(command)
    }

    sealed interface CommitResult {
        data class Edit(val command: Command) : CommitResult

        data class Invalid(val message: String) : CommitResult
    }
}
