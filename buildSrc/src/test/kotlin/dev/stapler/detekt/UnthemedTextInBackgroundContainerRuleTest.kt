package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnthemedTextInBackgroundContainerRuleTest {

    private val rule = UnthemedTextInBackgroundContainerRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags Text without color inside Column with background modifier`() {
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun test() {
                Column(modifier = Modifier.background("red")) {
                    Text("hello")
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags Text without color inside Row with background modifier`() {
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String) {}
            fun Row(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun test() {
                Row(modifier = Modifier.background("blue")) {
                    Text("world")
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags Text without color inside Box with background modifier`() {
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String) {}
            fun Box(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun test() {
                Box(modifier = Modifier.background("green")) {
                    Text("inside")
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags the exact TableBlock-style pattern that caused the dark theme bug`() {
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String, style: Any = Unit) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun tableHeaderRow(headerColor: Any) {
                Column(modifier = Modifier.background(headerColor)) {
                    Text("Scenario")
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== KNOWN GAPS: rule does NOT catch these patterns =====

    @Test
    fun `does NOT flag Text inside an extracted composable called from a background container (known gap)`() {
        // This is the pattern that caused the original dark-theme bug in TableBlock.
        // TableCell had Text without color; the rule only sees the TableCell call-site,
        // not the Text inside TableCell. Fix: ensure extracted composables pass explicit
        // color to their Text calls — the rule will catch it if they have their own
        // .background() modifier but not otherwise.
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun TableCell(text: String) {
                Text(text)   // ← missing color; rule cannot see inside TableCell
            }

            fun test() {
                Column(modifier = Modifier.background("headerColor")) {
                    TableCell("value")   // ← rule only sees this call, not the Text inside
                }
            }
            """.trimIndent()
        )
        // Rule does NOT flag this — the gap is intentional (static PSI analysis limit).
        // The only enforcement for extracted-composable patterns is code review.
        assertTrue(findings.isEmpty())
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag Text with explicit color inside Column with background`() {
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String, color: Any = Unit) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun test() {
                Column(modifier = Modifier.background("red")) {
                    Text("hello", color = "white")
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Text without color inside container without background`() {
        val findings = rule.compileAndLint(
            """
            fun Text(text: String) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            object Modifier

            fun test() {
                Column {
                    Text("no background here")
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Text nested inside a non-container child of background container`() {
        // Text is in a nested composable, not a direct child — the nested composable
        // is responsible for its own colour management.
        val findings = rule.compileAndLint(
            """
            fun Modifier.background(color: Any): Any = this
            fun Text(text: String) {}
            fun Column(modifier: Any = Unit, content: () -> Unit) {}
            fun AnotherComposable(content: () -> Unit) {}
            object Modifier

            fun test() {
                Column(modifier = Modifier.background("red")) {
                    AnotherComposable {
                        Text("deeply nested")
                    }
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Text directly in a composable that has no container parent`() {
        val findings = rule.compileAndLint(
            """
            fun Text(text: String) {}

            fun test() {
                Text("standalone")
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }
}
