package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.test.runTest

/** Story 7.1.3 — malformed input and pathological hangs both degrade to [MermaidRenderResult.Failed], never throw. */
class MermaidRendererFallbackTest {
    private fun key(source: String) = MermaidRenderKey(source, ThemeFingerprint(true, 1), 400)

    @Test
    fun renderMermaid_should_returnFailedNotThrow_when_syntaxMalformed() = runTest {
        val result = renderMermaid(key("graph TD; A --> "))
        assertIs<MermaidRenderResult.Failed>(result)
    }

    @Test
    fun renderMermaidWith_should_returnFailed_when_engineRenderExceedsTimeout() = runTest {
        val hangingEngine = object : MermaidJvmEngine() {
            override fun render(source: String): String {
                Thread.sleep(Long.MAX_VALUE)
                error("unreachable")
            }
        }
        lateinit var result: MermaidRenderResult
        val elapsed = measureTime {
            result = renderMermaidWith(hangingEngine, key("graph TD; A-->B"))
        }
        assertIs<MermaidRenderResult.Failed>(result)
        assertTrue(
            elapsed.inWholeMilliseconds < MERMAID_RENDER_TIMEOUT_MS + 500,
            "expected completion within ${MERMAID_RENDER_TIMEOUT_MS + 500}ms, took ${elapsed.inWholeMilliseconds}ms",
        )
    }

    @Test
    fun renderMermaidWith_should_returnFailed_when_sourceEmpty() = runTest {
        val result = renderMermaidWith(MermaidJvmEngine(), key(""))
        assertIs<MermaidRenderResult.Failed>(result)
    }

    @Test
    fun renderMermaidWith_should_returnFailed_when_sourceExceedsMaxLength() = runTest {
        val oversized = "graph TD; " + "A-->B; ".repeat(MAX_MERMAID_SOURCE_LENGTH)
        val result = renderMermaidWith(MermaidJvmEngine(), key(oversized))
        assertIs<MermaidRenderResult.Failed>(result)
    }
}
