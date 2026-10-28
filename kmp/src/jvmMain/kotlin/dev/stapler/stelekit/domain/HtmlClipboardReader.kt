// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor

object HtmlClipboardReader {
    private val HTML_FLAVOR = DataFlavor("text/html; charset=UTF-8; class=java.lang.String")

    /**
     * Reads HTML from the clipboard, cut at [maxChars]. Returns null when the clipboard has no
     * HTML flavor or is unreadable (headless, Wayland, permission error).
     *
     * The cap bounds what is parsed downstream; the AWT transfer itself hands back a whole String
     * that this code cannot limit.
     */
    fun read(
        clipboard: Clipboard? = null,
        maxChars: Int = HtmlBlockConverter.MAX_HTML_CHARS,
    ): ClipboardHtml? = try {
        val source = clipboard ?: Toolkit.getDefaultToolkit().systemClipboard
        if (!source.isDataFlavorAvailable(HTML_FLAVOR)) {
            null
        } else {
            (source.getData(HTML_FLAVOR) as? String)?.let { html ->
                if (html.length > maxChars) ClipboardHtml(html.substring(0, maxChars), truncated = true)
                else ClipboardHtml(html, truncated = false)
            }
        }
    } catch (_: Exception) {
        null
    }
}
