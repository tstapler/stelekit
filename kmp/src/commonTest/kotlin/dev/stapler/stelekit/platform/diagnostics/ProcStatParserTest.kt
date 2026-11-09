package dev.stapler.stelekit.platform.diagnostics

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcStatParserTest {
    // pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ...
    private fun statLine(name: String, utime: Long, stime: Long) =
        "123 ($name) S 1 1 0 0 -1 4194560 100 0 0 0 $utime $stime 0 0 20 0 5 0 99999 1000 100"

    @Test
    fun parseLine_should_SumUtimeAndStime() {
        val stat = ProcStatParser.parseLine(statLine("RenderThread", 40, 2))

        assertEquals(ThreadStat("RenderThread", 42), stat)
    }

    @Test
    fun parseLine_should_HandleCommWithSpacesAndParentheses() {
        val stat = ProcStatParser.parseLine(statLine("weird (name) x", 7, 3))

        assertEquals(ThreadStat("weird (name) x", 10), stat)
    }

    @Test
    fun parseLine_should_ReturnNull_When_Malformed() {
        assertNull(ProcStatParser.parseLine(""))
        assertNull(ProcStatParser.parseLine("no parens here"))
        assertNull(ProcStatParser.parseLine("1 (x) S 1 2"))
        assertNull(ProcStatParser.parseLine("1 (x) S 1 1 0 0 -1 0 0 0 0 0 abc 5 0"))
    }

    @Test
    fun groupName_should_MergePoolThreadsThatDifferByIndex() {
        assertEquals("DefaultDispatcher-worker", ProcStatParser.groupName("DefaultDispatcher-worker-3"))
        assertEquals("pool-2-thread", ProcStatParser.groupName("pool-2-thread-1"))
        assertEquals("RenderThread", ProcStatParser.groupName("RenderThread"))
        assertEquals("123", ProcStatParser.groupName("123"))
    }

    @Test
    fun topGroups_should_ReturnLargestThreeGroupsFirst() {
        val threads = listOf(
            ThreadStat("main", 50),
            ThreadStat("DefaultDispatcher-worker-1", 20),
            ThreadStat("DefaultDispatcher-worker-2", 25),
            ThreadStat("RenderThread", 30),
            ThreadStat("Binder:1", 1),
        )

        val top = ProcStatParser.topGroups(threads)

        assertEquals(listOf("main", "DefaultDispatcher-worker", "RenderThread"), top.map { it.group })
        assertEquals(listOf(50L, 45L, 30L), top.map { it.ticks })
    }

    @Test
    fun parseAll_should_SumToTheSumOfValidLinesAndIgnoreMalformedOnes() = runTest {
        checkAll(Arb.list(Arb.long(0, 100_000), 0..20), Arb.list(Arb.long(0, 100_000), 0..20)) { utimes, stimes ->
            val valid = utimes.zip(stimes).mapIndexed { i, (u, s) -> statLine("t$i", u, s) }
            val noise = listOf("", "garbage", "9 (x) S 1")

            val parsed = ProcStatParser.parseAll(noise + valid + noise)

            assertEquals(valid.size, parsed.size)
            assertEquals(utimes.zip(stimes).sumOf { (u, s) -> u + s }, parsed.sumOf { it.ticks })
        }
    }

    @Test
    fun topGroups_should_NeverExceedLimitAndPreserveTotalOfTheTopGroups() = runTest {
        checkAll(Arb.list(Arb.int(0, 5), 0..30)) { indexes ->
            val threads = indexes.mapIndexed { i, g -> ThreadStat("group$g-${i}", (i + 1).toLong()) }

            val top = ProcStatParser.topGroups(threads, limit = 3)

            assertTrue(top.size <= 3)
            assertEquals(top.sortedByDescending { it.ticks }.map { it.ticks }, top.map { it.ticks })
        }
    }
}
