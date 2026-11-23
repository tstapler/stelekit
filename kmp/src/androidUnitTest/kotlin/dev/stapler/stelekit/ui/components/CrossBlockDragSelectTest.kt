// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

// NATIVE graphics: legacy mode measures every glyph as zero-width, so offsets collapse to the end.
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class CrossBlockDragSelectTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeClipboard : ClipboardManager {
        var text: String? = null
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
    }

    private fun block(i: Int, content: String) = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-00000000000$i"),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000099"),
        content = content,
        position = "a$i",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
    )

    private val blocks = listOf(block(1, "first block"), block(2, "second block"), block(3, "third block"))

    private fun setContent(clipboard: FakeClipboard, enabled: Boolean = true, editing: String? = null) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalClipboardManager provides clipboard,
                    LocalMultiBlockDragSelect provides enabled,
                ) {
                    BlockList(
                        blocks = blocks,
                        editingBlockUuid = editing,
                        onStartEditing = {},
                        onStopEditing = {},
                        onContentChange = { _, _, _ -> },
                        onLinkClick = {},
                        onNewBlock = {},
                        onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun center(text: String): Offset =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    private fun dragFirstToSecond() {
        // Text nodes span the full row width, so aim at their left edge where the glyphs are.
        fun leftOf(t: String) = composeRule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.let { Offset(it.left + 40f, it.center.y) }
        val start = leftOf("first block")
        val end = leftOf("second block")
        composeRule.onRoot().performMouseInput {
            moveTo(start)
            press()
            moveTo(Offset((start.x + end.x) / 2, (start.y + end.y) / 2))
            moveTo(end)
            release()
        }
        composeRule.waitForIdle()
    }

    private fun ctrlC() {
        composeRule.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.C); keyUp(Key.C); keyUp(Key.CtrlLeft)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun dragAcrossBlocks_thenCtrlC_copiesSelectedTextInDocumentOrder() {
        val clipboard = FakeClipboard()
        setContent(clipboard)
        dragFirstToSecond()
        ctrlC()
        val lines = clipboard.text!!.split("\n")
        assertEquals(2, lines.size, "copied=${clipboard.text}")
        assertEquals(true, lines[0].isNotEmpty() && "first block".endsWith(lines[0]), "copied=${clipboard.text}")
        assertEquals(true, lines[1].isNotEmpty() && "second block".startsWith(lines[1]) && lines[1] != "second block", "copied=${clipboard.text}")
    }

    @Test
    fun clickAfterDrag_clearsSelection() {
        val clipboard = FakeClipboard()
        setContent(clipboard)
        dragFirstToSecond()
        ctrlC()
        assertEquals(true, clipboard.text != null, "selection must exist before the click")
        clipboard.text = null
        val thirdCenter = center("third block")
        composeRule.onRoot().performMouseInput {
            moveTo(thirdCenter); press(); release()
        }
        composeRule.waitForIdle()
        ctrlC()
        assertNull(clipboard.text)
    }

    @Test
    fun flagOff_dragDoesNotSelect() {
        val clipboard = FakeClipboard()
        setContent(clipboard, enabled = false)
        dragFirstToSecond()
        ctrlC()
        assertNull(clipboard.text)
    }

    @Test
    fun editModeBlock_blocksDragSelect() {
        val clipboard = FakeClipboard()
        setContent(clipboard, editing = blocks[2].uuid.value)
        dragFirstToSecond()
        ctrlC()
        assertNull(clipboard.text)
    }
}
