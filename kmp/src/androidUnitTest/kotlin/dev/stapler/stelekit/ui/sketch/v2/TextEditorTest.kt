// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.geometry.Offset
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.tools.TextEditSession
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.4.1 (UX-20 to UX-22, UX-23, UX-124): the in-place text overlay. Robolectric, so no display is needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 * Soft-keyboard lift (IME inset) is not observable under Robolectric and is an owner device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TextEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun origin(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private var commits = 0

    private fun show(session: TextEditSession, hardwareKeyboard: Boolean = false, viewport: SketchViewport = SketchViewport.of()) {
        rule.setContent { TextEditorOverlay(session, viewport, hardwareKeyboard, onCommit = { commits++ }) }
        rule.waitForIdle()
    }

    @Test
    fun typing_should_FeedTheSession_IncludingShortcutLettersAndNewlines() {
        val session = TextEditSession(origin(10, 10))
        show(session)
        rule.onNodeWithTag("sketch.text.field").performTextInput("rect\nline")
        assertEquals("rect\nline", session.text)
    }

    @Test
    fun touchMode_should_ShowDoneAndTouchHint_And_DoneShouldCommit() {
        show(TextEditSession(origin(0, 0)))
        rule.onNodeWithTag("sketch.text.hint").assertTextEquals("Tap Done or tap outside to finish")
        rule.onNodeWithTag("sketch.text.done").assertIsDisplayed().performClick()
        assertEquals(1, commits)
    }

    @Test
    fun hardwareKeyboard_should_ShowEscHintWithoutDone_And_EscShouldCommit() {
        show(TextEditSession(origin(0, 0)), hardwareKeyboard = true)
        rule.onNodeWithTag("sketch.text.hint").assertTextEquals("Esc to finish")
        rule.onNodeWithTag("sketch.text.done").assertDoesNotExist()
        rule.onNodeWithTag("sketch.text.field").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, commits)
    }

    @Test
    fun tapOutside_should_Commit() {
        show(TextEditSession(origin(0, 0)))
        rule.onNodeWithTag("sketch.text.scrim").performTouchInput { click(Offset(width - 5f, height - 5f)) }
        assertEquals(1, commits)
    }

    @Test
    fun pastingOverTheLimit_should_BeRefusedWithADialog_AndKeepTheOldText() {
        val session = TextEditSession(origin(0, 0), "Hello")
        show(session)
        val tooLong = "x".repeat(SceneLimits.Default.maxTextChars + 1)
        rule.onNodeWithTag("sketch.text.field").performTextInput(tooLong)
        rule.onNodeWithTag("sketch.text.limitMessage").assertTextEquals("Text too long (max 10,000 characters)")
        assertEquals("Hello", session.text)
        rule.onNodeWithTag("sketch.text.limitOk").performClick()
        rule.onNodeWithTag("sketch.text.limitDialog").assertDoesNotExist()
    }

    @Test
    fun placement_should_FollowTheViewportAtZoomTwoPointFive() {
        val session = TextEditSession(origin(10, 20))
        val placement = TextOverlayPlacement.of(SketchViewport.of(zoom = 2.5, panX = 5.0, panY = -5.0), session)
        assertEquals(30.0, placement.origin.x)
        assertEquals(45.0, placement.origin.y)
        assertTrue(placement.fontSizePx > 0.0)
        assertEquals(50.0, placement.fontSizePx)
        show(session, viewport = SketchViewport.of(zoom = 2.5, panX = 5.0, panY = -5.0))
        rule.onNodeWithTag("sketch.text.field").assertIsDisplayed()
    }
}
