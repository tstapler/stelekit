// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.ui.components.sheet.LocalPasteEventSource
import dev.stapler.stelekit.ui.components.sheet.LocalTableVisualPrefs
import dev.stapler.stelekit.ui.components.sheet.TableVisualPrefs

private const val FORCED_COLORS = "(forced-colors: active)"
private const val REDUCED_MOTION = "(prefers-reduced-motion: reduce)"

/** The web values of [TableVisualPrefs], from the two media queries; off when `matchMedia` is missing. */
internal fun webTableVisualPrefs() = TableVisualPrefs(reducedMotion = mediaMatches(REDUCED_MOTION), highContrast = mediaMatches(FORCED_COLORS))

/**
 * What the table surfaces need from the page, provided once around the app (Tasks 6.1d and 7.1b): the DOM paste
 * source and the forced-colors and reduced-motion preferences, re-read when either media query changes. The
 * `matchMedia` mechanism is INFERRED (UNVERIFIED in a real browser with forced colors on).
 */
@Composable
internal fun WebTableEnvironment(content: @Composable () -> Unit) {
    val bridge = remember { WasmPasteEventBridge() }
    var prefs by remember { mutableStateOf(webTableVisualPrefs()) }
    DisposableEffect(Unit) {
        val handles = listOf(FORCED_COLORS, REDUCED_MOTION).map { watchMedia(it) { prefs = webTableVisualPrefs() } }
        onDispose { handles.forEach(::unwatchMedia) }
    }
    CompositionLocalProvider(
        LocalPasteEventSource provides bridge,
        LocalTableVisualPrefs provides prefs,
        content = content,
    )
}

private fun mediaMatches(query: String): Boolean =
    js("typeof window.matchMedia === 'function' && window.matchMedia(query).matches")

private fun watchMedia(query: String, onChange: () -> Unit): JsAny? = js(
    """
    (function() {
        if (typeof window.matchMedia !== 'function') return null;
        const media = window.matchMedia(query);
        const listener = function() { onChange(); };
        media.addEventListener('change', listener);
        return { media: media, listener: listener };
    })()
    """,
)

private fun unwatchMedia(handle: JsAny?): Unit = js("if (handle) handle.media.removeEventListener('change', handle.listener)")
