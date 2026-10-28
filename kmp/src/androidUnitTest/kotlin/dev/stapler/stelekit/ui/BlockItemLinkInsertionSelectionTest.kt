// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.components.BlockItem
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * After a programmatic insertion (link picker, image markdown) the block's content grows and its
 * version bumps; the cursor must follow `initialCursorPosition` rather than keep the stale selection.
 */
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class BlockItemLinkInsertionSelectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun block(content: String, version: Long) = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000001"),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000002"),
        content = content,
        position = "a0",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
        version = version,
    )

    private fun selectionAfterUpdate(
        before: String,
        after: String,
        cursorAfter: Int,
    ): TextRange? {
        var current by mutableStateOf(block(before, 0) to (before.length as Int?))
        composeTestRule.setContent {
            MaterialTheme {
                BlockItem(
                    block = current.first,
                    isEditing = true,
                    initialCursorPosition = current.second,
                    onStartEditing = {},
                    onStopEditing = {},
                    onContentChange = { _, _ -> },
                    onLinkClick = {},
                    onNewBlock = {},
                    onSplitBlock = { _, _ -> },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { current = block(after, 1) to cursorAfter }
        composeTestRule.waitForIdle()
        return composeTestRule.onAllNodesWithEditableText(after)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithEditableText(
        text: String,
    ): TextRange? {
        val nodes = onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.TextSelectionRange)
        ).fetchSemanticsNodes()
        return nodes.firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }?.config?.getOrNull(SemanticsProperties.TextSelectionRange)
    }

    @Test
    fun linkInsertedMidWordMovesCursorToEndOfLink() {
        // "Hello world" with cursor at 5 -> "Hello[[Notes]] world", cursor 14
        assertEquals(TextRange(14), selectionAfterUpdate("Hello world", "Hello[[Notes]] world", 14))
    }

    @Test
    fun linkReplacingSelectionCollapsesCursorAtEndOfLink() {
        assertEquals(TextRange(15), selectionAfterUpdate("Hello world", "Hello [[Notes]]", 15))
    }

    @Test
    fun insertedTextAtStartMovesCursorPastInsertion() {
        assertEquals(TextRange(9), selectionAfterUpdate("world", "[[Notes]]world", 9))
    }

    @Test
    fun cursorBeyondContentIsClamped() {
        assertEquals(TextRange(5), selectionAfterUpdate("Hello world", "Hello", 99))
    }
}
