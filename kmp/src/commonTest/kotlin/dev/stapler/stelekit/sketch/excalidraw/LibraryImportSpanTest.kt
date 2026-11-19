// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.1.1g: library mode of the span scanner, one span per library item. */
class LibraryImportSpanTest {
    private val limits = SceneLimits.Default
    private val mb = 1_048_576

    private fun item(id: String, elements: List<String>, pad: Int = 0) =
        """{"id":"$id","status":"published","created":1700000000000,"name":"item $id","elements":[${elements.joinToString(",")}]${if (pad > 0) ""","pad":"${"p".repeat(pad)}"""" else ""}}"""

    private fun library(items: List<String>, type: String = "excalidrawlib") =
        """{"type":"$type","version":2,"source":"https://excalidraw.com","libraryItems":[${items.joinToString(",")}]}"""

    private suspend fun parse(text: String, decoder: SpanDecoder = SpanDecoder.Default, budget: ParseBudget = ParseBudget.start()) =
        ExcalidrawParser(decoder = decoder).parseLibrary(ImportSource.ofBytes(text.encodeToByteArray()), budget)

    @Test
    fun library_should_NeverDecodeMoreThanOneItem_When_NineMegabytesOfItemsArrive() = runTest {
        val items = (0 until ITEMS).map { item("i$it", listOf(element("rectangle", "r$it")), pad = ITEM_PAD) }
        val text = library(items)
        assertTrue(text.length > 8 * mb, "library is ${text.length} bytes")
        val decoder = CountingDecoder()
        val budget = ParseBudget({ 0L }, Long.MAX_VALUE)
        val result = (parse(text, decoder, budget) as Either.Right).value
        assertEquals(ITEMS, result.items.size)
        assertTrue(decoder.maxSize <= limits.maxImportSpanBytes, "largest decode call ${decoder.maxSize}")
        assertTrue(budget.yieldCount > 0)
        assertTrue(budget.maxBytesBetweenYields <= ParseBudget.YIELD_BYTES + limits.maxImportSpanBytes)
    }

    @Test
    fun library_should_DropAnOverCapItem_And_KeepTheOthers() = runTest {
        val items = listOf(item("a", listOf(element("rectangle", "r1"))), item("big", emptyList(), pad = 2 * mb + mb / 2), item("c", listOf(element("ellipse", "r2"))))
        val result = (parse(library(items)) as Either.Right).value
        assertEquals(listOf("a", "c"), result.items.map { it.id })
        assertEquals(ImportReason.ImportSpanTooLarge, result.report.issues.single().reason)
    }

    @Test
    fun library_should_CapTheItemCount() = runTest {
        val items = (0 until limits.maxLibraryItems + 5).map { item("i$it", listOf(element("rectangle", "r$it"))) }
        val result = (parse(library(items)) as Either.Right).value
        assertEquals(limits.maxLibraryItems, result.items.size)
        assertEquals(1, result.report.count(ImportReason.LibraryTooManyItems))
    }

    @Test
    fun library_should_RefuseWithTheCapNamed_When_StoredBytesExceedTheLibraryCap() = runTest {
        val items = (0 until NINE).map { item("i$it", emptyList(), pad = 2 * mb - TWO_KB) }
        val text = library(items)
        assertTrue(text.length <= limits.maxImportBytes && text.length > limits.maxLibraryBytes)
        val left = (parse(text) as Either.Left).value
        assertEquals(ImportReason.LibraryTooLarge, left.reason)
        assertEquals(limits.maxLibraryBytes, left.cap)
    }

    @Test
    fun library_should_TurnImageItemsIntoPlaceholders() = runTest {
        val img = element("image", "img1", extra = ""","fileId":"file1","status":"saved"""")
        val result = (parse(library(listOf(item("a", listOf(img, element("rectangle", "r1")))))) as Either.Right).value
        val kept = result.items.single().elements
        assertEquals(listOf("img1", "r1"), kept.map { it.id })
        assertTrue(kept.first().raw["fileId"] == null, "an image item keeps no file reference")
        assertEquals(1, result.report.count(ImportReason.LibraryImage))
    }

    @Test
    fun library_should_AcceptTheVersionOneLayout_And_RejectOtherTypes() = runTest {
        val v1 = """{"type":"excalidrawlib","version":1,"library":[[${element("rectangle", "r1")}],[${element("ellipse", "r2")}]]}"""
        val result = (parse(v1) as Either.Right).value
        assertEquals(2, result.items.size)
        assertEquals(listOf("r1"), result.items.first().elements.map { it.id })
        assertEquals(ImportReason.NotExcalidraw, (parse(library(emptyList(), type = "excalidraw")) as Either.Left).value.reason)
        assertEquals(ImportReason.MalformedJson, (parse("{\"type\":\"excalidrawlib\",\"libraryItems\":[") as Either.Left).value.reason)
        assertEquals(ImportReason.NotExcalidraw, (parse("""{"type":"excalidrawlib"}""") as Either.Left).value.reason)
    }

    @Test
    fun library_should_RefuseAFileOverTheImportCap_BeforeReadingIt() = runTest {
        val source = UnreadableSource(limits.maxImportBytes + 1)
        val left = (ExcalidrawParser().parseLibrary(source) as Either.Left).value
        assertEquals(ImportReason.FileTooLarge, left.reason)
        assertEquals(0, source.reads)
    }

    companion object {
        private const val ITEMS = 120
        private const val ITEM_PAD = 75_000
        private const val NINE = 9
        private const val TWO_KB = 2_048

        /** A small library for fuzzing the library entry point. */
        fun sampleLibrary(): String =
            """{"type":"excalidrawlib","version":2,"source":"x","libraryItems":[{"id":"a","status":"published","created":1,"elements":[${element("rectangle", "r1")},${element("text", "t1", extra = ""","text":"hi"""")}]},{"id":"b","status":"unpublished","created":2,"elements":[${element("image", "i1", extra = ""","fileId":"f"""")}]}]}"""
    }
}
