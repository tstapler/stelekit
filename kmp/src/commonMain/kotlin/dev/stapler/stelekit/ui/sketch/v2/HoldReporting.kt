// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports true to [onHold] while a pointer is down on this node and false when it lifts or is cancelled (also when
 * the node leaves composition), without consuming the press, so a tap on it still clicks. The node's pointers are its own: a
 * finger resting here does not reach the canvas, so it never reads as a pinch or a second drawing finger there. Null adds nothing.
 */
@Composable
internal fun Modifier.holdReporting(onHold: ((Boolean) -> Unit)?): Modifier {
    if (onHold == null) return this
    val current = rememberUpdatedState(onHold)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            current.value(true)
            try {
                // Held until this pointer lifts or is cancelled, wherever it drifts and whoever consumed it: a resting thumb moves a little.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.id == down.id && it.pressed })
            } finally {
                current.value(false)
            }
        }
    }
}
