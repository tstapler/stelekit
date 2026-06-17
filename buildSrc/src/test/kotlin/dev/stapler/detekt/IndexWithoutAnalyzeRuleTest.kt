package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexWithoutAnalyzeRuleTest {

    private val rule = IndexWithoutAnalyzeRule()

    // Minimal stub so compileAndLint can resolve Migration without real imports
    private val preamble = """
        data class Migration(val name: String, val statements: List<String>, val allowContentUpdate: Boolean = false)
    """.trimIndent()

    // ── NON-COMPLIANT: should flag ────────────────────────────────────────────────────────────

    @Test
    fun `flags Migration with CREATE INDEX on blocks and no ANALYZE`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_blocks_covering_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("blocks"))
        assertTrue(findings[0].message.contains("add_blocks_covering_index"))
    }

    @Test
    fun `flags Migration with CREATE INDEX on pages and no ANALYZE`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_pages_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_pages_name ON pages(name)"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("pages"))
    }

    @Test
    fun `flags when next migration does not contain ANALYZE`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_blocks_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_pg ON blocks(page_uuid)"
                )),
                Migration(name = "add_something_else", statements = listOf(
                    "ALTER TABLE pages ADD COLUMN foo TEXT"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags CREATE UNIQUE INDEX on blocks without ANALYZE`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "unique_blocks_idx", statements = listOf(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_blocks_uuid ON blocks(uuid)"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags when ANALYZE is two migrations later (only immediately next counts)`() {
        // Documents that only the IMMEDIATELY following migration is checked.
        // If ANALYZE is two steps later, the planner can still see a large graph with
        // a new index and no statistics between the two app launches that run those migrations.
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_blocks_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_pg ON blocks(page_uuid)"
                )),
                Migration(name = "intermediate", statements = listOf(
                    "ALTER TABLE spans ADD COLUMN app_version TEXT NOT NULL DEFAULT ''"
                )),
                Migration(name = "analyze_later", statements = listOf(
                    "ANALYZE blocks"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags when next migration uses PRAGMA optimize instead of ANALYZE`() {
        // PRAGMA optimize is a startup hook wired into MigrationRunner.applyAll, not a
        // per-migration ANALYZE substitute. A migration must contain an explicit ANALYZE.
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_blocks_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)"
                )),
                Migration(name = "optimize_stats", statements = listOf(
                    "PRAGMA optimize"
                ))
            )
        """.trimIndent())
        assertEquals(1, findings.size)
    }

    // ── COMPLIANT: should not flag ────────────────────────────────────────────────────────────

    @Test
    fun `does not flag when same migration contains ANALYZE blocks`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_and_analyze", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
                    "ANALYZE blocks"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag when same migration contains bare ANALYZE`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_and_analyze", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_pg ON blocks(page_uuid)",
                    "ANALYZE"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag when immediately next migration contains ANALYZE blocks`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_blocks_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)"
                )),
                Migration(name = "analyze_blocks", statements = listOf(
                    "ANALYZE blocks"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag CREATE INDEX on a non-watched table`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "add_spans_index", statements = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_spans_version ON spans(app_version)"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag CREATE TABLE on blocks (not an index)`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "create_blocks", statements = listOf(
                    "CREATE TABLE IF NOT EXISTS blocks (uuid TEXT NOT NULL PRIMARY KEY)"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag DROP INDEX on blocks`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "drop_old_index", statements = listOf(
                    "DROP INDEX IF EXISTS idx_blocks_page_uuid"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag standalone ANALYZE migration`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "analyze_blocks", statements = listOf(
                    "ANALYZE blocks"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag Migration with non-index statements on blocks table`() {
        val findings = rule.compileAndLint("""
            $preamble
            val all = listOf(
                Migration(name = "alter_blocks", statements = listOf(
                    "ALTER TABLE blocks ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'"
                ))
            )
        """.trimIndent())
        assertTrue(findings.isEmpty())
    }
}
