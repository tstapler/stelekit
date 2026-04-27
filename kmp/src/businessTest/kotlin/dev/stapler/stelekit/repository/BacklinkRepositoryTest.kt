package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BacklinkRepositoryTest {

    private val now = Clock.System.now()

    private fun makePage(uuid: String, name: String): Page = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now
    )

    private fun makeBlock(
        uuid: String,
        pageUuid: String,
        content: String,
        position: Int = 0
    ): Block = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        level = 0,
        position = position,
        createdAt = now,
        updatedAt = now
    )

    private fun makeRepo(vararg blocks: Block): InMemoryBlockRepository {
        val repo = InMemoryBlockRepository()
        runBlocking { repo.saveBlocks(blocks.toList()) }
        return repo
    }

    @Test
    fun `returns blocks containing wikilink to target page`() = runBlocking {
        // Page B has a block that links to Page A via [[A]]
        val blockInB = makeBlock("block-b1", "page-b", "[[A]] is a great page", position = 0)
        val blockInB2 = makeBlock("block-b2", "page-b", "Unrelated content", position = 1)
        val repo = makeRepo(blockInB, blockInB2)

        val result = repo.getLinkedReferences("A").first()

        assertTrue(result.isRight())
        val linked = result.getOrNull()!!
        assertEquals(1, linked.size)
        assertEquals("block-b1", linked.first().uuid)
    }

    @Test
    fun `returns empty list when no pages link to target`() = runBlocking {
        val blockInB = makeBlock("block-b1", "page-b", "This mentions something else entirely", position = 0)
        val repo = makeRepo(blockInB)

        val result = repo.getLinkedReferences("A").first()

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.isEmpty())
    }

    @Test
    fun `does not exclude blocks from the same page (self-references are included)`() = runBlocking {
        // InMemoryBlockRepository does NOT filter by page — self-references are returned.
        // This test documents that observed behavior explicitly.
        val selfRefBlock = makeBlock("block-a1", "page-a", "[[A]] self-reference", position = 0)
        val repo = makeRepo(selfRefBlock)

        val result = repo.getLinkedReferences("A").first()

        assertTrue(result.isRight())
        val linked = result.getOrNull()!!
        // Self-reference IS included — the in-memory impl returns all matching blocks
        // regardless of which page they belong to.
        assertTrue(linked.any { it.uuid == "block-a1" })
    }

    @Test
    fun `getLinkedReferences is case-insensitive`() = runBlocking {
        // InMemoryBlockRepository uses IGNORE_CASE, so [[a]], [[A]], and [[aA]] all match "A".
        val lowerCaseLink = makeBlock("block-1", "page-b", "see [[a]] for details", position = 0)
        val upperCaseLink = makeBlock("block-2", "page-c", "also [[A]] here", position = 0)
        val noLink = makeBlock("block-3", "page-d", "no link at all", position = 0)
        val repo = makeRepo(lowerCaseLink, upperCaseLink, noLink)

        val result = repo.getLinkedReferences("A").first()

        assertTrue(result.isRight())
        val linked = result.getOrNull()!!
        assertEquals(2, linked.size)
        assertTrue(linked.any { it.uuid == "block-1" })
        assertTrue(linked.any { it.uuid == "block-2" })
        assertFalse(linked.any { it.uuid == "block-3" })
    }

    @Test
    fun `returns multiple blocks across multiple pages that link to target`() = runBlocking {
        val block1 = makeBlock("block-1", "page-b", "I read about [[TargetPage]] today", position = 0)
        val block2 = makeBlock("block-2", "page-c", "[[TargetPage]] is useful", position = 0)
        val block3 = makeBlock("block-3", "page-d", "No mention here", position = 0)
        val repo = makeRepo(block1, block2, block3)

        val result = repo.getLinkedReferences("TargetPage").first()

        assertTrue(result.isRight())
        val linked = result.getOrNull()!!
        assertEquals(2, linked.size)
        assertTrue(linked.any { it.uuid == "block-1" })
        assertTrue(linked.any { it.uuid == "block-2" })
    }

    @Test
    fun `matches wikilink with alias syntax`() = runBlocking {
        // [[PageName|alias]] form should also be matched
        val aliasBlock = makeBlock("block-1", "page-b", "See [[PageName|a friendly alias]]", position = 0)
        val repo = makeRepo(aliasBlock)

        val result = repo.getLinkedReferences("PageName").first()

        assertTrue(result.isRight())
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("block-1", result.getOrNull()!!.first().uuid)
    }
}
