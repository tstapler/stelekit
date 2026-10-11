package dev.stapler.stelekit.ui.components

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Minimal JS-to-native bridge for the Android Mermaid render shim (`render.html`). Exposes
 * exactly one [JavascriptInterface] method — untrusted diagram-controlled JS calling back into
 * a wider bridge surface is a documented RCE-class risk (ADR-001), so the exposed surface is
 * bounded to a single result callback.
 *
 * Owns its own [CoroutineScope] rather than accepting a caller-supplied `rememberCoroutineScope()`
 * (per this repo's coroutine-scope-ownership rule) — [onRenderResult] is invoked from a WebView-
 * internal callback thread, not composition, so there is no Compose scope to borrow anyway, and a
 * bridge instance must keep completing pending results even across recomposition.
 */
class MermaidWebViewBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var pending: CompletableDeferred<MermaidRenderResult>? = null

    /**
     * Registers a new pending result for the next [onRenderResult] callback. Callers are
     * responsible for triggering the WebView-side render call after this and awaiting the
     * returned deferred under their own timeout (see `MermaidRenderer.android.kt`).
     */
    fun newPendingResult(): CompletableDeferred<MermaidRenderResult> {
        val deferred = CompletableDeferred<MermaidRenderResult>()
        pending = deferred
        return deferred
    }

    /**
     * Called from `render.html`'s JS with a `{"svg": "..."}` or `{"error": "..."}` payload.
     * Malformed JSON must never crash the bridge (it originates from untrusted diagram content
     * indirectly, via the mermaid.js render pipeline) — any parse failure completes the pending
     * deferred with [MermaidRenderResult.Failed] instead of throwing.
     */
    @JavascriptInterface
    fun onRenderResult(json: String) {
        val deferred = pending ?: return
        pending = null
        val result = parseResult(json)
        scope.launch { deferred.complete(result) }
    }

    private fun parseResult(json: String): MermaidRenderResult = try {
        val obj = JSONObject(json)
        when {
            obj.has("svg") -> {
                val svg = obj.optString("svg", "")
                if (svg.isBlank()) MermaidRenderResult.Failed("empty svg") else MermaidRenderResult.Rendered(svg)
            }
            obj.has("error") -> MermaidRenderResult.Failed(obj.optString("error", "unknown render error"))
            else -> MermaidRenderResult.Failed("malformed render payload: missing svg/error")
        }
    } catch (e: Throwable) {
        MermaidRenderResult.Failed("malformed JSON from WebView: ${e.message}")
    }

    /** Cancels any in-flight completion work. Call when the owning render call is torn down. */
    fun close() {
        scope.cancel()
    }
}
