// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockPropertyKeys
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FlashcardsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/fake"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val now = Clock.System.now()
    private val pageUuid = "flash-page"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val blockRepo = InMemoryBlockRepository()
    private val pageRepo = InMemoryPageRepository()
    private val manager = BlockStateManager(
        blockRepository = blockRepo,
        graphLoader = GraphLoader(NoopFileSystem(), pageRepo, blockRepo),
        scope = scope,
    )

    @After
    fun tearDown() = scope.cancel()

    private fun block(uuid: String, content: String, properties: Map<String, String>) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = "a0",
        createdAt = now,
        updatedAt = now,
        properties = properties,
    )

    private fun seed(vararg blocks: Block) = runBlocking {
        pageRepo.savePage(Page(uuid = PageUuid(pageUuid), name = "Flash", createdAt = now, updatedAt = now))
        blocks.forEach { blockRepo.saveBlock(it) }
        manager.observePage(PageUuid(pageUuid))
    }

    private fun show() = composeTestRule.setContent { MaterialTheme { FlashcardsScreen(manager) } }

    @Test
    fun `empty state shown when no cards are due`() {
        show()
        composeTestRule.onNodeWithText("No cards due for review").assertIsDisplayed()
    }

    @Test
    fun `non-card and future-dated blocks are not due`() {
        seed(
            block("plain", "just a note", emptyMap()),
            block("future", "later", mapOf(BlockPropertyKeys.CARD to "true", BlockPropertyKeys.CARD_NEXT_REVIEW to "2999-01-01")),
        )
        show()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No cards due for review").assertIsDisplayed()
    }

    @Test
    fun `due card flips to reveal answer and Good persists next review`() {
        seed(block("card-1", "What is KMP?", mapOf(BlockPropertyKeys.CARD to "true")))
        show()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTextCount("What is KMP?") > 0
        }
        composeTestRule.onNodeWithText("Tap to reveal").assertIsDisplayed()

        composeTestRule.onNodeWithText("What is KMP?").performClick()
        composeTestRule.onNodeWithText("How did you do?").assertIsDisplayed()

        composeTestRule.onNodeWithText("Good").performClick()

        composeTestRule.waitUntil(5_000) {
            manager.blocks.value[pageUuid]?.firstOrNull { it.uuid.value == "card-1" }
                ?.properties?.containsKey(BlockPropertyKeys.CARD_NEXT_REVIEW) == true
        }
        val updated = manager.blocks.value[pageUuid]!!.first { it.uuid.value == "card-1" }
        assertNotNull(updated.properties[BlockPropertyKeys.CARD_NEXT_REVIEW])
        assertTrue(updated.properties[BlockPropertyKeys.CARD_NEXT_REVIEW]!!.isNotBlank())
        // The rescheduled card is no longer due, so the deck recomputes to the empty state.
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No cards due for review").assertIsDisplayed()
    }

    @Test
    fun `Again on a due card persists a reschedule`() {
        seed(block("card-2", "Q2", mapOf(BlockPropertyKeys.CARD to "true")))
        show()
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithTextCount("Q2") > 0 }
        composeTestRule.onNodeWithText("Q2").performClick()
        composeTestRule.onNodeWithText("Again").performClick()
        composeTestRule.waitUntil(5_000) {
            manager.blocks.value[pageUuid]?.firstOrNull { it.uuid.value == "card-2" }
                ?.properties?.containsKey(BlockPropertyKeys.CARD_NEXT_REVIEW) == true
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
