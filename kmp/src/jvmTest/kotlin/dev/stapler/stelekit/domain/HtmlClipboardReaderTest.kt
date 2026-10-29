// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.export.HtmlStringSelection
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [HtmlClipboardReader] edge cases on private [Clipboard]s (no display needed). */
class HtmlClipboardReaderTest {
    private val htmlFlavor = DataFlavor("text/html; charset=UTF-8; class=java.lang.String")

    @Test
    fun htmlUnderTheCap_isReturnedWhole() {
        val clipboard = Clipboard("t").also { it.setContents(HtmlStringSelection("<p>a</p>", "a"), null) }

        assertEquals(ClipboardHtml("<p>a</p>", truncated = false), HtmlClipboardReader.read(clipboard, maxChars = 100))
    }

    @Test
    fun htmlOverTheCap_isCutAndFlagged() {
        val clipboard = Clipboard("t").also { it.setContents(HtmlStringSelection("<p>abcdef</p>", "a"), null) }

        assertEquals(ClipboardHtml("<p>abc", truncated = true), HtmlClipboardReader.read(clipboard, maxChars = 6))
    }

    @Test
    fun unreadableClipboard_yieldsNullInsteadOfThrowing() {
        val exploding = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(htmlFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor.match(htmlFlavor)
            override fun getTransferData(flavor: DataFlavor): Any = throw java.io.IOException("clipboard owner gone")
        }
        val clipboard = Clipboard("t").also { it.setContents(exploding, null) }

        assertNull(HtmlClipboardReader.read(clipboard))
    }

    @Test
    fun systemClipboard_neverThrows_evenWhenHeadless() {
        HtmlClipboardReader.read()
    }
}
