// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.browser

import dev.stapler.stelekit.ui.components.sheet.PasteEventSource

/**
 * The web [PasteEventSource] (Task 7.1b, ADR-008): one `paste` listener on [target] while at least one sheet is
 * registered, handing the event's `text/plain` to every registered handler. A handler that takes the text makes the
 * bridge cancel the browser's own paste, so the sheet and the page never both act on it.
 *
 * UNVERIFIED in a real browser (spike 0.3a/b): whether Ctrl+V on the Compose canvas fires `paste` at all. When it does
 * not, the sheet falls back to the clipboard port and the "Paste here" box. Plain class with its own state, not a field
 * on `PlatformFileSystem`; the listener handle is read at call time, never captured at construction.
 *
 * @param target the object that receives bubbling `paste` events; the page's `document` by default.
 */
class WasmPasteEventBridge(private val target: JsAny = pasteTarget()) : PasteEventSource {
    private val handlers = mutableListOf<(String) -> Boolean>()
    private var listener: JsAny? = null

    override fun register(onPaste: (String) -> Boolean): () -> Unit {
        handlers += onPaste
        if (listener == null) listener = addPasteListener(target) { text -> deliver(text) }
        return {
            handlers.remove(onPaste)
            val installed = listener
            if (handlers.isEmpty() && installed != null) {
                removePasteListener(target, installed)
                listener = null
            }
        }
    }

    /** True when some handler took [text]; an empty paste (an image, say) is never delivered. */
    private fun deliver(text: String): Boolean {
        if (text.isEmpty()) return false
        var taken = false
        for (handler in handlers.toList()) taken = handler(text) || taken
        return taken
    }
}

private fun pasteTarget(): JsAny = js("document")

private fun addPasteListener(target: JsAny, onPaste: (String) -> Boolean): JsAny = js(
    """
    (function() {
        const listener = function(e) {
            const data = e.clipboardData;
            const text = data ? data.getData('text/plain') : '';
            if (text && onPaste(text)) e.preventDefault();
        };
        target.addEventListener('paste', listener);
        return listener;
    })()
    """,
)

private fun removePasteListener(target: JsAny, listener: JsAny): Unit = js("target.removeEventListener('paste', listener)")
