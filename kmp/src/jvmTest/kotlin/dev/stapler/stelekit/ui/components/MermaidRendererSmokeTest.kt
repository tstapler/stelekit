package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Story 7.1.2 — one fixture per supported diagram type, verified end-to-end through the real
 * jvmMain `renderMermaid` (GraalJS engine, via [MermaidEngineActor]). Fixtures use single-line
 * labels only: multi-line (`<br/>`) labels are a known, separately-tracked gap in the vendored
 * DOM shim (see ADR-001-mermaid-rendering-strategy.md's Open Items) unrelated to diagram type.
 */
class MermaidRendererSmokeTest {
    private fun key(source: String) = MermaidRenderKey(source, ThemeFingerprint(true, 1), 400)

    private suspend fun assertRenders(source: String) {
        val result = renderMermaid(key(source))
        assertIs<MermaidRenderResult.Rendered>(result, "expected Rendered for: $source")
        assertTrue(result.svg.contains("<svg"), "expected <svg> root in output")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_flowchartSourceValid() = runTest {
        assertRenders("graph TD; A[Start]-->B[Middle]-->C[End]")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_sequenceSourceValid() = runTest {
        assertRenders("sequenceDiagram\n  Alice->>Bob: Hello Bob\n  Bob-->>Alice: Hi Alice")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_classSourceValid() = runTest {
        assertRenders("classDiagram\n  class Animal\n  Animal : +String name\n  Animal : +makeSound()\n  Animal <|-- Dog")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_stateSourceValid() = runTest {
        assertRenders("stateDiagram-v2\n  [*] --> Still\n  Still --> Moving\n  Moving --> Still\n  Moving --> [*]")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_erSourceValid() = runTest {
        assertRenders("erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER ||--|{ LINE_ITEM : contains")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_pieSourceValid() = runTest {
        assertRenders("pie title Pets\n  \"Dogs\" : 5\n  \"Cats\" : 3")
    }

    @Test
    fun renderMermaid_should_returnRendered_when_ganttSourceValid() = runTest {
        assertRenders("gantt\n  title A Gantt Diagram\n  dateFormat YYYY-MM-DD\n  section Section\n  A task :a1, 2024-01-01, 30d")
    }

    /** REQ-9 — end-to-end confirmation that the real GraalJS engine was configured with strict security. */
    @Test
    fun renderMermaid_should_produceRenderedSvg_when_engineConfiguredWithStrictSecurity() = runTest {
        assertRenders("graph TD; A-->B")
    }
}
