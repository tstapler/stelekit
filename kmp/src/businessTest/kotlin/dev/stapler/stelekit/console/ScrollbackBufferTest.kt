// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbackBufferTest {
    private fun out(text: String) = ScrollbackKind.OUTPUT to text

    @Test
    fun appendAll_should_SplitMultilineTextIntoLines() {
        val buffer = ScrollbackBuffer()
        buffer.appendAll(listOf(out("a\nb"), out("c")))
        assertEquals(listOf("a", "b", "c"), buffer.lines.value.map { it.text })
    }

    @Test
    fun appendAll_should_CutEachLineToTheCellCap() {
        val buffer = ScrollbackBuffer(maxCellChars = 10)
        buffer.appendAll(listOf(out("x".repeat(25))))
        assertEquals("xxxxxxxxxx ... [+15 chars]", buffer.lines.value.single().text)
    }

    @Test
    fun appendAll_should_NeverHoldMoreThanTheLimit_And_KeepTheNewestLines() = runTest {
        checkAll(Arb.int(1..40), Arb.int(0..120)) { limit, count ->
            val buffer = ScrollbackBuffer(maxLines = limit)
            repeat(count) { buffer.appendAll(listOf(out("line $it"))) }
            val lines = buffer.lines.value
            assertTrue(lines.size <= limit)
            if (count > 0) assertEquals("line ${count - 1}", lines.last().text)
            assertEquals(lines.map { it.id }.sorted().distinct(), lines.map { it.id }, "ids must be unique and ordered")
        }
    }

    @Test
    fun appendAll_should_EmitOnce_ForABatch() {
        val buffer = ScrollbackBuffer()
        buffer.appendAll(List(500) { out("l$it") })
        assertEquals(500, buffer.lines.value.size)
    }

    @Test
    fun clear_should_EmptyTheBuffer() {
        val buffer = ScrollbackBuffer()
        buffer.appendAll(listOf(out("a")))
        buffer.clear()
        assertTrue(buffer.lines.value.isEmpty())
    }
}
