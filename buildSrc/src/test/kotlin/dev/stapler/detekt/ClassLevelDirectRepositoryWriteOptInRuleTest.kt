package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassLevelDirectRepositoryWriteOptInRuleTest {

    private val rule = ClassLevelDirectRepositoryWriteOptInRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags StateManager with class-level OptIn`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class BlockStateManager
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("BlockStateManager"))
    }

    @Test
    fun `flags ViewModel with class-level OptIn`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class StelekitViewModel
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("StelekitViewModel"))
    }

    @Test
    fun `flags Panel composable with class-level OptIn`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class ReferencesPanel
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should NOT flag =====

    @Test
    fun `does not flag SqlDelightBlockRepository (Repository pattern)`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class SqlDelightBlockRepository
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag DatabaseWriteActor`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class DatabaseWriteActor
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag migration class`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class UuidMigration
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag ChangeApplier`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class ChangeApplier
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag InMemory test double`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class InMemoryBlockRepository
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag class without OptIn at all`() {
        val findings = rule.compileAndLint("""
            class BlockStateManager
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag interface with class-level OptIn`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            interface SomeStateInterface
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag class with OptIn on different annotation`() {
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class ExperimentalApi
            @OptIn(ExperimentalApi::class)
            class BlockStateManager
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `pre-fix pattern fires — class-level OptIn on StateManager would have been caught`() {
        // This test documents the exact pre-fix violation that caused the race bug.
        // It verifies that the rule would have fired against the original BlockStateManager.
        val findings = rule.compileAndLint("""
            annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<*>)
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class BlockStateManager(private val blockRepository: Any) {
                fun addNewBlock(uuid: String) {
                    // Without the class-level opt-in, blockRepository.splitBlock(...)
                    // would require an explicit @OptIn here, making the bypass visible.
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size, "Rule must fire on pre-fix BlockStateManager pattern")
    }
}
