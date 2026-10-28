// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.domain.ClipboardHtml
import dev.stapler.stelekit.domain.HtmlBlockConverter
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.state.BlockStateManager

/**
 * Ctrl/Cmd+V handler for rich-text paste. Returns true when it turned the clipboard's HTML into
 * blocks; false when there is no editing block, no HTML, or the HTML is a single inline run, so
 * the ordinary text paste keeps handling copied words and sentences.
 */
internal fun pasteClipboardHtml(
    readClipboardHtml: () -> ClipboardHtml?,
    blockStateManager: BlockStateManager,
    notify: (String) -> Unit,
    editingBlockUuid: BlockUuid?,
): Boolean {
    if (editingBlockUuid == null) return false
    val clipboard = readClipboardHtml() ?: return false
    val blocks = HtmlBlockConverter.convert(clipboard.html)?.takeIf { it.size > 1 } ?: return false
    blockStateManager.pasteHtmlBlocks(editingBlockUuid, blocks)
    if (clipboard.truncated) notify("The clipboard was too large - only the first part was pasted")
    return true
}
