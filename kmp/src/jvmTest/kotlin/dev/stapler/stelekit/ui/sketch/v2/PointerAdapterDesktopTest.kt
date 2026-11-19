// jvmTest-reason: desktop-only
// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.1.2 (UX-07, UX-09): a real Compose Desktop host feeds the mouse through [sketchPointerInput] into the adapter. */
@OptIn(ExperimentalTestApi::class)
class PointerAdapterDesktopTest {
    private val adapter = SketchPointerAdapter()
    private val actions = mutableListOf<PointerAction>()

    private fun host() = Modifier.fillMaxSize().sketchPointerInput(adapter) { actions += it }.testTag("canvas")

    @Test
    fun mouseDrag_should_ActWithNoPressure_And_NotPan() = runComposeUiTest {
        setContent { Box(host()) }
        onNodeWithTag("canvas").performMouseInput { moveTo(Offset(10f, 10f)); press(); moveTo(Offset(40f, 30f)); release() }
        waitForIdle()
        val events = actions.filterIsInstance<PointerAction.ToolInput>().map { it.event }
        val types = events.map { it.type }
        assertTrue(InputType.Down in types && InputType.Up in types, "types=$types")
        assertTrue(events.all { it.pointerKind == PointerKind.Mouse })
        assertNull(events.first { it.type == InputType.Down }.pressure)
        assertEquals(0.0, adapter.viewport.panX)
    }

    @Test
    fun rightClick_should_OpenContextMenuAtThePointer() = runComposeUiTest {
        setContent { Box(host()) }
        onNodeWithTag("canvas").performMouseInput { rightClick(Offset(25f, 35f)) }
        waitForIdle()
        val menu = actions.filterIsInstance<PointerAction.ContextMenu>().first()
        assertEquals(25.0, menu.at.x.value)
        assertEquals(35.0, menu.at.y.value)
        assertTrue(actions.filterIsInstance<PointerAction.ToolInput>().none { it.event.type == InputType.Down })
    }
}
