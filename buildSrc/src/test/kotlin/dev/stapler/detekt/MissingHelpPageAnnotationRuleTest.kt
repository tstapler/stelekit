package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissingHelpPageAnnotationRuleTest {

    private val rule = MissingHelpPageAnnotationRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `reports data object Screen subclass without annotation`() {
        val findings = rule.compileAndLint("""
            sealed class Screen {
                data object Foo : Screen()
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `reports data class Screen subclass without annotation`() {
        val findings = rule.compileAndLint("""
            sealed class Screen {
                data class Foo(val x: Int) : Screen()
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `fires on data object in full sealed class Screen`() {
        val findings = rule.compileAndLint("""
            sealed class Screen {
                data object Foo : Screen()
            }
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Foo"))
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `silent on HelpPage-annotated Screen subclass`() {
        val findings = rule.compileAndLint("""
            annotation class HelpPage(val docs: kotlin.reflect.KClass<*>)
            sealed class Screen {
                @HelpPage(docs = Any::class)
                data object Foo : Screen()
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `silent on HelpExempt-annotated Screen subclass`() {
        val findings = rule.compileAndLint("""
            annotation class HelpExempt(val reason: String)
            sealed class Screen {
                @HelpExempt(reason = "debug only")
                data object Foo : Screen()
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `silent on non-Screen subclass`() {
        val findings = rule.compileAndLint("""
            open class OtherClass
            data object Foo : OtherClass()
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `silent on sealed Screen class itself`() {
        val findings = rule.compileAndLint("""
            sealed class Screen
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `silent on nested class inside Screen subclass`() {
        val findings = rule.compileAndLint("""
            annotation class HelpPage(val docs: kotlin.reflect.KClass<*>)
            sealed class Screen {
                @HelpPage(docs = Any::class)
                data object Foo : Screen() {
                    data class State(val loading: Boolean)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
