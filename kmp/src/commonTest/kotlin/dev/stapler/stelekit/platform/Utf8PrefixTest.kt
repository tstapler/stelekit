// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8PrefixTest {
    @Test
    fun ascii_should_BeCutAtMaxBytes() {
        assertContentEquals("abcd".encodeToByteArray(), Utf8Prefix.of("abcdefgh", 4))
    }

    @Test
    fun shortText_should_BeReturnedWhole() {
        assertContentEquals("ab".encodeToByteArray(), Utf8Prefix.of("ab", 256))
    }

    @Test
    fun multiByteCharacters_should_BeTruncatedToMaxBytes() {
        val prefix = Utf8Prefix.of("ééé", 3)
        assertEquals(3, prefix.size)
    }

    @Test
    fun zeroOrNegative_should_BeEmpty() {
        assertEquals(0, Utf8Prefix.of("abc", 0).size)
        assertEquals(0, Utf8Prefix.of("abc", -1).size)
    }

    @Test
    fun prefix_should_AlwaysBeAPrefixOfTheFullEncodingUpToMaxBytes() = runTest {
        checkAll(Arb.string(0..60), Arb.int(0..80)) { text, max ->
            val full = text.encodeToByteArray()
            val prefix = Utf8Prefix.of(text, max)
            assertTrue(prefix.size <= max)
            val expected = minOf(max, full.size)
            // Characters beyond the slice are never encoded, so the prefix can only be shorter than min(max, full) when
            // every character in the slice is multi-byte-free; it is never longer and always equals full's head.
            assertTrue(prefix.size <= expected)
            assertContentEquals(full.copyOf(prefix.size), prefix)
        }
    }
}
