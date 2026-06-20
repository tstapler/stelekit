package dev.stapler.stelekit.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FractionalIndexingTest {

    @Test
    fun `generateKeyBetween null null returns a0`() {
        assertEquals("a0", FractionalIndexing.generateKeyBetween(null, null))
    }

    @Test
    fun `generateKeyBetween a0 null returns key greater than a0`() {
        val key = FractionalIndexing.generateKeyBetween("a0", null)
        assertTrue(key > "a0", "Expected key > 'a0', got '$key'")
    }

    @Test
    fun `generateKeyBetween null a0 returns key less than a0`() {
        val key = FractionalIndexing.generateKeyBetween(null, "a0")
        assertTrue(key < "a0", "Expected key < 'a0', got '$key'")
    }

    @Test
    fun `generateKeyBetween a0 a1 returns key strictly between`() {
        val key = FractionalIndexing.generateKeyBetween("a0", "a1")
        assertTrue(key > "a0" && key < "a1", "Expected 'a0' < '$key' < 'a1'")
    }

    @Test
    fun `1000 successive appends produce strictly increasing keys`() {
        var prev = FractionalIndexing.generateKeyBetween(null, null)
        repeat(1000) { i ->
            val next = FractionalIndexing.generateKeyBetween(prev, null)
            assertTrue(next > prev, "Key not strictly increasing at step $i: prev='$prev', next='$next'")
            prev = next
        }
    }

    @Test
    fun `generates key between zero-padded migration keys`() {
        val key = FractionalIndexing.generateKeyBetween("00000000000", "00000000002")
        assertTrue(
            key > "00000000000" && key < "00000000002",
            "Expected '00000000000' < '$key' < '00000000002'"
        )
    }
}
