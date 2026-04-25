package dev.stapler.stelekit.ui.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.ui.MainLayout
import org.junit.Rule
import org.junit.Test

class DesktopLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainLayout_showsAllSlots() {
        composeTestRule.setContent {
            MaterialTheme {
                MainLayout(
                    topBar = { Text("TopBarContent") },
                    leftSidebar = { Text("SidebarContent") },
                    rightSidebar = {},
                    content = { Text("MainContent") },
                    statusBar = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("left-sidebar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("content-area").assertIsDisplayed()
        composeTestRule.onNodeWithText("TopBarContent").assertIsDisplayed()
        composeTestRule.onNodeWithText("SidebarContent").assertIsDisplayed()
        composeTestRule.onNodeWithText("MainContent").assertIsDisplayed()
    }

    @Test
    fun mainLayout_contentAreaIsPresent() {
        composeTestRule.setContent {
            MaterialTheme {
                MainLayout(
                    topBar = {},
                    leftSidebar = {},
                    rightSidebar = {},
                    content = { Text("ContentArea") },
                    statusBar = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("content-area").assertIsDisplayed()
        composeTestRule.onNodeWithText("ContentArea").assertIsDisplayed()
    }
}
