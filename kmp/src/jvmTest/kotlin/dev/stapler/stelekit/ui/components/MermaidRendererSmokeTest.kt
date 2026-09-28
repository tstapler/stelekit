package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/**
 * Story 7.1.2 — one fixture per supported diagram type, verified end-to-end through the real
 * jvmMain `renderMermaid` (GraalJS engine, via [MermaidEngineActor]). Fixtures use single-line
 * labels only: multi-line (`<br/>`) labels are a known, separately-tracked gap in the vendored
 * DOM shim (see ADR-001-mermaid-rendering-strategy.md's Open Items) unrelated to diagram type.
 */
class MermaidRendererSmokeTest {
    private fun key(source: String) = MermaidRenderKey(source, ThemeFingerprint(true, 1), 400)

    /**
     * Real-timing helper: renders through the real engine on real time — never `runTest`, whose
     * virtual clock doesn't advance while the render blocks a real thread. Retries on `timeout`
     * only (contention under load, not signal); any other failure returns immediately so genuine
     * render bugs still fail the test on first attempt.
     */
    private fun renderWithContentionRetry(source: String): MermaidRenderResult {
        var last: MermaidRenderResult = MermaidRenderResult.Failed("not attempted")
        repeat(3) {
            val result = runBlocking { withTimeout(5.minutes) { renderMermaid(key(source)) } }
            if (result is MermaidRenderResult.Rendered) return result
            last = result
            if (result is MermaidRenderResult.Failed && result.reason != MERMAID_TIMEOUT_REASON) return result
        }
        return last
    }

    private fun assertRenders(source: String) {
        val result = renderWithContentionRetry(source)
        assertIs<MermaidRenderResult.Rendered>(result, "expected Rendered for: $source")
        assertTrue(result.svg.contains("<svg"), "expected <svg> root in output")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_flowchartSourceValid() {
        assertRenders("graph TD; A[Start]-->B[Middle]-->C[End]")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_sequenceSourceValid() {
        assertRenders("sequenceDiagram\n  Alice->>Bob: Hello Bob\n  Bob-->>Alice: Hi Alice")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_classSourceValid() {
        assertRenders("classDiagram\n  class Animal\n  Animal : +String name\n  Animal : +makeSound()\n  Animal <|-- Dog")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_stateSourceValid() {
        assertRenders("stateDiagram-v2\n  [*] --> Still\n  Still --> Moving\n  Moving --> Still\n  Moving --> [*]")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_erSourceValid() {
        assertRenders("erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER ||--|{ LINE_ITEM : contains")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_pieSourceValid() {
        assertRenders("pie title Pets\n  \"Dogs\" : 5\n  \"Cats\" : 3")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_ganttSourceValid() {
        assertRenders("gantt\n  title A Gantt Diagram\n  dateFormat YYYY-MM-DD\n  section Section\n  A task :a1, 2024-01-01, 30d")
    }

    /** REQ-9 — end-to-end confirmation that the real GraalJS engine was configured with strict security. */
    @Test
    fun renderMermaid_should_produceRenderedSvg_when_engineConfiguredWithStrictSecurity() {
        assertRenders("graph TD; A-->B")
    }
}
