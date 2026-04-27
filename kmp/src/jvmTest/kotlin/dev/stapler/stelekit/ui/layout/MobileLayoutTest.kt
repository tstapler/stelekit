package dev.stapler.stelekit.ui.layout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.LeftSidebar
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.Screen
import org.junit.Rule
import org.junit.Test

class MobileLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun leftSidebar_hiddenWhenCollapsed() {
        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = false,
                    isLoading = false,
                    favoritePages = emptyList(),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {}
                )
            }
        }

        // When sidebar is collapsed, "Navigation" label should not be visible
        composeTestRule.onNodeWithText("Navigation").assertDoesNotExist()
    }

    @Test
    fun leftSidebar_visibleWhenExpanded() {
        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = false,
                    favoritePages = emptyList(),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Navigation").assertIsDisplayed()
        composeTestRule.onNodeWithText("Journals").assertIsDisplayed()
    }

    @Test
    fun leftSidebar_showsNavigationItems() {
        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = false,
                    favoritePages = emptyList(),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {}
                )
            }
        }

        composeTestRule.onNodeWithText("All Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Flashcards").assertIsDisplayed()
    }
}
