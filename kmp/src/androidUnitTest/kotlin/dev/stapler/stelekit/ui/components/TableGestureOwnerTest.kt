// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.sheet.isTouchLikePointer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.2: one gesture owner decides tap, link tap and long-press for an inline table. Gradle-only (Compose host). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableGestureOwnerTest {
    @get:Rule
    val rule = createComposeRule()

    private val table = "| Item | Note |\n|---|---|\n| Apple | see [[Page]] |\n| Pear | green |"
    private var started = 0
    private var toggled = 0
    private var longPressed = 0
    private val links = mutableListOf<String>()

    private fun show(
        flagOn: Boolean = true,
        inBlockSelectionMode: Boolean = false,
    ) = rule.setContent {
        MaterialTheme {
            Surface {
                Box(Modifier.width(360.dp)) {
                    TableBlock(
                        content = table,
                        onStartEditing = { started++ },
                        isInSelectionMode = inBlockSelectionMode,
                        onToggleSelect = { toggled++ },
                        onLongPressSelect = { longPressed++ },
                        onLinkClick = { links += it },
                        prefs = TableDisplayPrefs(inlineEditEnabled = flagOn),
                        onCommit = {},
                        inBlockSelectionMode = inBlockSelectionMode,
                    )
                }
            }
        }
    }

    private fun editors() = rule.onAllNodes(hasSetTextAction())

    @Test
    fun linkTap_should_FireOnLinkClickOnceAndNotEdit_When_TapLandsOnLink() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Page", substring = true).performTouchInput {
            click(Offset(width - 36f, height / 2f))
        }
        rule.waitForIdle()
        assertEquals(listOf("Page"), links)
        editors().assertCountEquals(0)
        assertEquals(0, started)
    }

    @Test
    fun plainTap_should_EditThatCellAndNotStartRawEdit() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performTouchInput { click() }
        rule.waitForIdle()
        editors().assertCountEquals(1)
        rule.onNode(hasSetTextAction()).assertTextEquals("Apple")
        assertEquals(0, started)
        assertTrue(links.isEmpty())
    }

    @Test
    fun mouseClick_should_AlsoEditTheCell() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Pear").performMouseInput { click() }
        rule.waitForIdle()
        editors().assertCountEquals(1)
    }

    @Test
    fun longPress_should_SelectBlockOnceAndNotEdit_When_FrameClockIsHeldBack() {
        show()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Apple").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Apple").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, longPressed)
        editors().assertCountEquals(0)
        assertEquals(0, started)
    }

    @Test
    fun flagOff_should_StartRawEditOnTap() {
        show(flagOn = false)
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, started)
        editors().assertCountEquals(0)
    }

    @Test
    fun tap_should_ToggleBlockSelectionNotEdit_When_InBlockSelectionMode() {
        show(inBlockSelectionMode = true)
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, toggled)
        editors().assertCountEquals(0)
        assertEquals(0, started)
        assertEquals(0, longPressed)
    }

    @Test
    fun touchDrag_should_CreateNoSelectionAndNoEdit() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onAllNodes(isSelected()).assertCountEquals(0)
        editors().assertCountEquals(0)
        assertEquals(0, longPressed)
    }

    @Test
    fun tap_should_ShowExactlyOneSelectedCell_When_CellEntersEdit() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Pear").performTouchInput { click() }
        rule.waitForIdle()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
    }

    @Test
    fun pointerType_should_MapTouchAndStylusToTouchAndMouseToHardware() {
        assertTrue(isTouchLikePointer(PointerType.Touch))
        assertTrue(isTouchLikePointer(PointerType.Stylus))
        assertFalse(isTouchLikePointer(PointerType.Mouse))
        assertFalse(isTouchLikePointer(PointerType.Eraser))
    }

    @Test
    fun sheetOwner_should_MakeTapInert() {
        val owner = dev.stapler.stelekit.model.BlockUuid("00000000-0000-0000-0000-0000000000aa")
        rule.setContent {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalTableSheetOwner provides owner) {
                        Box(Modifier.width(360.dp)) {
                            TableBlock(
                                content = table,
                                onStartEditing = { started++ },
                                blockUuid = owner,
                                prefs = TableDisplayPrefs(inlineEditEnabled = true),
                                onCommit = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performTouchInput { click() }
        rule.waitForIdle()
        editors().assertCountEquals(0)
        assertEquals(0, started)
    }
}
