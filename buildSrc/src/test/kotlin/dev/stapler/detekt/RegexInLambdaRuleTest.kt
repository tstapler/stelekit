package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class RegexInLambdaRuleTest {

    private val rule = RegexInLambdaRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags Regex construction with literal pattern inside lambda`() {
        val findings = rule.compileAndLint("""
            fun test(list: List<String>) = list.filter { Regex("\\d+").containsMatchIn(it) }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags toRegex() on literal string inside lambda`() {
        val findings = rule.compileAndLint("""
            fun test(list: List<String>) = list.map { it.split("\\s+".toRegex()) }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags multiple Regex constructions in same lambda`() {
        val findings = rule.compileAndLint("""
            fun test(list: List<String>) = list.filter {
                Regex("foo").containsMatchIn(it) && Regex("bar").containsMatchIn(it)
            }
        """.trimIndent())
        assertEquals(2, findings.size)
    }

    @Test
    fun `flags Regex in nested lambda`() {
        val findings = rule.compileAndLint("""
            fun test(map: Map<String, List<String>>) = map.mapValues { (_, v) ->
                v.filter { Regex("\\w+").containsMatchIn(it) }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag Regex at top-level val`() {
        val findings = rule.compileAndLint("""
            private val DIGITS = Regex("\\d+")
            fun test(list: List<String>) = list.filter { DIGITS.containsMatchIn(it) }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag dynamic Regex pattern with interpolation inside lambda`() {
        val findings = rule.compileAndLint("""
            fun test(name: String, list: List<String>) =
                list.filter { Regex("\\b${'$'}{Regex.escape(name)}\\b").containsMatchIn(it) }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Regex construction outside any lambda`() {
        val findings = rule.compileAndLint("""
            fun test(): Regex = Regex("\\d+")
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag toRegex on dynamic string outside lambda`() {
        val findings = rule.compileAndLint("""
            fun test(pattern: String): Regex = pattern.toRegex()
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
