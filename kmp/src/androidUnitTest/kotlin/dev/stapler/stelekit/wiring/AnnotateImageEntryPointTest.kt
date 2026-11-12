// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.LocalAnnotateImageAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The image viewer opened from an inline image block offers "Annotate / measure" under its pencil,
 * and invoking it hands the block and image reference to `LocalAnnotateImageAction` (supplied by
 * `GraphContentActiveShell`, which navigates to the annotation editor).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class AnnotateImageEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val block = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000001"),
        pageUuid = PageUuid("00000000-0000-0000-0000-0000000000a1"),
        content = "![site photo](../assets/photo.jpg)",
        position = "a0",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
    )

    private fun setContent(action: ((String, Block) -> Unit)?, onEdit: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAnnotateImageAction provides action) {
                    BlockList(
                        blocks = listOf(block),
                        editingBlockUuid = null,
                        onStartEditing = { onEdit() },
                        onStopEditing = {},
                        onContentChange = { _, _, _ -> },
                        onLinkClick = {},
                        onNewBlock = {},
                        onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun pencilOffersAnnotate_andInvokingItPassesTheBlockAndImageReference() {
        val calls = mutableListOf<Pair<String, Block>>()
        setContent({ ref, b -> calls += ref to b })

        composeRule.onNodeWithContentDescription("site photo").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Edit block").performClick()
        composeRule.onNodeWithTag("image-lightbox-annotate").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("../assets/photo.jpg" to block), calls)
    }

    @Test
    fun pencilStillEditsDirectly_whenAnnotatingIsUnavailable() {
        var edits = 0
        setContent(action = null, onEdit = { edits++ })

        composeRule.onNodeWithContentDescription("site photo").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Edit block").performClick()
        composeRule.waitForIdle()

        assertEquals(1, edits)
    }
}
