package dev.stapler.stelekit.platform.diagnostics

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExitRecordFormatterTest {
    private val excessiveCpu = ExitRecord(
        reason = 9,
        importance = 400,
        status = 0,
        pssKb = 210_000,
        rssKb = 300_000,
        timestampMs = 1_700_000_000_000,
        description = "excessive cpu 61000 during 300000 dur=300000 limit=25",
    )

    @Test
    fun formatExitRecord_should_NameReasonImportanceStatusPssAndKeepDescriptionVerbatim() {
        val line = formatExitRecord(excessiveCpu)

        assertTrue(line.contains("EXCESSIVE_RESOURCE_USAGE"), line)
        assertTrue(line.contains("importance=CACHED"), line)
        assertTrue(line.contains("status=0"), line)
        assertTrue(line.contains("pss=205MB"), line)
        assertTrue(line.endsWith("excessive cpu 61000 during 300000 dur=300000 limit=25"), line)
        assertTrue(line.startsWith(EXIT_LINE_PREFIX), line)
    }

    @Test
    fun formatExitRecord_should_OmitDescription_When_NullOrEmpty() {
        assertTrue(formatExitRecord(excessiveCpu.copy(description = null)).endsWith("rss=292MB"))
        assertTrue(formatExitRecord(excessiveCpu.copy(description = "")).endsWith("rss=292MB"))
    }

    @Test
    fun recordId_should_BeStableAndDistinguishRecords() {
        assertEquals(excessiveCpu.id, excessiveCpu.copy(description = "other").id)
        assertTrue(excessiveCpu.id != excessiveCpu.copy(timestampMs = 1).id)
    }

    @Test
    fun names_should_CoverKnownConstants() {
        assertEquals("LOW_MEMORY", exitReasonName(3))
        assertEquals("CRASH_NATIVE", exitReasonName(5))
        assertEquals("ANR", exitReasonName(6))
        assertEquals("FOREGROUND", processImportanceName(100))
        assertEquals("CACHED", processImportanceName(400))
    }

    @Test
    fun names_should_NeverBeBlankAndKeepUnknownNumbers() = runTest {
        checkAll(Arb.int(-5, 2_000)) { n ->
            assertTrue(exitReasonName(n).isNotBlank())
            assertTrue(processImportanceName(n).isNotBlank())
            if (n !in 1..16) assertEquals("REASON_$n", exitReasonName(n))
            if (n !in listOf(100, 125, 130, 200, 230, 300, 325, 400, 1000)) {
                assertEquals("IMPORTANCE_$n", processImportanceName(n))
            }
        }
    }

    @Test
    fun formatExitRecord_should_AlwaysContainEveryFieldName_When_AnyInts() = runTest {
        checkAll(Arb.int(-5, 2_000), Arb.int(-5, 2_000), Arb.int(-300, 300)) { reason, importance, status ->
            val line = formatExitRecord(excessiveCpu.copy(reason = reason, importance = importance, status = status))

            assertTrue(line.contains(exitReasonName(reason)))
            assertTrue(line.contains("importance=${processImportanceName(importance)}"))
            assertTrue(line.contains("status=$status"))
        }
    }
}
