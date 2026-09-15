package dev.stapler.stelekit.ui.components

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts an already-[MermaidRenderResult.Rendered] SVG in a WebView, loading it directly via
 * [android.webkit.WebView.loadDataWithBaseURL] and skipping the bridge/JS-render pipeline
 * entirely (Story 4.1.3) — this composable is invoked only on a `mermaidRenderCache` hit or
 * right after a fresh render already produced the SVG, never to trigger a new render.
 */
@Composable
fun MermaidWebViewHost(result: MermaidRenderResult.Rendered, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> WebView(context).also(::configureMermaidWebViewForHosting) },
        update = { webView -> webView.loadMermaidSvg(result.svg) },
    )
}

/**
 * Disables the WebView's own scrolling/touch handling so it never captures the parent
 * `LazyColumn`'s vertical drag gesture — without this, a diagram taller than its viewport would
 * freeze page scroll the moment a finger lands on it.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun configureMermaidWebViewForHosting(webView: WebView) {
    // Pre-rendered SVG is loaded as static markup — no script execution needed here at all.
    webView.settings.javaScriptEnabled = false
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.isScrollContainer = false
    // Consume drag moves (preventing the WebView's own internal scroll) while letting
    // down/up/cancel fall through untouched so tap-to-edit still works.
    webView.setOnTouchListener { _, event -> event.action == MotionEvent.ACTION_MOVE }
}

/** Loads an already-rendered SVG directly — no `mermaid.render()` JS call on this path. */
internal fun WebView.loadMermaidSvg(svg: String) {
    val html = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>html,body{margin:0;padding:0;background:transparent;}svg{max-width:100%;height:auto;display:block;}</style>
        </head><body>$svg</body></html>
    """.trimIndent()
    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}
