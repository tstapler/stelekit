package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import org.junit.Rule
import org.junit.Test

class TopBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // canGoBack/canGoForward are computed: historyIndex=0, navigationHistory=[Journals] → both false
    private val defaultAppState = AppState(
        currentScreen = Screen.Journals,
        themeMode = StelekitThemeMode.SYSTEM
    )

    @Test
    fun topBar_rendersFileAndViewMenus() {
        composeTestRule.setContent {
            MaterialTheme {
                TopBar(
                    appState = defaultAppState,
                    platformSettings = PlatformSettings(),
                    onSettingsClick = {},
                    onNewPageClick = {},
                    onNavigate = {},
                    onThemeChange = {},
                    onLanguageChange = {},
                    onResetOnboarding = {},
                    onToggleDebug = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("top-bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("File").assertIsDisplayed()
        composeTestRule.onNodeWithText("View").assertIsDisplayed()
    }

    @Test
    fun topBar_navigationButtonsPresent() {
        composeTestRule.setContent {
            MaterialTheme {
                TopBar(
                    appState = defaultAppState,
                    platformSettings = PlatformSettings(),
                    onSettingsClick = {},
                    onNewPageClick = {},
                    onNavigate = {},
                    onThemeChange = {},
                    onLanguageChange = {},
                    onResetOnboarding = {},
                    onToggleDebug = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Go Back").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Go Forward").assertIsDisplayed()
    }

    @Test
    fun topBar_viewMenuOpensOnClick() {
        composeTestRule.setContent {
            MaterialTheme {
                TopBar(
                    appState = defaultAppState,
                    platformSettings = PlatformSettings(),
                    onSettingsClick = {},
                    onNewPageClick = {},
                    onNavigate = {},
                    onThemeChange = {},
                    onLanguageChange = {},
                    onResetOnboarding = {},
                    onToggleDebug = {}
                )
            }
        }

        composeTestRule.onNodeWithText("View").performClick()

        // View menu items should appear
        composeTestRule.onNodeWithText("Performance Dashboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show Debug Info").assertIsDisplayed()
    }
}
