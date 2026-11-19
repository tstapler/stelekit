// jvmTest-reason: desktop-only
// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.moveTo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spike 0.1.4 (Task 0.1.4a), desktop column: logs what Compose Desktop exposes per pointer event.
 * Findings are recorded in project_plans/excalidraw-sketch-engine/research/input-spike.md.
 */
@OptIn(ExperimentalTestApi::class)
class InputSpikeTest {
    private data class Sample(val type: PointerType, val event: PointerEventType, val pressure: Float, val primary: Boolean, val secondary: Boolean, val tertiary: Boolean)

    private fun probe(log: MutableList<Sample>) = Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.first()
                log += Sample(c.type, e.type, c.pressure, e.buttons.isPrimaryPressed, e.buttons.isSecondaryPressed, e.buttons.isTertiaryPressed)
            }
        }
    }

    @Test
    fun mouseReportsTypeMouseAndHover() = runComposeUiTest {
        val log = mutableListOf<Sample>()
        setContent { Box(probe(log).testTag("canvas")) }
        onNodeWithTag("canvas").performMouseInput { moveTo(Offset(10f, 10f)); press(); moveTo(Offset(20f, 20f)); release() }
        waitForIdle()
        println("INPUT-SPIKE desktop mouse: " + log.joinToString(" | ") { "${it.event} type=${it.type} p=${it.pressure} btn=${it.primary}/${it.secondary}/${it.tertiary}" })
        assertTrue(log.isNotEmpty())
        assertTrue(log.all { it.type == PointerType.Mouse }, log.toString())
        assertTrue(log.any { it.event == PointerEventType.Move }, "hover/move is delivered to the mouse path")
    }

    @Test
    fun syntheticTouchReportsTypeTouch() = runComposeUiTest {
        val log = mutableListOf<Sample>()
        setContent { Box(probe(log).testTag("canvas")) }
        onNodeWithTag("canvas").performTouchInput { down(0, Offset(10f, 10f)); moveTo(0, Offset(20f, 20f)); up(0) }
        waitForIdle()
        println("INPUT-SPIKE desktop touch: " + log.joinToString(" | ") { "${it.event} type=${it.type} p=${it.pressure}" })
        assertTrue(log.all { it.type == PointerType.Touch }, log.toString())
    }
}
