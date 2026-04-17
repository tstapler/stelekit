package dev.stapler.stelekit.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentHasherTest {

    // ---------------------------------------------------------------------------
    // SHA-256 correctness — known test vectors from FIPS 180-4 / NIST
    // ---------------------------------------------------------------------------

    @Test
    fun sha256_empty_string() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHasher.sha256("")
        )
    }

    @Test
    fun sha256_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHasher.sha256("abc")
        )
    }

    @Test
    fun sha256_produces_64_char_hex() {
        val hash = ContentHasher.sha256("hello world")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun sha256_is_deterministic() {
        val content = "This is a test block with [[links]] and #tags"
        assertEquals(ContentHasher.sha256(content), ContentHasher.sha256(content))
    }

    @Test
    fun sha256_different_inputs_produce_different_hashes() {
        assertNotEquals(ContentHasher.sha256("block A"), ContentHasher.sha256("block B"))
    }

    @Test
    fun sha256_long_input() {
        // Exercises multi-block SHA-256 (> 55 bytes triggers a second 512-bit block)
        val long = "a".repeat(1000)
        val hash = ContentHasher.sha256(long)
        assertEquals(64, hash.length)
    }

    // ---------------------------------------------------------------------------
    // Normalisation
    // ---------------------------------------------------------------------------

    @Test
    fun normalizeForHash_trims_whitespace() {
        assertEquals(
            ContentHasher.normalizeForHash("  hello  "),
            ContentHasher.normalizeForHash("hello")
        )
    }

    @Test
    fun normalizeForHash_crlf_equals_lf() {
        val withCrlf = "line one\r\nline two"
        val withLf = "line one\nline two"
        assertEquals(
            ContentHasher.normalizeForHash(withCrlf),
            ContentHasher.normalizeForHash(withLf)
        )
    }

    @Test
    fun sha256ForContent_treats_whitespace_variants_as_identical() {
        assertEquals(
            ContentHasher.sha256ForContent("  same content\r\n"),
            ContentHasher.sha256ForContent("same content\n")
        )
    }

    // ---------------------------------------------------------------------------
    // Collision-safety contract (simulated)
    //
    // SHA-256 collisions are computationally infeasible to produce, so we cannot
    // construct a real collision in a test.  Instead, we verify the LOGIC that
    // findDuplicateBlocks uses — groupBy { it.content } — would correctly separate
    // two blocks that happen to share a hash but have different content.
    // ---------------------------------------------------------------------------

    @Test
    fun collision_simulation_groupBy_separates_different_content() {
        data class FakeBlock(val content: String, val hash: String)

        val realDuplicate1 = FakeBlock("identical content", ContentHasher.sha256ForContent("identical content"))
        val realDuplicate2 = FakeBlock("identical content", ContentHasher.sha256ForContent("identical content"))
        val collisionVictim = FakeBlock("different content", realDuplicate1.hash) // Same hash, different content

        val allThree = listOf(realDuplicate1, realDuplicate2, collisionVictim)

        // Simulate the second pass in findDuplicateBlocks
        val groups = allThree.groupBy { it.content }.filter { it.value.size > 1 }

        // Only the true duplicates form a group; the collision victim is alone
        assertEquals(1, groups.size, "Exactly one true-duplicate group expected")
        assertEquals("identical content", groups.keys.first())
        assertEquals(2, groups.values.first().size)
    }

    @Test
    fun collision_simulation_all_unique_content_produces_no_groups() {
        data class FakeBlock(val content: String)
        val blocks = listOf(FakeBlock("A"), FakeBlock("B"), FakeBlock("C"))
        val groups = blocks.groupBy { it.content }.filter { it.value.size > 1 }
        assertTrue(groups.isEmpty())
    }
}
