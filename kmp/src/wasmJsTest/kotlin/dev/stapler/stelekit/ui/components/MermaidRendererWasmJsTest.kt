// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** REQ-1 (wasmJs) — exercises the real `mermaid` npm package in-browser via `wasmJsBrowserTest`. */
class MermaidRendererWasmJsTest {

    @Test
    fun renderMermaid_should_returnRenderedSvg_when_pieChartSourceValid() = runTest {
        val key = MermaidRenderKey(
            sourceText = "pie title Pets\n  \"Dogs\" : 5",
            theme = ThemeFingerprint(isLight = true, colorHash = 0),
            widthPx = 400,
        )

        val result = renderMermaid(key)

        val rendered = assertIs<MermaidRenderResult.Rendered>(result)
        assertTrue(rendered.svg.contains("<svg"), "expected rendered output to contain an <svg> element")
    }
}
