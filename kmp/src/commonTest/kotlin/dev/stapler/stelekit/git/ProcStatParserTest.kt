package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcStatParserTest {
    // Fields 3..21 after the comm: state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime priority nice threads itrealvalue
    private fun line(comm: String, ticks: Long) =
        "123 ($comm) S 1 123 123 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 $ticks 1000 50 18446744073709551615"

    @Test
    fun startTicks_should_ParseField22_When_PlainComm() {
        assertEquals(90761690L, ProcStatParser.startTicks(line("java", 90761690)))
    }

    @Test
    fun startTicks_should_ParseField22_When_CommHasSpacesAndParentheses() {
        assertEquals(777L, ProcStatParser.startTicks(line("my (weird) proc name", 777)))
        assertEquals(5L, ProcStatParser.startTicks(line(") S 9 9 (", 5)))
    }

    @Test
    fun startTicks_should_ReturnNull_When_LineTruncatedOrGarbage() {
        assertNull(ProcStatParser.startTicks(""))
        assertNull(ProcStatParser.startTicks("123 (java"))
        assertNull(ProcStatParser.startTicks("123 (java) S 1 2 3"))
        assertNull(ProcStatParser.startTicks(line("java", 1).replace(" 1 1000 50", " x 1000 50")))
    }
}
