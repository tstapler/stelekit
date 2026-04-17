package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SuggestionNavigatorPanel] — the bottom-strip panel that lets users
 * step through all visible page-name suggestions on the current screen.
 */
class SuggestionNavigatorPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(name: String, idx: Int = 0) =
        SuggestionItem(
            blockUuid = "block-$idx",
            canonicalName = name,
            contentStart = idx * 10,
            contentEnd = idx * 10 + name.length,
        )

    // ── Visibility ────────────────────────────────────────────────────────────

    @Test
    fun emptySuggestions_panelIsNotShown() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = emptyList(),
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Suggestion 1 of 1").assertDoesNotExist()
    }

    @Test
    fun singleSuggestion_showsProgressOneOfOne() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = listOf(item("Kotlin")),
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Suggestion 1 of 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Link as [[Kotlin]]?").assertIsDisplayed()
    }

    @Test
    fun multipleSuggestions_showsCorrectProgressCounter() {
        val suggestions = listOf(item("Kotlin", 0), item("Python", 1), item("Java", 2))
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = suggestions,
                    currentIndex = 1,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Suggestion 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Link as [[Python]]?").assertIsDisplayed()
    }

    // ── Button state ──────────────────────────────────────────────────────────

    @Test
    fun atFirstSuggestion_previousButtonIsDisabled() {
        val suggestions = listOf(item("A", 0), item("B", 1))
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = suggestions,
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("◀").assertIsNotEnabled()
        composeTestRule.onNodeWithText("▶").assertIsEnabled()
    }

    @Test
    fun atLastSuggestion_nextButtonIsDisabled() {
        val suggestions = listOf(item("A", 0), item("B", 1))
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = suggestions,
                    currentIndex = 1,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("◀").assertIsEnabled()
        composeTestRule.onNodeWithText("▶").assertIsNotEnabled()
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @Test
    fun clickingLink_invokesOnLink() {
        var linked = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = listOf(item("Kotlin")),
                    currentIndex = 0,
                    onLink = { linked = true },
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Link  (↵)").performClick()
        assertTrue(linked)
    }

    @Test
    fun clickingSkip_invokesOnSkip() {
        var skipped = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = listOf(item("Kotlin")),
                    currentIndex = 0,
                    onLink = {},
                    onSkip = { skipped = true },
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Skip  (N)").performClick()
        assertTrue(skipped)
    }

    @Test
    fun clickingClose_invokesOnClose() {
        var closed = false
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = listOf(item("Kotlin")),
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = { closed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun clickingNext_invokesOnNext() {
        var nextCalled = false
        val suggestions = listOf(item("A", 0), item("B", 1))
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = suggestions,
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = { nextCalled = true },
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("▶").performClick()
        assertTrue(nextCalled)
    }

    @Test
    fun clickingPrevious_invokesOnPrevious() {
        var prevCalled = false
        val suggestions = listOf(item("A", 0), item("B", 1))
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = suggestions,
                    currentIndex = 1,
                    onLink = {},
                    onSkip = {},
                    onPrevious = { prevCalled = true },
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onNodeWithText("◀").performClick()
        assertTrue(prevCalled)
    }

    // ── Keyboard hints ────────────────────────────────────────────────────────

    @Test
    fun keyboardHint_isDisplayed() {
        composeTestRule.setContent {
            MaterialTheme {
                SuggestionNavigatorPanel(
                    suggestions = listOf(item("Kotlin")),
                    currentIndex = 0,
                    onLink = {},
                    onSkip = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        // The hint line should be present somewhere in the panel
        composeTestRule
            .onNodeWithText("◀/▶ navigate  •  ↵ or L link  •  N or S skip  •  Esc close")
            .assertIsDisplayed()
    }
}
