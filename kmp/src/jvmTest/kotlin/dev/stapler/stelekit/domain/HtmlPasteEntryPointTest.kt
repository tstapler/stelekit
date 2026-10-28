// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.export.HtmlStringSelection
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.pasteClipboardHtml
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.state.FakeFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Story 7.2 entry point: an AWT clipboard holding HTML flows through [HtmlClipboardReader] and the
 * Ctrl+V handler ([pasteClipboardHtml], the function behind `EditorCapabilities.onPasteHtml`) into
 * a real [BlockStateManager]. jvmTest-reason: desktop-only AWT clipboard types; needs no display
 * because the clipboard is a private [Clipboard], not the system one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HtmlPasteEntryPointTest {
    private val now = Clock.System.now()
    private val page = PageUuid("html-paste-page")
    private val target = BlockUuid("target-block")

    private class Setup(val manager: BlockStateManager, val blocks: InMemoryBlockRepository, val page: PageUuid) {
        suspend fun contents(): List<String> =
            blocks.getBlocksForPage(page).first().getOrNull().orEmpty().sortedBy { it.position }.map { it.content }
    }

    private suspend fun TestScope.setup(targetContent: String): Setup {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(Page(uuid = page, name = "Html Paste", createdAt = now, updatedAt = now))
        blockRepo.saveBlock(
            Block(uuid = target, pageUuid = page, content = targetContent, position = "a0", createdAt = now, updatedAt = now),
        )
        val manager = BlockStateManager(
            blockRepo,
            GraphLoader(FakeFileSystem(), pageRepo, blockRepo),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            graphWriter = GraphWriter(FakeFileSystem()),
            pageRepository = pageRepo,
            graphPathProvider = { EffectiveNotesPath("/fake/graph") },
        )
        manager.observePage(page)
        advanceUntilIdle()
        return Setup(manager, blockRepo, page)
    }

    private fun clipboardWithHtml(html: String) =
        Clipboard("test").also { it.setContents(HtmlStringSelection(html, "plain"), null) }

    private fun paste(
        s: Setup,
        clipboard: Clipboard,
        notices: MutableList<String> = mutableListOf(),
    ) = pasteClipboardHtml({ HtmlClipboardReader.read(clipboard) }, s.manager, notices::add, target)

    @Test
    fun htmlList_pastedIntoEmptyBlock_becomesTwoSiblingBlocks() = runTest {
        val s = setup(targetContent = "")

        val consumed = paste(s, clipboardWithHtml("<ul><li>one</li><li>two</li></ul>"))
        advanceUntilIdle()

        assertTrue(consumed)
        assertEquals(listOf("one", "two"), s.contents())
    }

    @Test
    fun htmlList_pastedAfterExistingText_keepsTheExistingBlock() = runTest {
        val s = setup(targetContent = "keep me")

        paste(s, clipboardWithHtml("<ul><li>one</li><li>two</li></ul>"))
        advanceUntilIdle()

        assertEquals(listOf("keep me", "one", "two"), s.contents())
    }

    @Test
    fun plainTextClipboard_isNotConsumed() = runTest {
        val s = setup(targetContent = "")
        val clipboard = Clipboard("plain").also { it.setContents(StringSelection("just text"), null) }

        assertFalse(paste(s, clipboard))
        advanceUntilIdle()

        assertEquals(listOf(""), s.contents())
    }

    @Test
    fun singleInlineRun_isLeftToTheOrdinaryTextPaste() = runTest {
        val s = setup(targetContent = "")

        assertFalse(paste(s, clipboardWithHtml("<span>a copied word</span>")))
    }

    @Test
    fun oversizedHtml_isTruncatedAtTheCapAndNotifies() = runTest {
        val s = setup(targetContent = "")
        val html = "<p>first</p><p>second</p><p>" + "x".repeat(5 * 1024 * 1024) + "</p>"
        val notices = mutableListOf<String>()

        val read = HtmlClipboardReader.read(clipboardWithHtml(html))
        assertEquals(true, read?.truncated)
        assertEquals(HtmlBlockConverter.MAX_HTML_CHARS, read?.html?.length)

        assertTrue(paste(s, clipboardWithHtml(html), notices))
        advanceUntilIdle()

        val pasted = s.contents()
        assertEquals(listOf("first", "second"), pasted.take(2))
        assertTrue(pasted.last().length < html.length, "the oversized block must be cut")
        assertEquals(1, notices.size)
    }

    @Test
    fun nestedListItems_nestUnderThePrecedingItem() = runTest {
        val s = setup(targetContent = "")

        paste(s, clipboardWithHtml("<ul><li>parent<ul><li>child</li></ul></li><li>next</li></ul>"))
        advanceUntilIdle()

        val blocks = s.blocks.getBlocksForPage(page).first().getOrNull().orEmpty()
        val parent = blocks.single { it.content == "parent" }
        assertEquals(parent.uuid, blocks.single { it.content == "child" }.parentUuid)
        assertEquals(null, blocks.single { it.content == "next" }.parentUuid)
    }
}
