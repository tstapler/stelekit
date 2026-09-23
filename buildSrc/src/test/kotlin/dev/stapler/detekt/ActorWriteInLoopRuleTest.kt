package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActorWriteInLoopRuleTest {

    private val rule = ActorWriteInLoopRule()

    // ===== NON-COMPLIANT: pre-fix regression patterns that must be flagged =====

    @Test
    fun `flags deleteBlock inside forEach - original regression pattern`() {
        // This is the exact pattern that caused 100-250 s of untraced delay in
        // GraphLoader.dispatchFullBlockWrites (TC-BATCH-01 regression).
        val findings = rule.compileAndLint(
            """
            fun dispatchFullBlockWrites(writeActor: Any, toDelete: List<String>) {
                toDelete.forEach { uuid ->
                    writeActor.deleteBlock(uuid)
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("deleteBlock"))
    }

    @Test
    fun `flags saveBlock inside for loop`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, blocks: List<String>) {
                for (block in blocks) {
                    writeActor.saveBlock(block)
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("saveBlock"))
    }

    @Test
    fun `flags savePage inside map lambda`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, pages: List<String>) {
                pages.map { page ->
                    writeActor.savePage(page)
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags actor call inside while loop`() {
        val findings = rule.compileAndLint(
            """
            fun save(actor: Any, items: Iterator<String>) {
                while (items.hasNext()) {
                    actor.deleteBlock(items.next())
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags actor call inside do-while loop`() {
        val findings = rule.compileAndLint(
            """
            fun save(actor: Any, uuid: String, hasMore: Boolean) {
                do {
                    actor.saveBlock(uuid)
                } while (hasMore)
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags actor call inside forEachIndexed`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, blocks: List<String>) {
                blocks.forEachIndexed { _, block ->
                    writeActor.saveBlock(block)
                }
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: must NOT be flagged =====

    @Test
    fun `does not flag deleteBlock outside a loop`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, uuid: String) {
                writeActor.deleteBlock(uuid)
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag repository deleteBlock in forEach (receiver has no actor in name)`() {
        // Repository methods have the same names but the receiver is not an actor — no flag
        val findings = rule.compileAndLint(
            """
            fun save(blockRepository: Any, uuids: List<String>) {
                uuids.forEach { uuid ->
                    blockRepository.deleteBlock(uuid)
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag actor call directly inside execute lambda (not in a loop)`() {
        // Inside execute { }, writes are already batched — walking up to execute stops traversal
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, page: String) {
                writeActor.execute {
                    writeActor.savePage(page)
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag actor call inside if branch (not a loop)`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, uuid: String, condition: Boolean) {
                if (condition) {
                    writeActor.deleteBlock(uuid)
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag actor call inside when expression (not a loop)`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, uuid: String, mode: Int) {
                when (mode) {
                    1 -> writeActor.deleteBlock(uuid)
                    else -> writeActor.saveBlock(uuid)
                }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag execute method itself in forEach (execute is excluded from ACTOR_WRITE_METHODS)`() {
        val findings = rule.compileAndLint(
            """
            fun save(writeActor: Any, pages: List<String>) {
                pages.forEach { page ->
                    writeActor.execute { Unit }
                }
            }
            """.trimIndent()
        )
        // execute is not in ACTOR_WRITE_METHODS, so the forEach call to execute is fine
        assertTrue(findings.isEmpty())
    }
}
