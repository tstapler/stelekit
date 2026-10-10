// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.toJsString
import kotlinx.coroutines.await

/** Monotonic id suffix so concurrent renders never collide on the temp id `mermaid.render()` creates internally. */
private var mermaidRenderCounter = 0

/**
 * `wasmJs` renders directly against the real `mermaid.js` DOM API — no embedding problem, the
 * target already runs inside a browser. See ADR-001's Consequences → Negative/Watch-outs for why
 * this platform deliberately has no render-hang watchdog: `wasmJs` runs on a single-threaded JS
 * event loop, so a coroutine `withTimeoutOrNull`'s own callback needs that same event loop to
 * fire — it cannot preempt a synchronous/tight-loop `mermaid.render()` hang.
 */
actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult {
    key.sourceLengthFailure()?.let { return it }
    val source = key.sourceText

    return try {
        MermaidJsBindings.initialize(buildMermaidInitConfig(MERMAID_SECURITY_LEVEL))
        val renderId = "mermaid-render-${mermaidRenderCounter++}"
        val result = MermaidJsBindings.render(renderId.toJsString(), source.toJsString()).await<JsAny>()
        MermaidRenderResult.Rendered(mermaidRenderResultSvg(result))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MermaidRenderResult.Failed(e.message ?: "Unknown Mermaid render error")
    }
}
