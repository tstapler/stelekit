// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.SearchResultItem
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class AutocompleteMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = Clock.System.now()
    private val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
    private val footer = "Tab — filter results  •  Ctrl+Enter — create new page"

    private fun page(name: String, namespace: String? = null) =
        Page(uuid = pageUuid, name = name, namespace = namespace, createdAt = now, updatedAt = now)

    private fun block(content: String) = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000002"),
        pageUuid = pageUuid,
        content = content,
        position = "a0",
        createdAt = now,
        updatedAt = now,
    )

    private val alpha = SearchResultItem.PageItem(page("Alpha"))
    private val beta = SearchResultItem.PageItem(page("Beta", namespace = "Projects"))

    private fun show(
        items: List<SearchResultItem>,
        selectedIndex: Int = 0,
        cursorRect: Rect? = Rect(10f, 10f, 12f, 30f),
        filterText: String = "",
        isFilterActive: Boolean = false,
        onItemSelected: (SearchResultItem) -> Unit = {},
        onFilterTextChange: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                AutocompleteMenu(
                    items = items,
                    selectedIndex = selectedIndex,
                    onItemSelected = onItemSelected,
                    onDismiss = {},
                    cursorRect = cursorRect,
                    filterText = filterText,
                    isFilterActive = isFilterActive,
                    onFilterTextChange = onFilterTextChange,
                )
            }
        }
    }

    @Test
    fun menu_should_RenderNothing_WhenItemsEmpty() {
        show(items = emptyList())

        composeTestRule.onAllNodesWithText(footer).assertCountEquals(0)
    }

    @Test
    fun menu_should_RenderNothing_WhenCursorRectNull() {
        show(items = listOf(alpha), cursorRect = null)

        composeTestRule.onAllNodesWithText("Alpha").assertCountEquals(0)
        composeTestRule.onAllNodesWithText(footer).assertCountEquals(0)
    }

    @Test
    fun menu_should_RenderEachItemKind() {
        show(
            items = listOf(
                SearchResultItem.Header("Pages"),
                alpha,
                beta,
                SearchResultItem.AliasItem(page("Gamma"), alias = "G"),
                SearchResultItem.BlockItem(block("a block about things")),
                SearchResultItem.CreatePageItem("Delta"),
            ),
        )

        composeTestRule.onNodeWithText("Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
        composeTestRule.onNodeWithText("Projects").assertIsDisplayed()
        composeTestRule.onNodeWithText("G").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ Gamma").assertIsDisplayed()
        composeTestRule.onNodeWithText("a block about things").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create page: Delta").assertIsDisplayed()
    }

    @Test
    fun menu_should_ShowHintFooter_WhenFilterInactive() {
        show(items = listOf(alpha))

        composeTestRule.onNodeWithText(footer).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Filter: ").assertCountEquals(0)
    }

    @Test
    fun itemClick_should_InvokeOnItemSelected_WithThatItem() {
        val selected = mutableListOf<SearchResultItem>()
        show(items = listOf(alpha, beta), onItemSelected = { selected += it })

        composeTestRule.onNodeWithText("Beta").performClick()

        assertEquals(listOf<SearchResultItem>(beta), selected)
    }

    @Test
    fun selectedIndex_should_NotCrash_WhenOutOfRange() {
        show(items = listOf(alpha, beta), selectedIndex = 99)

        composeTestRule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun filter_should_ShowFieldAndHideFooter_WhenActive() {
        show(items = listOf(alpha, beta), isFilterActive = true)

        composeTestRule.onNodeWithText("Filter: ").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(footer).assertCountEquals(0)
    }

    @Test
    fun filter_should_CountMatches_ExcludingCreatePageItem() {
        show(
            items = listOf(alpha, beta, SearchResultItem.CreatePageItem("x")),
            filterText = "x",
            isFilterActive = true,
        )

        composeTestRule.onNodeWithText("2 matches").assertIsDisplayed()
    }

    @Test
    fun filter_should_UseSingularMatch_ForOneResult() {
        show(items = listOf(alpha), filterText = "al", isFilterActive = true)

        composeTestRule.onNodeWithText("1 match").assertIsDisplayed()
    }

    @Test
    fun filter_should_HideMatchCount_WhenFilterTextEmpty() {
        show(items = listOf(alpha), filterText = "", isFilterActive = true)

        composeTestRule.onAllNodesWithText("1 match").assertCountEquals(0)
    }
}
