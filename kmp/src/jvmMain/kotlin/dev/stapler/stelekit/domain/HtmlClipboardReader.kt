// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

object HtmlClipboardReader {
    /**
     * Attempts to read HTML content from the system clipboard.
     * Returns the HTML string if the clipboard contains an HTML flavor, null otherwise.
     * Falls back gracefully on Wayland/headless environments.
     */
    fun readHtml(): String? = try {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val htmlFlavor = java.awt.datatransfer.DataFlavor("text/html; charset=UTF-8; class=java.lang.String")
        if (clipboard.isDataFlavorAvailable(htmlFlavor)) {
            clipboard.getData(htmlFlavor) as? String
        } else null
    } catch (_: Exception) { null } // headless, Wayland, or permission error
}
