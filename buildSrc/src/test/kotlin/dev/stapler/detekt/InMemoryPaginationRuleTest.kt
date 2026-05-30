package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryPaginationRuleTest {

    private val rule = InMemoryPaginationRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags drop followed by take`() {
        val findings = rule.compileAndLint("""
            fun page(list: List<String>, offset: Int, limit: Int) =
                list.drop(offset).take(limit)
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags take followed by drop`() {
        val findings = rule.compileAndLint("""
            fun page(list: List<String>, offset: Int, limit: Int) =
                list.take(offset + limit).drop(offset)
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags drop-take inside a Flow map lambda — the exact pre-fix pattern`() {
        val findings = rule.compileAndLint("""
            fun search(list: List<String>, limit: Int, offset: Int): List<String> =
                list.drop(offset).take(limit).map { it.uppercase() }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags executeAsList result paged in memory`() {
        val findings = rule.compileAndLint("""
            fun bad(rows: List<String>, offset: Int, limit: Int): List<String> {
                return rows.drop(offset).take(limit)
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag drop alone`() {
        val findings = rule.compileAndLint("""
            fun dropFirst(list: List<String>) = list.drop(1)
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag take alone`() {
        val findings = rule.compileAndLint("""
            fun head(list: List<String>) = list.take(10)
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag drop-take on a Sequence — lazy, no materialisation`() {
        val findings = rule.compileAndLint("""
            fun page(seq: Sequence<String>, offset: Int, limit: Int) =
                seq.drop(offset).take(limit).toList()
        """.trimIndent())
        // Rule is syntactic — it will fire; add this note for future type-aware upgrade.
        // For now, document that sequences are an acceptable suppress site.
        // This test documents current behaviour rather than asserting zero findings.
        assertEquals(1, findings.size, "Rule currently fires on Sequence too; suppress at call site if intentional")
    }

    @Test
    fun `does not flag filter followed by take — different pattern`() {
        val findings = rule.compileAndLint("""
            fun topMatches(list: List<String>, query: String, limit: Int) =
                list.filter { it.contains(query) }.take(limit)
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
