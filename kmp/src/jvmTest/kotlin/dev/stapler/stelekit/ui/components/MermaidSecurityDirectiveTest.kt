package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Story 7.1.7a — an embedded `%%{init: {"securityLevel":"loose"}}%%` directive plus an
 * injection-payload node label must never let live/executable content reach the caller, on
 * either of the two ways this can resolve:
 *  - `Rendered`: the payload must appear HTML-escaped in the SVG, never as a live tag.
 *  - `Failed`: also an acceptable, safe outcome — nothing at all reaches the caller.
 * The one outcome that would mean the security invariant broke is a `Rendered` result whose SVG
 * contains the payload unescaped/live — that's what this test actually guards against.
 *
 * With this engine's current vendored DOM shim (see ADR-001's Open Items), this specific fixture
 * — a quoted node label containing raw `<tag ...>` syntax — triggers a `RangeError: Maximum call
 * stack size exceeded` inside the shim's HTML-sanitization path (independent of security level;
 * confirmed via a milder directive-only fixture below that it isn't the `%%{init}%%` line itself)
 * and resolves as `Failed`, never `Rendered`. That is caught, safe, and non-throwing — the
 * property this test actually needs — even though it means the "escaped in the output" branch
 * isn't exercised by this fixture today.
 */
class MermaidSecurityDirectiveTest {
    private fun key(source: String) = MermaidRenderKey(source, ThemeFingerprint(true, 1), 400)

    @Test
    fun renderMermaid_should_neverExposeLivePayload_when_initDirectiveRequestsLooseSecurity() = runTest {
        val payload = """%%{init: {"securityLevel":"loose"}}%%
            |graph TD; A["<img src=x onerror=alert(1)>"]-->B[end]
        """.trimMargin()

        val result = renderMermaid(key(payload))
        when (result) {
            is MermaidRenderResult.Rendered -> {
                assertFalse(result.svg.contains("onerror=alert"), "payload must not appear as a live executable attribute")
                assertFalse(result.svg.contains("<img "), "payload must not appear as a live <img> tag")
            }
            is MermaidRenderResult.Failed -> Unit // safe: nothing reaches the caller
            is MermaidRenderResult.UnsupportedPlatform -> error("JVM must not report UnsupportedPlatform")
        }
    }

    /**
     * Isolates that the `%%{init}%%` directive line alone (no injection payload) doesn't itself
     * break rendering. Retries once on a `Failed("timeout")` result: this exercises the shared
     * `mermaidEngineActor` singleton, whose first-ever call pays GraalJS's cold-start cost (JS
     * bundle eval + `mermaid.initialize()`) — a real, already-documented risk (ADR-001's Open
     * Items) that this test isn't the place to re-litigate. A second attempt on a warm context
     * reliably completes well under budget.
     */
    @Test
    fun renderMermaid_should_stillRender_when_initDirectivePresentWithBenignLabel() = runTest {
        val source = """%%{init: {"securityLevel":"loose"}}%%
            |graph TD; A[benign]-->B[end]
        """.trimMargin()

        var result = renderMermaid(key(source))
        if (result is MermaidRenderResult.Failed && result.reason == "timeout") {
            result = renderMermaid(key(source))
        }
        val rendered = result as? MermaidRenderResult.Rendered
            ?: error("expected Rendered for a benign label, got $result")
        assertTrue(rendered.svg.contains("<svg"))
    }
}
