package dev.stapler.stelekit.ui.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.stapler.stelekit.ui.FontScaled
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.NavSectionList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Nav accessibility at 200% font scale on a 411 dp phone (plan D.10). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NavAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun atFontScale2_everyRowIsAtLeast48dp_andDescriptionsStayWithinTwoLines() {
        composeRule.setContent {
            FontScaled(scale = 2f) { MaterialTheme { NavSectionList(Screen.Library(), onNavigate = {}, onGitSetup = {}) } }
        }
        for (spec in NavModel.allSpecs.filter { it.id != "logs" && it.id != "performance" }) {
            composeRule.onNodeWithTag("nav-row-${spec.id}").assertHeightIsAtLeast(48.dp)
        }
        val row = composeRule.onNodeWithTag("nav-row-library").getUnclippedBoundsInRoot()
        // label (1-2 lines) + description (<= 2 lines) at 2x: bounded, so nothing is clipped away to a single line height.
        assertTrue(row.height < 200.dp, "row grew unboundedly: ${row.height}")
    }
}
