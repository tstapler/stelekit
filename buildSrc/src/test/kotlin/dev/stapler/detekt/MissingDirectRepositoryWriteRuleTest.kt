package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class MissingDirectRepositoryWriteRuleTest {

    private val rule = MissingDirectRepositoryWriteRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags unannotated suspend write method in Repository interface`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun saveBlock(block: String): Unit
            }
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("saveBlock"))
    }

    @Test
    fun `flags multiple unannotated write methods in same interface`() {
        val findings = rule.compileAndLint("""
            interface PageRepository {
                suspend fun savePage(page: String): Unit
                suspend fun deletePage(id: String): Unit
                suspend fun updatePage(page: String): Unit
            }
        """.trimIndent())
        assertEquals(3, findings.size)
    }

    @Test
    fun `flags only the unannotated method when others are correctly annotated`() {
        val findings = rule.compileAndLint("""
            annotation class DirectRepositoryWrite
            interface BlockRepository {
                @DirectRepositoryWrite
                suspend fun saveBlock(block: String): Unit
                suspend fun deleteBlock(id: String): Unit
            }
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("deleteBlock"))
    }

    @Test
    fun `flags suspend write method returning Result`() {
        val findings = rule.compileAndLint("""
            interface SpanRepository {
                suspend fun insertSpan(span: String): Result<Unit>
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags interface whose name ends with Repository`() {
        val findings = rule.compileAndLint("""
            interface IPageRepository {
                suspend fun upsertPage(page: String): Unit
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag annotated write method`() {
        val findings = rule.compileAndLint("""
            annotation class DirectRepositoryWrite
            interface BlockRepository {
                @DirectRepositoryWrite
                suspend fun saveBlock(block: String): Unit
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Flow-returning suspend fun (read)`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun getBlockByUuid(uuid: String): Flow<String>
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag non-suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                fun getBlockByUuid(uuid: String): String
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag suspend fun in non-Repository interface`() {
        val findings = rule.compileAndLint("""
            interface DataSource {
                suspend fun insert(item: String): Unit
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag suspend fun in Repository class (not interface)`() {
        val findings = rule.compileAndLint("""
            class BlockRepository {
                suspend fun saveBlock(block: String): Unit = Unit
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag get-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun getStatistics(): String
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag find-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun findByTag(tag: String): List<String>
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag is-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun isEncrypted(): Boolean
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag has-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun hasBlocks(): Boolean
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag count-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun countBlocks(): Long
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag validate-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun validateIntegrity(): String
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag check-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun checkHealth(): Boolean
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag calculate-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun calculateDepth(id: String): Int
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag select-prefixed suspend fun`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun selectAll(): List<String>
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag getCacheStatistics (get-prefix exemption)`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun getCacheStatistics(): String
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `flags abstract class method if class name ends with Repository`() {
        // Rule only targets interfaces; abstract classes should not be flagged
        val findings = rule.compileAndLint("""
            abstract class BlockRepository {
                abstract suspend fun saveBlock(block: String): Unit
            }
        """.trimIndent())
        // Abstract classes are not KtClass.isInterface() — should not flag
        assertTrue(findings.isEmpty())
    }
}
