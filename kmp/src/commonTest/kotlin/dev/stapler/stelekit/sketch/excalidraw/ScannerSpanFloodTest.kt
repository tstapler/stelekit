// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review fix C3/C11: the span scanner stops counting at a cap instead of allocating a range per tiny array entry. */
class ScannerSpanFloodTest {
    /** What the runner passes: the element limit widened tenfold to 50,000. */
    private val widened = SceneLimits.Default.copy(maxElements = SceneLimits.Default.maxElements * 10)

    private fun flood(entry: String, count: Int, prefix: String, suffix: String): ByteArray {
        val sb = StringBuilder(prefix.length + suffix.length + count * (entry.length + 1))
        sb.append(prefix)
        repeat(count) { if (it > 0) sb.append(','); sb.append(entry) }
        sb.append(suffix)
        return sb.toString().encodeToByteArray()
    }

    private suspend fun refusal(bytes: ByteArray, parser: ExcalidrawParser): ImportError {
        val result = parser.parse(ImportSource.ofBytes(bytes))
        return (result as Either.Left).value
    }

    @Test
    fun scan_should_RefuseWithTooManyElements_When_AnArrayHoldsMillionsOfEmptyObjects() = runTest {
        val bytes = flood("{}", FLOOD, """{"type":"excalidraw","elements":[""", "]}")
        assertEquals(ImportReason.TooManyElements, refusal(bytes, ExcalidrawParser(widened)).reason)
    }

    @Test
    fun scan_should_RefuseWithTooManyElements_When_AnArrayHoldsMillionsOfScalars() = runTest {
        val bytes = flood("1", FLOOD, """{"type":"excalidraw","elements":[""", "]}")
        assertEquals(ImportReason.TooManyElements, refusal(bytes, ExcalidrawParser(widened)).reason)
    }

    @Test
    fun scan_should_RefuseWithTooManyElements_When_FilesHoldsMoreEntriesThanTheCap() = runTest {
        val cap = widened.maxElements * SPAN_COUNT_FACTOR
        val sb = StringBuilder("""{"type":"excalidraw","elements":[],"files":{""")
        for (i in 0..cap) { if (i > 0) sb.append(','); sb.append("\"f").append(i).append("\":{}") }
        sb.append("}}")
        assertEquals(ImportReason.TooManyElements, refusal(sb.toString().encodeToByteArray(), ExcalidrawParser(widened)).reason)
    }

    @Test
    fun scan_should_RefuseWithTooManyElements_When_OneFilesEntryHasThousandsOfMembers() = runTest {
        val sb = StringBuilder("""{"type":"excalidraw","elements":[],"files":{"f":{""")
        for (i in 0 until MEMBERS) { if (i > 0) sb.append(','); sb.append("\"m").append(i).append("\":1") }
        sb.append("}}}")
        assertEquals(ImportReason.TooManyElements, refusal(sb.toString().encodeToByteArray(), ExcalidrawParser(widened)).reason)
    }

    @Test
    fun parse_should_StillAcceptAFileOfDeletedElements_When_ItIsBelowTheSpanCap() = runTest {
        val kept = element("rectangle", "keep")
        val deleted = (0 until DELETED).map { element("rectangle", "d$it", deleted = true) }
        val result = ExcalidrawParser().parse(ImportSource.ofBytes(scene(deleted + kept).encodeToByteArray()))
        assertEquals(1, (result as Either.Right).value.scene.elements.size)
    }

    @Test
    fun parse_should_BoundTheUnknownTypeText_When_ElementTypesAreHugeAndDistinct() = runTest {
        val hugeType = "x".repeat(HUGE_TYPE)
        val elements = (0 until DISTINCT_TYPES).map { element(hugeType + it, "u$it") }
        val result = ExcalidrawParser().parse(ImportSource.ofBytes(scene(elements).encodeToByteArray()))
        val unknown = (result as Either.Right).value.report.unknownTypes
        assertTrue(unknown.size <= SceneLimits.Default.maxDiagnosticsEntries + 1, "${unknown.size} distinct unknown types")
        assertTrue(unknown.keys.all { it.length <= SceneLimits.Default.maxNameChars }, "an unknown type name was not shortened")
        assertEquals(DISTINCT_TYPES, unknown.values.sum())
    }

    private companion object {
        const val FLOOD = 6_000_000
        const val MEMBERS = 5_000
        const val DELETED = 3_000
        const val DISTINCT_TYPES = 200
        const val HUGE_TYPE = 5_000
        const val SPAN_COUNT_FACTOR = 4
    }
}
