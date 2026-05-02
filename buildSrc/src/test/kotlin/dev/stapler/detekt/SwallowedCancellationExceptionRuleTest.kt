package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwallowedCancellationExceptionRuleTest {

    private val rule = SwallowedCancellationExceptionRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags catch(Exception) in suspend function`() {
        val findings = rule.compileAndLint("""
            suspend fun load() {
                try {
                    doWork()
                } catch (e: Exception) {
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags catch(Throwable) in suspend function`() {
        val findings = rule.compileAndLint("""
            suspend fun load() {
                try {
                    doWork()
                } catch (e: Throwable) {
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags catch(Exception) inside launch builder`() {
        val findings = rule.compileAndLint("""
            import kotlinx.coroutines.*
            fun start(scope: CoroutineScope) {
                scope.launch {
                    try {
                        doWork()
                    } catch (e: Exception) {
                        println(e.message)
                    }
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags catch(Exception) inside async builder`() {
        val findings = rule.compileAndLint("""
            import kotlinx.coroutines.*
            fun start(scope: CoroutineScope) {
                scope.async {
                    try {
                        doWork()
                    } catch (e: Exception) {
                        println(e.message)
                    }
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags anonymous catch(_) in suspend function`() {
        val findings = rule.compileAndLint("""
            suspend fun load() {
                try {
                    doWork()
                } catch (_: Exception) {
                    // swallowing intentionally
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag when preceding CancellationException catch exists`() {
        val findings = rule.compileAndLint("""
            import kotlinx.coroutines.CancellationException
            suspend fun load() {
                try {
                    doWork()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag when body contains CancellationException guard`() {
        val findings = rule.compileAndLint("""
            import kotlinx.coroutines.CancellationException
            suspend fun load() {
                try {
                    doWork()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag when body rethrows the caught exception unconditionally`() {
        val findings = rule.compileAndLint("""
            suspend fun load() {
                try {
                    doWork()
                } catch (e: Exception) {
                    println(e.message)
                    throw e
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag catch(Exception) in regular non-suspend function`() {
        val findings = rule.compileAndLint("""
            fun migrate() {
                try {
                    readFile()
                } catch (e: Exception) {
                    println("migration failed")
                }
            }
            fun readFile() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag specific exception types in suspend function`() {
        val findings = rule.compileAndLint("""
            suspend fun load() {
                try {
                    doWork()
                } catch (e: IllegalStateException) {
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag catch(IOException) even though broad`() {
        val findings = rule.compileAndLint("""
            import java.io.IOException
            suspend fun load() {
                try {
                    doWork()
                } catch (e: IOException) {
                    println(e.message)
                }
            }
            fun doWork() {}
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
