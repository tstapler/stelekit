package dev.stapler.stelekit.ui.components
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule

import androidx.compose.material3.MaterialTheme
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.screens.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

class SearchDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeSearchViewModel(): SearchViewModel {
        val searchRepo = InMemorySearchRepository()
        return SearchViewModel(searchRepo, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun searchDialog_showsInputWhenVisible() {
        val viewModel = makeSearchViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                SearchDialog(
                    visible = true,
                    onDismiss = {},
                    onNavigateToPage = {},
                    onNavigateToBlock = {},
                    onCreatePage = {},
                    viewModel = viewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Search pages and blocks...").assertIsDisplayed()
    }

    @Test
    fun searchDialog_notShownWhenInvisible() {
        val viewModel = makeSearchViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                SearchDialog(
                    visible = false,
                    onDismiss = {},
                    onNavigateToPage = {},
                    onNavigateToBlock = {},
                    onCreatePage = {},
                    viewModel = viewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Search pages and blocks...").assertDoesNotExist()
    }

    @Test
    fun searchDialog_typingQueryShowsResults() {
        val viewModel = makeSearchViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                SearchDialog(
                    visible = true,
                    onDismiss = {},
                    onNavigateToPage = {},
                    onNavigateToBlock = {},
                    onCreatePage = {},
                    viewModel = viewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Search pages and blocks...").performTextInput("Test")

        // After typing, the "Create page" option should appear since no exact match
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Create page \"Test\"", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
