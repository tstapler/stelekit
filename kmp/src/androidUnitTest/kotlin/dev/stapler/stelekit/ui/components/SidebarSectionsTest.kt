package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Grouped sidebar navigation (plan D.3): headings, descriptions, selected state, rename, stable layout. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class SidebarSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var current by mutableStateOf<Screen>(Screen.Journals)

    private fun show(
        screen: Screen,
        navigated: MutableList<Screen> = mutableListOf(),
        badge: Boolean = false,
        gitSetup: () -> Unit = {},
    ) {
        current = screen
        composeRule.setContent {
            MaterialTheme {
                NavSectionList(currentScreen = current, onNavigate = { navigated += it }, onGitSetup = gitSetup, showLibraryNewBadge = badge)
            }
        }
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun sectionHeaders_haveHeadingSemantics_andPrecedeTheirRows() {
        show(Screen.Journals)
        for (key in listOf(I18n.NAV_SECTION_DAILY, I18n.NAV_SECTION_CONNECTIONS, I18n.NAV_SECTION_GRAPH)) {
            composeRule.onNode(hasText(I18n.get(key)).and(isHeading)).assertExists()
        }
        val header = composeRule.onNodeWithTag("nav-header-graph").getUnclippedBoundsInRoot()
        val library = composeRule.onNodeWithTag("nav-row-library").getUnclippedBoundsInRoot()
        assertTrue(header.top < library.top, "the Graph heading precedes the Library row")
    }

    @Test
    fun libraryRow_isFirstInGraph_withVisibleDescription_andAtLeast48dp() {
        show(Screen.Journals)
        composeRule.onNodeWithTag("nav-row-library").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(I18n.get(I18n.NAV_LIBRARY_DESC)).assertIsDisplayed()
        val library = composeRule.onNodeWithTag("nav-row-library").getUnclippedBoundsInRoot().top
        val stats = composeRule.onNodeWithTag("nav-row-graph-stats").getUnclippedBoundsInRoot().top
        assertTrue(library < stats)
    }

    @Test
    fun statsRowIsRenamed_andExactlyOneRowSaysLibrary() {
        show(Screen.Journals)
        composeRule.onNodeWithText("Graph Stats").assertExists()
        composeRule.onAllNodes(hasText("Library Stats")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Library")).assertCountEquals(1)
    }

    @Test
    fun selectedState_isSemantic_notOnlyColour() {
        show(Screen.Library())
        composeRule.onNodeWithTag("nav-row-library").assertIsSelected()
        composeRule.onNodeWithTag("nav-row-journals").assertIsNotSelected()
    }

    @Test
    fun rowsNavigate_andGitSetupOpensTheDialog() {
        val navigated = mutableListOf<Screen>()
        var git = 0
        show(Screen.Journals, navigated, gitSetup = { git++ })
        composeRule.onNodeWithTag("nav-row-library").performClick()
        composeRule.onNodeWithTag("nav-row-git-setup").performClick()
        assertEquals(listOf<Screen>(Screen.Library()), navigated)
        assertEquals(1, git)
    }

    @Test
    fun developerSection_appearsOnlyWhenActive_andNeverMovesRowsAbove() {
        show(Screen.Journals)
        composeRule.onNodeWithTag("nav-header-developer").assertDoesNotExist()
        val before = composeRule.onNodeWithTag("nav-row-git-setup").getUnclippedBoundsInRoot()
        current = Screen.Logs()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav-header-developer").assertExists()
        val after = composeRule.onNodeWithTag("nav-row-git-setup").getUnclippedBoundsInRoot()
        assertEquals(before, after)
    }

    @Test
    fun newBadge_showsWithTextEquivalent_whenRequested() {
        show(Screen.Journals, badge = true)
        composeRule.onNodeWithTag("nav-new-badge", useUnmergedTree = true).assertExists()
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, I18n.get(I18n.NAV_NEW_BADGE_STATE)),
            useUnmergedTree = false,
        ).assertExists()
    }

    @Test
    fun decorativeIcons_carryNoContentDescription() {
        show(Screen.Journals)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
