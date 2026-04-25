package dev.stapler.stelekit.ui.components
import androidx.compose.ui.test.*

import androidx.compose.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Tests for [SuggestionContextMenu] — the right-click / long-press menu that
 * offers Link / Skip / Navigate all actions for a page-name suggestion.
 */
class SuggestionContextMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun whenExpanded_allThreeActionsAreVisible() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "Meeting Notes",
                    expanded = true,
                    onDismiss = {},
                    onLink = {},
                    onSkip = {},
                    onNavigateAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Link as [[Meeting Notes]]").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip this suggestion").assertIsDisplayed()
        composeTestRule.onNodeWithText("Navigate all suggestions…").assertIsDisplayed()
    }

    @Test
    fun whenNotExpanded_menuIsNotShown() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "Meeting Notes",
                    expanded = false,
                    onDismiss = {},
                    onLink = {},
                    onSkip = {},
                    onNavigateAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Link as [[Meeting Notes]]").assertDoesNotExist()
    }

    @Test
    fun clickingLink_invokesOnLink() {
        var linked = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "Kotlin",
                    expanded = true,
                    onDismiss = {},
                    onLink = { linked = true },
                    onSkip = {},
                    onNavigateAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Link as [[Kotlin]]").performClick()
        assertTrue(linked, "onLink should be called when Link item is clicked")
    }

    @Test
    fun clickingSkip_invokesOnSkip() {
        var skipped = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "Kotlin",
                    expanded = true,
                    onDismiss = {},
                    onLink = {},
                    onSkip = { skipped = true },
                    onNavigateAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Skip this suggestion").performClick()
        assertTrue(skipped, "onSkip should be called when Skip item is clicked")
    }

    @Test
    fun clickingNavigateAll_invokesOnNavigateAll() {
        var navigated = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "Kotlin",
                    expanded = true,
                    onDismiss = {},
                    onLink = {},
                    onSkip = {},
                    onNavigateAll = { navigated = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Navigate all suggestions…").performClick()
        assertTrue(navigated, "onNavigateAll should be called when Navigate All item is clicked")
    }

    @Test
    fun canonicalName_isShownInLinkLabel() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionContextMenu(
                    canonicalName = "My Special Page",
                    expanded = true,
                    onDismiss = {},
                    onLink = {},
                    onSkip = {},
                    onNavigateAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Link as [[My Special Page]]").assertIsDisplayed()
    }
}
