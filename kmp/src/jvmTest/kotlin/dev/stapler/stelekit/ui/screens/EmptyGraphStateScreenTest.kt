package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Interaction coverage for [EmptyGraphStateScreen] -- the UI surface the new
 * `GraphManager.removeGraph`/`graphsExplicitlyEmptied` backend behavior (covered by
 * `GraphManagerRemoveGraphTest`) is otherwise unreachable-by-test through.
 */
class EmptyGraphStateScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `create graph button is shown and invokes the callback when a native picker is available`() {
        var createClicked = false
        composeTestRule.setContent {
            MaterialTheme {
                EmptyGraphStateScreen(
                    onTryDemo = {},
                    onCreateGraph = { createClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("No graphs yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create a Graph").performClick()

        assertTrue("clicking Create a Graph must invoke onCreateGraph", createClicked)
    }

    @Test
    fun `try demo secondary button invokes onTryDemo when a native picker is available`() {
        var demoClicked = false
        composeTestRule.setContent {
            MaterialTheme {
                EmptyGraphStateScreen(
                    onTryDemo = { demoClicked = true },
                    onCreateGraph = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Try the Demo Graph Instead").performClick()

        assertTrue("clicking the secondary demo button must invoke onTryDemo", demoClicked)
    }

    @Test
    fun `only the demo button is offered when onCreateGraph is null`() {
        var demoClicked = false
        composeTestRule.setContent {
            MaterialTheme {
                EmptyGraphStateScreen(
                    onTryDemo = { demoClicked = true },
                    onCreateGraph = null,
                )
            }
        }

        composeTestRule.onNodeWithText("Create a Graph").assertDoesNotExist()
        composeTestRule.onNodeWithText("Try the Demo Graph Instead").assertDoesNotExist()
        composeTestRule.onNodeWithText("Try Demo Graph").performClick()

        assertTrue("the sole demo button must invoke onTryDemo", demoClicked)
    }

    @Test
    fun `error message is displayed when provided`() {
        composeTestRule.setContent {
            MaterialTheme {
                EmptyGraphStateScreen(
                    onTryDemo = {},
                    onCreateGraph = {},
                    errorMessage = "Couldn't open folder picker: boom",
                )
            }
        }

        composeTestRule.onNodeWithText("Couldn't open folder picker: boom").assertIsDisplayed()
    }
}
