// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * REQ-9 (wasmJs) — guards against a diagram source overriding the hardened `securityLevel` via
 * an inline `%%{init: ...}%%` directive. `MermaidJsBindings.initialize` is called with
 * `MERMAID_SECURITY_LEVEL` ("strict") before every render (`MermaidRenderer.wasmJs.kt`); mermaid
 * itself also refuses to let inline directives override `securityLevel`. Under either guard, an
 * injection payload in a node label must come back HTML-escaped, not as live markup.
 */
class MermaidSecurityDirectiveWasmJsTest {

    @Test
    fun renderMermaid_should_escapeInjectionPayload_when_sourceAttemptsLooseSecurityOverride() = runTest {
        val source = """
            %%{init: {"securityLevel":"loose"}}%%
            graph TD
              A["<img src=x onerror=alert(1)>"]
        """.trimIndent()
        val key = MermaidRenderKey(
            sourceText = source,
            theme = ThemeFingerprint(isLight = true, colorHash = 0),
            widthPx = 400,
        )

        val result = renderMermaid(key)

        val rendered = assertIs<MermaidRenderResult.Rendered>(result)
        assertFalse(
            rendered.svg.contains("<img src=x onerror=alert(1)>"),
            "injection payload must not appear as live, unescaped markup in the rendered SVG",
        )
        assertFalse(
            rendered.svg.contains("onerror=alert(1)", ignoreCase = true),
            "injection payload's event handler must not survive as an executable attribute",
        )
    }
}
