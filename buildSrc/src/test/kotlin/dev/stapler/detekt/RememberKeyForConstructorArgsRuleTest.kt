package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RememberKeyForConstructorArgsRuleTest {

    private val rule = RememberKeyForConstructorArgsRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags remember without key when constructor takes a name-reference arg`() {
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()
            class ViewModel(val pipeline: Any)

            fun test(pipeline: Any) {
                val vm = remember { ViewModel(pipeline) }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags remember without key when constructor takes multiple name-reference args`() {
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()
            class ViewModel(val a: Any, val b: Any)

            fun test(pipeline: Any, service: Any) {
                val vm = remember { ViewModel(pipeline, service) }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags the exact pattern that caused the voice pipeline bug`() {
        // Regression test: remember { VoiceCaptureViewModel(voicePipeline, ...) }
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()
            class JournalService
            class VoicePipelineConfig
            class VoiceCaptureViewModel(val pipeline: VoicePipelineConfig, val service: JournalService)

            fun test(voicePipeline: VoicePipelineConfig, journalService: JournalService) {
                val vm = remember { VoiceCaptureViewModel(voicePipeline, journalService) }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag remember when the captured arg is listed as a key`() {
        val findings = rule.compileAndLint(
            """
            fun <T> remember(key: Any, block: () -> T): T = block()
            class ViewModel(val pipeline: Any)

            fun test(pipeline: Any) {
                val vm = remember(pipeline) { ViewModel(pipeline) }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag remember with no constructor args`() {
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()
            class ViewModel

            fun test() {
                val vm = remember { ViewModel() }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag remember when constructor takes only non-name-reference args`() {
        // Literal values and complex expressions are not flagged — they can't change.
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()

            fun test() {
                val x = remember { StringBuilder(256) }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag lowercase function calls inside remember`() {
        // Only uppercase callee names (constructor convention) are checked.
        val findings = rule.compileAndLint(
            """
            fun remember(block: () -> Any): Any = block()
            fun buildThing(x: Any): Any = x

            fun test(value: Any) {
                val x = remember { buildThing(value) }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag remember with multiple keys`() {
        val findings = rule.compileAndLint(
            """
            fun <T> remember(k1: Any, k2: Any, block: () -> T): T = block()
            class ViewModel(val a: Any, val b: Any)

            fun test(a: Any, b: Any) {
                val vm = remember(a, b) { ViewModel(a, b) }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }
}
