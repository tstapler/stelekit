package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoRunBlockingInLifecycleRuleTest {

    private val rule = NoRunBlockingInLifecycleRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags runBlocking inside override onCreate — the exact pattern that caused the ANR`() {
        val findings = rule.compileAndLint(
            """
            fun runBlocking(block: suspend () -> Unit): Unit {}
            open class Application { open fun onCreate() {} }

            class MyApp : Application() {
                override fun onCreate() {
                    super.onCreate()
                    runBlocking { doWork() }
                }
                suspend fun doWork() {}
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags runBlocking inside override onStart`() {
        val findings = rule.compileAndLint(
            """
            fun runBlocking(block: suspend () -> Unit): Unit {}
            open class Activity { open fun onStart() {} }

            class MyActivity : Activity() {
                override fun onStart() {
                    runBlocking { fetchData() }
                }
                suspend fun fetchData() {}
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags runBlocking nested inside override onCreate body`() {
        val findings = rule.compileAndLint(
            """
            fun runBlocking(block: suspend () -> Unit): Unit {}
            open class Application { open fun onCreate() {} }

            class MyApp : Application() {
                override fun onCreate() {
                    val active = true
                    if (active) {
                        runBlocking { flush() }
                    }
                }
                suspend fun flush() {}
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag runBlocking in a non-lifecycle override function`() {
        val findings = rule.compileAndLint(
            """
            fun runBlocking(block: suspend () -> Unit): Unit {}
            interface Worker { fun doWork() }

            class MyWorker : Worker {
                override fun doWork() {
                    runBlocking { process() }
                }
                suspend fun process() {}
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag runBlocking in a non-override onCreate function`() {
        val findings = rule.compileAndLint(
            """
            fun runBlocking(block: suspend () -> Unit): Unit {}

            class Foo {
                fun onCreate() {
                    runBlocking { doWork() }
                }
                suspend fun doWork() {}
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag a lifecycle override that does not use runBlocking`() {
        val findings = rule.compileAndLint(
            """
            open class Application { open fun onCreate() {} }

            class MyApp : Application() {
                override fun onCreate() {
                    super.onCreate()
                    initSync()
                }
                fun initSync() {}
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }
}
