// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.projectbridge.PageLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The web [PageLifecycle] (Task 1.4.7m, UX-140): each `visibilitychange` to `hidden` on [target] asks every open saver to
 * skip its debounce and write now. This narrows the window of lost edits when a tab is closed or backgrounded and cannot
 * close it: a mobile browser may kill the page before the write reaches OPFS (ux.md 14.8).
 *
 * @param target the object that fires `visibilitychange` and carries `visibilityState`; the page's `document` by default.
 */
class WasmPageLifecycle(private val target: JsAny = pageDocument()) : PageLifecycle {
    override val hidden: Flow<Unit> = callbackFlow {
        val listener = addHiddenListener(target) { trySend(Unit) }
        awaitClose { removeHiddenListener(target, listener) }
    }.buffer(Channel.CONFLATED)
}

private fun pageDocument(): JsAny = js("document")

private fun addHiddenListener(target: JsAny, onHidden: () -> Unit): JsAny = js(
    """
    (function() {
        const listener = function() { if (target.visibilityState === 'hidden') onHidden(); };
        target.addEventListener('visibilitychange', listener);
        return listener;
    })()
    """,
)

private fun removeHiddenListener(target: JsAny, listener: JsAny): Unit = js(
    """
    target.removeEventListener('visibilitychange', listener)
    """,
)
