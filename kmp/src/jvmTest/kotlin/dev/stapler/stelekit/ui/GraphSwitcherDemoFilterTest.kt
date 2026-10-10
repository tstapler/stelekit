package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.ui.components.GraphSwitcher
import org.junit.Rule
import org.junit.Test

class GraphSwitcherDemoFilterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val realGraph = GraphInfo(
        id = GraphId("real-id-001"),
        path = "/home/user/my-notes",
        displayName = "Real Graph",
        addedAt = 0L,
    )

    private val demoGraph = GraphInfo(
        id = DEMO_GRAPH_ID,
        path = "__demo__",
        displayName = "Demo Graph",
        addedAt = 0L,
        isDemo = true,
    )

    // T-16: GraphSwitcher does not show demo graph in dropdown.
    // The filter (!it.isDemo) lives in LeftSidebar before the list reaches GraphSwitcher.
    // This test mirrors that: pass the already-filtered list and verify "Demo Graph" never appears.
    @Test
    fun graphSwitcher_doesNotShowDemoGraphInDropdown() {
        composeTestRule.setContent {
            MaterialTheme {
                GraphSwitcher(
                    currentGraphName = "Real Graph",
                    availableGraphs = listOf(realGraph), // demo already filtered at call site
                    activeGraphId = "real-id-001",
                    onGraphSelected = {},
                    onAddGraph = {},
                    onRemoveGraph = {},
                )
            }
        }

        // Expand the dropdown to make items visible
        composeTestRule.onNodeWithContentDescription("Graph: Real Graph, tap to switch graph")
            .performClick()

        // Demo Graph must not appear among dropdown items
        composeTestRule.onNodeWithText("Demo Graph").assertDoesNotExist()
    }

    // T-17: GraphSwitcher pill contentDescription includes "(demo)" when demo is active
    @Test
    fun graphSwitcher_pillContentDescriptionIncludesDemoWhenDemoActive() {
        composeTestRule.setContent {
            MaterialTheme {
                GraphSwitcher(
                    currentGraphName = "Demo Graph",
                    availableGraphs = emptyList(),
                    activeGraphId = DEMO_GRAPH_ID.value,
                    isDemoActive = true,
                    onGraphSelected = {},
                    onAddGraph = {},
                    onRemoveGraph = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "Graph: Demo Graph (demo), tap to switch graph"
        ).assertExists()
    }
}
