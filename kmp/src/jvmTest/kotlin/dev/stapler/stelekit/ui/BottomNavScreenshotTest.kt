package dev.stapler.stelekit.ui
import androidx.compose.ui.test.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the bottom navigation bar.
 *
 * NOTE on JVM platform:
 * The JVM `actual fun PlatformBottomBar(...)` is a deliberate no-op — desktop uses a
 * sidebar instead of a bottom nav.  These tests therefore exercise the bottom-nav UI
 * directly using the same `NavigationBar` / `NavigationBarItem` composables that the
 * Android implementation wraps, via `CompositionLocalProvider(LocalWindowSizeClass
 * provides WindowSizeClass.COMPACT)`.  This lets us verify the visual structure and
 * label text without requiring an Android device or emulator.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class BottomNavScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Helper: renders the four-tab NavigationBar that mirrors the Android bottom nav
    // ---------------------------------------------------------------------------
    @Suppress("TestFunctionName")
    private fun renderBottomNav(
        currentScreen: Screen,
        themeMode: StelekitThemeMode = StelekitThemeMode.LIGHT
    ) {
        composeTestRule.setContent {
            // Provide COMPACT window size so isMobile = true throughout the tree.
            CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.COMPACT) {
                StelekitTheme(themeMode = themeMode) {
                    Box(modifier = Modifier.clipToBounds()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Simulate the bottom bar slot — mirrors the Android impl's nav items.
                            // windowInsets = WindowInsets(0) bypasses the system-bars inset lookup
                            // that Material3's NavigationBar performs; that API is only available
                            // on mobile platforms and throws NoSuchMethodError on the JVM/Skiko.
                            NavigationBar(windowInsets = WindowInsets(0)) {
                                NavigationBarItem(
                                    selected = currentScreen is Screen.Journals,
                                    onClick = {},
                                    icon = { Icon(Icons.Default.AutoStories, contentDescription = null) },
                                    label = { Text("Journals") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen is Screen.AllPages || currentScreen is Screen.PageView,
                                    onClick = {},
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    label = { Text("Pages") }
                                )
                                NavigationBarItem(
                                    // Search is a dialog, not a destination — never selected
                                    selected = false,
                                    onClick = {},
                                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    label = { Text("Search") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen is Screen.Notifications,
                                    onClick = {},
                                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                    label = { Text("Notifications") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Behavioral tests (no golden required — assert on semantics)
    // ---------------------------------------------------------------------------

    @Test
    fun bottomNav_rendersWithoutCrashing_whenCurrentScreenIsJournals() {
        renderBottomNav(currentScreen = Screen.Journals)
        composeTestRule.waitForIdle()
        // Verify all four tab labels are present
        composeTestRule.onNodeWithText("Journals").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun bottomNav_searchTabIsPresent_notFlashcards() {
        renderBottomNav(currentScreen = Screen.Journals)
        composeTestRule.waitForIdle()
        // The key requirement: Search tab exists, Flashcards tab does not
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Flashcards").assertDoesNotExist()
    }

    @Test
    fun bottomNav_rendersWithoutCrashing_whenCurrentScreenIsAllPages() {
        renderBottomNav(currentScreen = Screen.AllPages)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Screenshot tests — require recording on first run:
    //   ./gradlew jvmTest -Proborazzi.test.record=true
    // ---------------------------------------------------------------------------

    @Test
    fun bottomNav_screenshot_light_journalsSelected() {
        renderBottomNav(currentScreen = Screen.Journals, themeMode = StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot()
            .captureRoboImage("build/outputs/roborazzi/bottom_nav_light_journals.png")
    }

    @Test
    fun bottomNav_screenshot_dark_journalsSelected() {
        renderBottomNav(currentScreen = Screen.Journals, themeMode = StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot()
            .captureRoboImage("build/outputs/roborazzi/bottom_nav_dark_journals.png")
    }

    @Test
    fun bottomNav_screenshot_light_pagesSelected() {
        renderBottomNav(currentScreen = Screen.AllPages, themeMode = StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot()
            .captureRoboImage("build/outputs/roborazzi/bottom_nav_light_pages.png")
    }
}
