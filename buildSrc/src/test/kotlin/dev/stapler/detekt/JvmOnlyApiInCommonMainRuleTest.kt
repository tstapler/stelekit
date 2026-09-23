package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmOnlyApiInCommonMainRuleTest {

    private val rule = JvmOnlyApiInCommonMainRule()

    // ── Non-compliant: must flag ──────────────────────────────────────────────

    @Test
    fun `flags Charsets UTF-8 in ByteArray toString`() {
        val findings = rule.compileAndLint("""
            fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags Charsets UTF-8 in String toByteArray`() {
        val findings = rule.compileAndLint("""
            fun encode(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags Charsets standalone reference`() {
        val findings = rule.compileAndLint("""
            val charset = Charsets.UTF_8
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ── Compliant: must not flag ──────────────────────────────────────────────

    @Test
    fun `is silent when using decodeToString`() {
        val findings = rule.compileAndLint("""
            fun decode(bytes: ByteArray): String = bytes.decodeToString()
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `is silent when using encodeToByteArray`() {
        val findings = rule.compileAndLint("""
            fun encode(s: String): ByteArray = s.encodeToByteArray()
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `is silent when Charsets is not referenced`() {
        val findings = rule.compileAndLint("""
            fun noop(s: String): ByteArray = s.toByteArray()
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
