@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.query.QueryExecutor
import dev.stapler.stelekit.repository.BlockSearchRepository
import dev.stapler.stelekit.repository.DatalogBlockRepository
import dev.stapler.stelekit.repository.DatalogPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class QueryBlockTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Clock.System.now()
    private val blocks = DatalogBlockRepository()
    private val pages = DatalogPageRepository()
    private val executor = QueryExecutor(blocks, pages, blocks)

    private fun seed(content: String, uuid: String = "b1") = runBlocking {
        pages.savePage(Page(uuid = PageUuid("p1"), name = "Source Page", createdAt = now, updatedAt = now))
        blocks.saveBlock(
            Block(uuid = BlockUuid(uuid), pageUuid = PageUuid("p1"), content = content, position = "a0", createdAt = now, updatedAt = now)
        )
    }

    private fun show(
        raw: String,
        exec: QueryExecutor? = executor,
        onEdit: () -> Unit = {},
        onLink: (String) -> Unit = {},
    ) = rule.setContent {
        MaterialTheme {
            QueryBlock(rawQuery = raw, queryExecutor = exec, pageRepository = pages, onStartEditing = onEdit, onLinkClick = onLink)
        }
    }

    @Test
    fun resultsStateShowsCountRowsAndNavigatesOnRowClick() {
        seed("NOW write report")
        var navigated: String? = null
        show("(task now)", onLink = { navigated = it })
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("1 results") > 0 }
        rule.onNodeWithText("NOW write report").assertIsDisplayed()
        rule.onNodeWithText("NOW write report").performClick()
        rule.waitUntil(5_000) { navigated != null }
        assertEquals("Source Page", navigated)
    }

    @Test
    fun emptyStateShowsExplicitMessage() {
        show("(task now)")
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("No matching blocks") > 0 }
        rule.onNodeWithText("0 results").assertIsDisplayed()
    }

    @Test
    fun unsupportedFormShowsPlaceholderWithRawText() {
        show("(between -7d +7d)")
        rule.onNodeWithText("Unsupported query — showing raw text:").assertIsDisplayed()
        rule.onNodeWithText("{{query (between -7d +7d)}}").assertIsDisplayed()
    }

    @Test
    fun readFailureShowsMessageInsteadOfBlank() {
        val failing = object : BlockSearchRepository by blocks {
            override fun findBlocksWithTaskMarker(markers: Set<String>, limit: Int, offset: Int):
                Flow<Either<DomainError, List<Block>>> =
                flowOf(DomainError.DatabaseError.ReadFailed("closed").left())
        }
        show("(task now)", exec = QueryExecutor(failing, pages, blocks))
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Unable to load results") > 0 }
    }

    @Test
    fun collapseToggleHidesBodyInOneClick() {
        show("(task now)")
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("No matching blocks") > 0 }
        rule.onNodeWithContentDescription("Collapse query results").performClick()
        rule.onNodeWithContentDescription("Expand query results").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("No matching blocks"))
    }

    @Test
    fun headerClickStartsEditing() {
        var edits = 0
        show("(task now)", onEdit = { edits++ })
        rule.onNodeWithText("(task now)").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun truncationAffixAndCeilingAwareCount() {
        runBlocking {
            pages.savePage(Page(uuid = PageUuid("p1"), name = "Source Page", createdAt = now, updatedAt = now))
            blocks.saveBlocks(
                (1..60).map {
                    Block(uuid = BlockUuid("b$it"), pageUuid = PageUuid("p1"), content = "NOW item $it", position = "a$it", createdAt = now, updatedAt = now)
                }
            )
        }
        show("(task now)")
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("60 results") > 0 }
        rule.onNodeWithText("+10 more").assertExists()
    }

    @Test
    fun blockItemRendersLiteralTextWhenFlagDisabled() {
        seed("NOW write report")
        val query = Block(
            uuid = BlockUuid("q1"), pageUuid = PageUuid("p1"), content = "{{query (task now)}}",
            position = "a1", createdAt = now, updatedAt = now,
        )
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalQueryBlockContext provides QueryBlockContext(executor, pages, enabled = false)
                ) {
                    BlockItem(
                        block = query, isEditing = false, onStartEditing = {}, onStopEditing = {},
                        onContentChange = { _, _ -> }, onLinkClick = {}, onNewBlock = {}, onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithText("{{query (task now)}}", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Collapse query results", byDescription = true))
    }

    @Test
    fun blockItemDispatchesWholeBlockQueryToQueryBlock() {
        seed("NOW write report")
        val query = Block(
            uuid = BlockUuid("q1"), pageUuid = PageUuid("p1"), content = "{{query (task now)}}",
            position = "a1", createdAt = now, updatedAt = now,
        )
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalQueryBlockContext provides QueryBlockContext(executor, pages, true)) {
                    BlockItem(
                        block = query, isEditing = false, onStartEditing = {}, onStopEditing = {},
                        onContentChange = { _, _ -> }, onLinkClick = {}, onNewBlock = {}, onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("NOW write report") > 0 }
    }

    @Test
    fun mixedInlineQueryKeepsLiteralRendering() {
        seed("NOW write report")
        val mixed = Block(
            uuid = BlockUuid("q1"), pageUuid = PageUuid("p1"), content = "see {{query (task now)}} here",
            position = "a1", createdAt = now, updatedAt = now,
        )
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalQueryBlockContext provides QueryBlockContext(executor, pages, true)) {
                    BlockItem(
                        block = mixed, isEditing = false, onStartEditing = {}, onStopEditing = {},
                        onContentChange = { _, _ -> }, onLinkClick = {}, onNewBlock = {}, onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
        assertEquals(0, rule.onAllNodesWithTextCount("Collapse query results", byDescription = true))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
        text: String,
        byDescription: Boolean = false,
    ): Int =
        (if (byDescription) onAllNodesWithContentDescription(text) else onAllNodesWithText(text))
            .fetchSemanticsNodes().size
}
