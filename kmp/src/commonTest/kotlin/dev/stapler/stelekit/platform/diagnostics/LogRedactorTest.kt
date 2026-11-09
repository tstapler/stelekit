package dev.stapler.stelekit.platform.diagnostics

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRedactorTest {
    private val exitLine = formatExitRecord(
        ExitRecord(9, 400, 0, 210_000, 300_000, 1_700_000_000_000, "excessive cpu 61000 during 300000 dur=300000 limit=25"),
    )
    private val sampleLine =
        "DiagnosticsSample cpuMsDelta=120 threads=main:50 foreground=false heapMb=40/512 coilCacheKb=900 " +
            "undoDepth=3 screen=AnnotationEditor tool=DISTANCE image=4000x3000"

    private val planted = listOf(
        "/home/alice/Pictures/IMG_0001.jpg",
        "content://media/external/images/media/42",
        "file:///storage/emulated/0/Notes/Kitchen%20Reno/pages/Secret.md",
        "C:\\Users\\alice\\Notes\\pages\\Secret.md",
    )

    @Test
    fun redactForShare_should_RemovePathsUrisGraphAndPageNames_When_PlantedValuesPresent() {
        val text = "failed reading ${planted.joinToString(" and ")} in graph Kitchen-Reno page Budget 2026 secret-plans ok"

        val out = LogRedactor.redactForShare(text, graphNames = listOf("Kitchen-Reno"), pageNames = listOf("Budget 2026", "secret-plans"))

        assertFalse(out.contains("alice"), out)
        assertFalse(out.contains("content://"), out)
        assertFalse(out.contains("file://"), out)
        assertFalse(out.contains("Kitchen-Reno"), out)
        assertFalse(out.contains("Budget 2026"), out)
        assertFalse(out.contains("secret-plans"), out)
        assertTrue(out.contains(LogRedactor.PATH) && out.contains(LogRedactor.GRAPH) && out.contains(LogRedactor.NAME), out)
    }

    @Test
    fun redactForShare_should_KeepClassNamesReasonNamesAndNumbers() {
        val text = "$exitLine\n$sampleLine"

        assertEquals(text, LogRedactor.redactForShare(text))
    }

    @Test
    fun redactForShare_should_BeIdempotent_When_AnyMixOfPlantedValues() = runTest {
        checkAll(Arb.list(Arb.element(planted + listOf("plain words", "Kitchen-Reno", "path", "name", exitLine)), 0..8)) { parts ->
            val text = parts.joinToString(" | ")
            val once = LogRedactor.redactForShare(text, listOf("Kitchen-Reno"), listOf("path", "name"))

            assertEquals(once, LogRedactor.redactForShare(once, listOf("Kitchen-Reno"), listOf("path", "name")))
            assertFalse(once.contains("alice"))
            assertFalse(once.contains("content://"))
            assertFalse(once.contains("Kitchen-Reno"))
        }
    }

    @Test
    fun redactForShare_should_IgnoreVeryShortNames() {
        assertEquals("a b ab", LogRedactor.redactForShare("a b ab", pageNames = listOf("a", "b", "ab")))
    }

    @Test
    fun redactForShare_should_NotMatchNamesInsideLongerWords() {
        assertEquals("mapping the app", LogRedactor.redactForShare("mapping the app", pageNames = listOf("map")))
    }

    @Test
    fun selectShareLines_should_TakeExitLinesAndAtMostThreeSamples() {
        val displayed = listOf(sampleLine + " n=1", sampleLine + " n=2", "unrelated", exitLine, sampleLine + " n=3", sampleLine + " n=4")

        val lines = LogRedactor.selectShareLines(displayed)

        assertEquals(listOf(exitLine, sampleLine + " n=1", sampleLine + " n=2", sampleLine + " n=3"), lines)
    }

    @Test
    fun selectShareLines_should_NeverIncludeAnythingNotDisplayed() = runTest {
        checkAll(Arb.list(Arb.element(listOf(exitLine, sampleLine, "random log line", "DiagnosticsSample stopped")), 0..12)) { displayed ->
            val lines = LogRedactor.selectShareLines(displayed)

            assertTrue(displayed.containsAll(lines))
            assertTrue(lines.count { it.contains("cpuMsDelta=") } <= LogRedactor.MAX_SHARED_SAMPLES)
        }
    }

    @Test
    fun buildShareText_should_BeNull_When_NoExitRecordIsDisplayed() {
        assertNull(LogRedactor.buildShareText(listOf(sampleLine, "other")))
    }

    @Test
    fun buildShareText_should_RedactExitBlock() {
        val text = LogRedactor.buildShareText(listOf("$exitLine /home/alice/x.jpg"))

        assertNotNull(text)
        assertFalse(text.contains("alice"))
        assertTrue(text.contains("EXCESSIVE_RESOURCE_USAGE"))
    }
}
