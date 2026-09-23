// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import kotlin.js.Promise

/**
 * Kotlin/Wasm external interop for the `mermaid` npm package's default export
 * (bundled via `implementation(npm("mermaid", "12.0.0"))`, `kmp/build.gradle.kts`).
 *
 * Uses Kotlin/Wasm's `external`/`JsAny` interop model — classic Kotlin/JS `dynamic`
 * types have no equivalent on the `wasmJs` target.
 */
@Suppress("UnusedParameter") // external declarations have no body — params are consumed by the JS runtime, not Kotlin
@JsModule("mermaid")
internal external object MermaidJsBindings {
    fun initialize(config: JsAny)

    /** Renders [source] and resolves to a JS object with a `.svg` string field. */
    fun render(id: JsString, source: JsString): Promise<JsAny>
}

/** Builds the `mermaid.initialize()` config object, hardened at the given [securityLevel]. */
@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun buildMermaidInitConfig(securityLevel: String): JsAny =
    js("({ startOnLoad: false, securityLevel: securityLevel })")

/** Extracts the `.svg` string field from a resolved `mermaid.render()` result object. */
@Suppress("UnusedParameter") // referenced inside the js() string literal, not as a Kotlin expression
internal fun mermaidRenderResultSvg(result: JsAny): String = js("result.svg")
