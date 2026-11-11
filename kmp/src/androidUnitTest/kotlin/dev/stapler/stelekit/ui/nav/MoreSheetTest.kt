package dev.stapler.stelekit.ui.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The phone "More" sheet (plan D.4): the same grouped list, and opening it never toggles the drawer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class MoreSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Calls {
        val navigated = mutableListOf<Screen>()
        var dismissed = 0
        var drawer = 0
        var git = 0
    }

    private fun show(calls: Calls, current: Screen = Screen.Journals, badge: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                MoreSheet(
                    currentScreen = current,
                    onNavigate = { calls.navigated += it },
                    onGitSetup = { calls.git++ },
                    onOpenDrawer = { calls.drawer++ },
                    onDismiss = { calls.dismissed++ },
                    showLibraryNewBadge = badge,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun sheetShowsTheThreeGroupedSections_andDescriptions() {
        show(Calls())
        for (key in listOf(I18n.NAV_SECTION_DAILY, I18n.NAV_SECTION_CONNECTIONS, I18n.NAV_SECTION_GRAPH)) {
            composeRule.onNodeWithText(I18n.get(key)).assertExists()
        }
        composeRule.onNodeWithText(I18n.get(I18n.NAV_LIBRARY_DESC)).assertExists()
    }

    @Test
    fun tappingLibrary_navigatesAndDismisses_withoutTouchingTheDrawer() {
        val calls = Calls()
        show(calls)
        composeRule.onNodeWithTag("nav-row-library").performClick()
        assertEquals(listOf<Screen>(Screen.Library()), calls.navigated)
        assertEquals(1, calls.dismissed)
        assertEquals(0, calls.drawer)
    }

    @Test
    fun theDrawerIsOneDeliberateTapAway() {
        val calls = Calls()
        show(calls)
        composeRule.onNodeWithTag("more-sheet-drawer").performClick()
        assertEquals(1, calls.drawer)
        assertEquals(1, calls.dismissed)
    }

    @Test
    fun newBadge_isShownOnTheLibraryRow() {
        show(Calls(), badge = true)
        composeRule.onNodeWithTag("nav-new-badge", useUnmergedTree = true).assertExists()
    }
}
