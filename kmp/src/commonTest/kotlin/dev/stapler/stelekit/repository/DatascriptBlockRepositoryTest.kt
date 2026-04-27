package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
class DatascriptBlockRepositoryTest {

    private lateinit var repository: DatascriptBlockRepository
    private val now = Clock.System.now()

    @BeforeTest
    fun setup() {
        repository = DatascriptBlockRepository()
    }

    private fun createBlock(uuidSuffix: Long, parentUuidSuffix: Long? = null, position: Int, content: String = "Block $uuidSuffix"): Block {
        // Pad ID to make valid UUID: 00000000-0000-0000-0000-000000000001
        val uuid = "00000000-0000-0000-0000-${uuidSuffix.toString().padStart(12, '0')}"
        val pageUuid = "00000000-0000-0000-0000-000000000001"
        val parentUuid = parentUuidSuffix?.let { "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}" }
        
        return Block(
            uuid = uuid,
            pageUuid = pageUuid,
            content = content,
            parentUuid = parentUuid,
            position = position,
            leftUuid = null, // simplified for helper, logic should handle it
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun testIndentBlock() = runTest {
        // Setup: B1 -> B2. Indent B2 into B1.
        val b1 = createBlock(1, position = 0)
        val b2 = createBlock(2, position = 1)
        val uuid2 = b2.uuid
        val uuid1 = b1.uuid
        
        repository.saveBlock(b1)
        repository.saveBlock(b2)

        // Action
        val result = repository.indentBlock(uuid2)
        
        // Verify
        assertTrue(result.isRight(), "Indent failed: ${result.leftOrNull()?.let { RuntimeException(it.message) }}")
        
        // Fetch B2 to check parent
        val res = repository.getBlockByUuid(uuid2).first()
        val block = res.getOrNull()
        assertNotNull(block)
        assertEquals(uuid1, block.parentUuid) // Should now be child of B1
    }

    @Test
    fun testMoveBlockUp() = runTest {
        // Setup: B1 -> B2. Move B2 up.
        val b1 = createBlock(1, position = 0)
        val b2 = createBlock(2, position = 1)
        val uuid1 = b1.uuid
        val uuid2 = b2.uuid
        
        repository.saveBlock(b1)
        repository.saveBlock(b2)
        
        // Action
        val result = repository.moveBlockUp(uuid2)
        assertTrue(result.isRight())
        
        // Verify positions
        val pageUuid = "00000000-0000-0000-0000-000000000001"
        val res = repository.getBlocksForPage(pageUuid).first()
        val blocks = res.getOrNull() ?: emptyList()
        assertEquals(2, blocks.size)
        assertEquals(uuid2, blocks[0].uuid) // B2 first
        assertEquals(uuid1, blocks[1].uuid) // B1 second
    }
    
    @Test
    fun testMoveBlockDown() = runTest {
        // Setup: B1 -> B2. Move B1 down.
        val b1 = createBlock(1, position = 0)
        val b2 = createBlock(2, position = 1)
        val uuid1 = b1.uuid
        val uuid2 = b2.uuid
        
        repository.saveBlock(b1)
        repository.saveBlock(b2)
        
        // Action
        val result = repository.moveBlockDown(uuid1)
        assertTrue(result.isRight())
        
        // Verify positions
        val pageUuid = "00000000-0000-0000-0000-000000000001"
        val res = repository.getBlocksForPage(pageUuid).first()
        val blocks = res.getOrNull() ?: emptyList()
        assertEquals(2, blocks.size)
        assertEquals(uuid2, blocks[0].uuid) // B2 first
        assertEquals(uuid1, blocks[1].uuid) // B1 second
    }
}
