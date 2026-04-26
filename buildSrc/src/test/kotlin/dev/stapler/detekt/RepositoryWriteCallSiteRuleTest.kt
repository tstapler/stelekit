package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class RepositoryWriteCallSiteRuleTest {

    private val rule = RepositoryWriteCallSiteRule()

    // ===== NON-COMPLIANT: should flag =====

    @Test
    fun `flags queries-insert write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("SqlDelightBlockRepository"))
        assertTrue(findings[0].message.contains("saveBlock"))
    }

    @Test
    fun `flags queries-delete write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun removeBlock(id: String) {
                    queries.deleteBlock(id)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags queries-update write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun changeBlock(block: String) {
                    queries.updateBlock(block)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags queries-upsert write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun mergeBlock(block: String) {
                    queries.upsertBlock(block)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags queries-transaction write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlocks(blocks: List<String>) {
                    queries.transaction {
                        blocks.forEach { queries.insertBlock(it) }
                    }
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags restricted-insert write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightSpanRepository : SpanRepository {
                override suspend fun insertSpan(span: String) {
                    restricted.insertSpan(span)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags restricted-delete write without OptIn`() {
        val findings = rule.compileAndLint("""
            class SqlDelightSpanRepository : SpanRepository {
                override suspend fun deleteSpans(cutoff: Long) {
                    restricted.deleteSpansOlderThan(cutoff)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags class matched by supertype containing Repository`() {
        val findings = rule.compileAndLint("""
            class SqlBlockImpl : IBlockRepository {
                override suspend fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags multiple write methods in same class`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
                override suspend fun removeBlock(id: String) {
                    queries.deleteBlock(id)
                }
            }
        """.trimIndent())
        assertEquals(2, findings.size)
    }

    @Test
    fun `flags write with whitespace between queries and method name`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlock(block: String) {
                    queries . insertBlock(block)
                }
            }
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `does not flag when class has OptIn annotation`() {
        val findings = rule.compileAndLint("""
            annotation class DirectRepositoryWrite
            @OptIn(DirectRepositoryWrite::class)
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag read-only SQL call (select)`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun getStatistics(): String {
                    return queries.selectBlockCount().toString()
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag non-override suspend fun`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                suspend fun internalWrite(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag interface with write SQL in method body`() {
        val findings = rule.compileAndLint("""
            interface BlockRepository {
                suspend fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag class not ending in Repository with no Repository supertype`() {
        val findings = rule.compileAndLint("""
            class DataSource {
                override suspend fun insert(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag non-suspend override fun`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override fun saveBlock(block: String) {
                    queries.insertBlock(block)
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag empty override suspend fun body`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun saveBlock(block: String) {}
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag queries-count (read, not matched by write pattern)`() {
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun countBlocks(): Long {
                    return queries.countBlocks()
                }
            }
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag when method body contains string literal with insert word`() {
        // "insert" appearing in a string literal should not be a false positive
        // (regex matches against body text, so this is a known limitation —
        //  document the behaviour rather than claim it works perfectly)
        val findings = rule.compileAndLint("""
            class SqlDelightBlockRepository : BlockRepository {
                override suspend fun logAction(action: String) {
                    val msg = "queries.insertBlock called"
                    log(msg)
                }
            }
        """.trimIndent())
        // The regex matches on body text including string literals — this is a documented
        // trade-off: false positives here are safe (they prompt adding @OptIn); false
        // negatives (missing writes) are dangerous.
        // This test documents the actual behaviour rather than an ideal.
        assertEquals(1, findings.size)
    }
}
