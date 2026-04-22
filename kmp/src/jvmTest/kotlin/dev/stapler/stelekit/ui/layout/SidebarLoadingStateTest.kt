package dev.stapler.stelekit.ui.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.LeftSidebar
import kotlin.test.assertEquals
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

class SidebarLoadingStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = Clock.System.now()

    private fun fakePage(uuid: String, name: String) = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    // ── Sidebar during Phase 0/1 loading ──────────────────────────────────────

    @Test
    fun sidebar_whileLoading_navigationItemsAreVisible() {
        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = true,
                    favoritePages = emptyList(),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {},
                )
            }
        }

        // Core nav items are always rendered — user can navigate immediately
        composeTestRule.onNodeWithText("Journals").assertIsDisplayed()
        composeTestRule.onNodeWithText("All Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Flashcards").assertIsDisplayed()
    }

    @Test
    fun sidebar_whileLoading_favoritesAndRecentsAreHidden() {
        val favoritePage = fakePage("00000000-0000-0000-0000-000000000001", "My Favorite Page")
        val recentPage = fakePage("00000000-0000-0000-0000-000000000002", "Recent Note")

        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = true,
                    favoritePages = listOf(favoritePage),
                    recentPages = listOf(recentPage),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {},
                )
            }
        }

        // Favorites and Recents section headers must not appear while loading
        composeTestRule.onNodeWithText("Favorites").assertDoesNotExist()
        composeTestRule.onNodeWithText("Recent").assertDoesNotExist()
        composeTestRule.onNodeWithText("My Favorite Page").assertDoesNotExist()
        composeTestRule.onNodeWithText("Recent Note").assertDoesNotExist()
    }

    // ── Sidebar after Phase 1 completes (isLoading = false) ──────────────────

    @Test
    fun sidebar_afterPhase1_showsFavoritesAndRecents() {
        val favoritePage = fakePage("00000000-0000-0000-0000-000000000001", "My Favorite Page")
        val recentPage = fakePage("00000000-0000-0000-0000-000000000002", "Recent Note")

        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = false,
                    favoritePages = listOf(favoritePage),
                    recentPages = listOf(recentPage),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Favorites").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Favorite Page").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recent Note").assertIsDisplayed()
    }

    @Test
    fun sidebar_afterPhase1_navigationIsClickable() {
        var navigatedTo: Screen? = null

        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = false,
                    favoritePages = emptyList(),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = {},
                    onNavigate = { navigatedTo = it },
                    onToggleFavorite = {},
                )
            }
        }

        composeTestRule.onNodeWithText("All Pages").performClick()

        assertEquals(Screen.AllPages, navigatedTo, "clicking All Pages must navigate immediately after phase 1")
    }

    @Test
    fun sidebar_afterPhase1_pageLinksArClickable() {
        val favoritePage = fakePage("00000000-0000-0000-0000-000000000001", "My Favorite Page")
        var clickedPage: Page? = null

        composeTestRule.setContent {
            MaterialTheme {
                LeftSidebar(
                    expanded = true,
                    isLoading = false,
                    favoritePages = listOf(favoritePage),
                    recentPages = emptyList(),
                    currentScreen = Screen.Journals,
                    onPageClick = { clickedPage = it },
                    onNavigate = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeTestRule.onNodeWithText("My Favorite Page").performClick()

        assertEquals(favoritePage, clickedPage, "clicking a favorite page must fire onPageClick immediately after phase 1")
    }
}
