package dev.stapler.stelekit.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private val logger = dev.stapler.stelekit.logging.Logger("MermaidRenderer")
private const val RENDER_HTML_URL = "file:///android_asset/mermaid/render.html"

/**
 * Renders via a hidden `android.webkit.WebView` loading the bundled local `render.html` shim
 * (never a remote URL — see ADR-001) and a minimal [MermaidWebViewBridge].
 *
 * WebView creation/`evaluateJavascript` must run on the main thread; the outer [withContext]
 * keeps the suspend boundary + guards on [PlatformDispatcher.IO], and [renderViaWebView] hops
 * to [Dispatchers.Main] only for the WebView calls themselves.
 */
actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult = withContext(PlatformDispatcher.IO) {
    key.sourceLengthFailure()?.let { return@withContext it }
    val source = key.sourceText

    try {
        withTimeoutOrNull(MERMAID_RENDER_TIMEOUT_MS) { renderViaWebView(source) }
            ?: MermaidRenderResult.Failed("render timed out after ${MERMAID_RENDER_TIMEOUT_MS}ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A missing/broken WebView provider (some AOSP forks, stripped-down devices) throws
        // here rather than returning null — caught broadly per this repo's native-load-failure
        // rule (Application.onCreate's catch-Throwable precedent).
        logger.warn("renderMermaid: WebView unavailable", e)
        MermaidRenderResult.UnsupportedPlatform
    }
}

/**
 * Enables JS (required to run the bundled mermaid.js) while locking down every file/content
 * access surface — the render page is a bundled asset, never a remote or user-supplied URL, so
 * there is no legitimate reason for the page's own script to reach the filesystem or a content
 * provider. Extracted so the settings themselves are directly assertable in a Robolectric test
 * (see `MermaidWebViewSettingsTest`) rather than only inferred from behavior.
 */
internal fun configureMermaidRenderWebView(webView: WebView) {
    webView.settings.javaScriptEnabled = true
    webView.settings.allowFileAccess = false
    webView.settings.allowFileAccessFromFileURLs = false
    webView.settings.allowUniversalAccessFromFileURLs = false
    webView.settings.allowContentAccess = false
}

private suspend fun renderViaWebView(source: String): MermaidRenderResult {
    val bridge = MermaidWebViewBridge()
    val deferred = bridge.newPendingResult()
    var webView: WebView? = null
    try {
        withContext(Dispatchers.Main) {
            val wv = WebView(SteleKitContext.context)
            webView = wv
            configureMermaidRenderWebView(wv)
            wv.addJavascriptInterface(bridge, "AndroidBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // JSONObject.quote produces a JS-string-literal-safe escaped form.
                    view.evaluateJavascript("window.stelekitRenderMermaid(${JSONObject.quote(source)});", null)
                }
            }
            // Local bundled asset only — never http(s), per ADR-001's untrusted-content posture.
            wv.loadUrl(RENDER_HTML_URL)
        }
        return deferred.await()
    } finally {
        bridge.close()
        // On the timeout path this `finally` runs while the coroutine's Job is already
        // cancelling — an ordinary `withContext` would throw CancellationException immediately
        // without running its body, leaking the WebView + JS bridge on every timeout. +
        // NonCancellable forces the destroy() call to actually happen.
        withContext(Dispatchers.Main + NonCancellable) { webView?.destroy() }
    }
}
