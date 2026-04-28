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
 *   BlockEditor.kt  →  .onPreviewKeyEvent (tunnel phase, fires BEFORE focus target)
 *
 * Behaviour:
 *   - No selection: Cmd+K falls through to global handler → opens search
 *   - Selection: Cmd+K consumed by BlockEditor → opens search pre-filled with selected text
 */
class KeyboardShortcutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mirrors the BlockEditor / App key event topology with minimal dependencies.
    @Composable
    private fun ShortcutTestHarness(
        onGlobalSearch: () -> Unit,
        onOpenSearchWithText: (String) -> Unit = {},
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
                    // Mirrors BlockEditor.kt: Cmd+K with selection opens search pre-filled;
                    // without selection the event falls through to the global handler.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.isMetaPressed || event.isCtrlPressed) &&
                            event.key == Key.K
                        ) {
                            if (!textState.selection.collapsed) {
                                val selectedText = textState.text.substring(
                                    textState.selection.min, textState.selection.max
                                )
                                onOpenSearchWithText(selectedText)
                                true
                            } else {
                                false // no selection — pass through to global handler
                            }
                        } else false
                    }
            )
        }
    }

    @Test
    fun `Cmd+K without text selection fires global search`() {
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
        assertEquals("", textState.text, "Text should be unchanged when no selection")
    }

    @Test
    fun `Cmd+K with text selected opens search pre-filled with selected text`() {
        var globalSearchFired = false
        var searchWithTextArg: String? = null
        var textState by mutableStateOf(
            TextFieldValue("hello world", selection = TextRange(0, 5))
        )

        composeTestRule.setContent {
            ShortcutTestHarness(
                textState = textState,
                onGlobalSearch = { globalSearchFired = true },
                onOpenSearchWithText = { searchWithTextArg = it },
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

        assertFalse(globalSearchFired, "Global handler should NOT fire — event consumed by BlockEditor")
        assertEquals("hello", searchWithTextArg, "Search should open pre-filled with selected text")
        assertEquals("hello world", textState.text, "Text should not be modified (no link wrapping)")
    }

    @Test
    fun `Cmd+K in non-editor input (no preview handler) reaches global handler`() {
        // Simulates focus in a text field without BlockEditor's onPreviewKeyEvent.
        // The global handler should still fire because the event bubbles up unobstructed.
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
