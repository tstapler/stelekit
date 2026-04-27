package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.ui.components.applyFormatAction
import dev.stapler.stelekit.ui.screens.FormatAction
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)

/**
 * Integration tests verifying Compose key event propagation for Cmd+K.
 *
 * The production topology:
 *   App.kt  →  .onKeyEvent (bubble phase, fires AFTER children)  →  opens search
 *   BlockEditor.kt  →  .onPreviewKeyEvent (tunnel phase, fires BEFORE focus target)  →  link format
 *
 * Before the fix, BlockEditor consumed Cmd+K unconditionally via onPreviewKeyEvent,
 * so App's onKeyEvent never fired. After the fix, BlockEditor only consumes Cmd+K
 * when text is selected — allowing the global handler to fire when nothing is selected.
 */
class KeyboardShortcutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mirrors the Block editor / App key event topology with minimal dependencies.
    @Composable
    private fun ShortcutTestHarness(
        initialText: TextFieldValue = TextFieldValue(""),
        onGlobalSearch: () -> Unit,
        onTextChanged: (TextFieldValue) -> Unit,
        textState: TextFieldValue,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Mirrors App.kt: global shortcuts on the bubble-up (onKeyEvent) phase.
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        event.key == Key.K
                    ) {
                        onGlobalSearch()
                        true
                    } else false
                }
        ) {
            BasicTextField(
                value = textState,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .testTag("editor")
                    // Mirrors BlockEditor.kt: link format on tunnel (onPreviewKeyEvent) phase.
                    // Only consumes Cmd+K when text is selected — the fix.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.isMetaPressed || event.isCtrlPressed) &&
                            event.key == Key.K
                        ) {
                            if (!textState.selection.collapsed) {
                                applyFormatAction(
                                    FormatAction.LINK,
                                    textState,
                                    onTextChanged,
                                    { 0L },
                                    { _, _ -> }
                                )
                            } else {
                                false // no selection — pass through to global handler
                            }
                        } else false
                    }
            )
        }
    }

    @Test
    fun `Cmd+K without text selection fires global search, not link format`() {
        var globalSearchFired = false
        var textState by mutableStateOf(TextFieldValue(""))

        composeTestRule.setContent {
            ShortcutTestHarness(
                textState = textState,
                onGlobalSearch = { globalSearchFired = true },
                onTextChanged = { textState = it },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(globalSearchFired, "Global search should fire when Cmd+K pressed with no selection")
        assertEquals("", textState.text, "No link should be inserted when no text is selected")
    }

    @Test
    fun `Cmd+K with text selected wraps selection as link and does not open global search`() {
        var globalSearchFired = false
        var textState by mutableStateOf(
            TextFieldValue("hello world", selection = TextRange(0, 5))
        )

        composeTestRule.setContent {
            ShortcutTestHarness(
                textState = textState,
                onGlobalSearch = { globalSearchFired = true },
                onTextChanged = { textState = it },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        // Restore the selection explicitly since performClick may collapse it
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("hello world", selection = TextRange(0, 5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertFalse(globalSearchFired, "Global search should NOT fire when text is selected")
        assertTrue(
            textState.text.contains("[[hello]]"),
            "Selected text should be wrapped as a wiki link, got: '${textState.text}'"
        )
    }

    @Test
    fun `Cmd+K in non-editor input (no preview handler) reaches global handler`() {
        // Simulates focus in a text field that is NOT a block editor — e.g. the search bar
        // or any other input without BlockEditor's onPreviewKeyEvent. The global handler
        // should still fire because the event bubbles up unobstructed.
        var globalSearchFired = false
        var textState by mutableStateOf(TextFieldValue(""))

        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.isMetaPressed || event.isCtrlPressed) &&
                            event.key == Key.K
                        ) {
                            globalSearchFired = true
                            true
                        } else false
                    }
            ) {
                // Plain BasicTextField with NO onPreviewKeyEvent — does not intercept Cmd+K
                BasicTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier.testTag("plain-input")
                )
            }
        }

        composeTestRule.onNodeWithTag("plain-input").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("plain-input").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(globalSearchFired, "Global handler should fire when focused input has no Cmd+K handler")
    }
}
