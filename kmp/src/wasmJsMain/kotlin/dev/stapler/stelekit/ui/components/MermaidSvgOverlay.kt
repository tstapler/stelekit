// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Positions a rendered [svg] as a DOM overlay synced to this composable's on-screen bounds.
 *
 * This build's Compose-for-Web renders onto a single `<canvas>` (`jscanvas` mode,
 * `kmp/gradle.properties:13`), so the SVG cannot become a native composable node — it's a real
 * DOM element, a sibling of the canvas appended to `document.body`, kept positioned via
 * [onGloballyPositioned]. The composable itself draws a [BlendMode.Clear] rect over its own
 * bounds so no canvas-layer paint (e.g. the `CodeFenceBlock` fallback content this replaces)
 * bleeds through a partially-transparent region of the SVG.
 *
 * **Verified only by static review, not a live browser** — see ADR-001's Open Items
 * (Story 2.1.2 spike addendum) for exactly what was and wasn't validated: this compiles and the
 * positioning logic was reviewed, but real scroll/resize/DPI desync behavior was not observed.
 */
@Composable
fun MermaidSvgOverlay(svg: String, modifier: Modifier = Modifier) {
    val overlayElement = remember { createMermaidOverlayElement() }
    var bounds by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(overlayElement) {
        appendMermaidOverlayElement(overlayElement)
        onDispose { removeMermaidOverlayElement(overlayElement) }
    }

    DisposableEffect(overlayElement, svg) {
        setMermaidOverlayHtml(overlayElement, svg)
        onDispose { }
    }

    SideEffect {
        val currentBounds = bounds
        if (currentBounds != null) {
            positionMermaidOverlayElement(
                overlayElement,
                left = currentBounds.left,
                top = currentBounds.top,
                width = currentBounds.width,
                height = currentBounds.height,
            )
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates -> bounds = coordinates.boundsInWindow() }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent { drawRect(color = Color.Transparent, blendMode = BlendMode.Clear) },
    )
}

/** Creates a detached, pointer-events-inert `<div>` that will host the raw SVG markup. */
internal fun createMermaidOverlayElement(): JsAny = js(
    """
    (function() {
        var div = document.createElement('div');
        div.setAttribute('data-mermaid-overlay', 'true');
        div.style.position = 'absolute';
        div.style.pointerEvents = 'none';
        div.style.overflow = 'visible';
        div.style.zIndex = '1000';
        return div;
    })()
    """,
)

@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun appendMermaidOverlayElement(element: JsAny): Unit = js("document.body.appendChild(element)")

@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun removeMermaidOverlayElement(element: JsAny): Unit = js(
    """
    (function() {
        if (element.parentNode) { element.parentNode.removeChild(element); }
    })()
    """,
)

/**
 * `mermaid.render()`'s output is already sanitized under `securityLevel: "strict"` (see
 * `MermaidLimits.MERMAID_SECURITY_LEVEL`), so injecting it via `innerHTML` here does not
 * reintroduce the injection risk — the sanitization happens upstream, once, in
 * [renderMermaid]'s `mermaid.render()` call, not at display time.
 */
@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun setMermaidOverlayHtml(element: JsAny, svg: String): Unit = js("element.innerHTML = svg")

@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun positionMermaidOverlayElement(
    element: JsAny,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): Unit = js(
    """
    (function() {
        element.style.left = left + 'px';
        element.style.top = top + 'px';
        element.style.width = width + 'px';
        element.style.height = height + 'px';
    })()
    """,
)
