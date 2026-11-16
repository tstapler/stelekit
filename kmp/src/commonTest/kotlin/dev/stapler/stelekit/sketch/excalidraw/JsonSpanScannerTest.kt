// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A decoder that records the size of every text it is handed. */
class CountingDecoder : SpanDecoder {
    val sizes = ArrayList<Int>()
    val maxSize: Int get() = sizes.maxOrNull() ?: 0

    override fun decode(text: String): JsonElement {
        sizes += text.length
        return Json.parseToJsonElement(text)
    }
}

/** Source that refuses to be read; proves a refusal happens before any byte is scanned. */
class UnreadableSource(override val size: Long) : ImportSource {
    var reads = 0
    override fun read(offset: Long, length: Int): ByteArray {
        reads++
        error("must not be read")
    }
}

/**
 * Task 5.1.1e: the byte-level span scanner against the whole-file oracle, over the nine equivalence classes of the
 * plan (Story 5.1.1, E3), plus the structural bound on every decode call.
 */
class JsonSpanScannerTest {
    private val limits = SceneLimits.Default
    private val mb = 1_048_576

    private fun bytes(text: String) = text.encodeToByteArray()

    private suspend fun scanned(text: String, parser: ExcalidrawParser = ExcalidrawParser()) = parser.parse(ImportSource.ofBytes(bytes(text)))

    private suspend fun oracle(text: String) = ExcalidrawParser().parseWholeFile(bytes(text))

    /** Class 1 and 2 together: both paths accept with EQUAL results, or both reject with the same reason. */
    private suspend fun assertEquivalent(text: String, label: String = "") {
        val a = scanned(text)
        val b = oracle(text)
        val same = when {
            a is Either.Right && b is Either.Right -> a.value == b.value
            a is Either.Left && b is Either.Left -> a.value.reason == b.value.reason
            else -> false
        }
        assertTrue(same, "scanner and oracle disagree $label\n input: ${text.take(160)}\n scanner: $a\n oracle: $b")
    }

    // ---- class 1: well-formed, in-limit

    @Test
    fun scanner_should_EqualOracle_When_InputIsWellFormed() = runTest {
        assertEquivalent(ExcalidrawTestData.mixedScene())
        val png = ExcalidrawTestData.pngBytes(40, 30)
        assertEquivalent(scene(listOf(element("image", "i1", extra = ""","fileId":"f1","status":"saved","scale":[1,1]""")), ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("f1", "image/png", png))))
        assertEquivalent("  \n" + ExcalidrawTestData.mixedScene() + "\n  ")
    }

    @Test
    fun scanner_should_KeepEveryKindAndCountUnsupportedAndUnknown() = runTest {
        val result = (scanned(ExcalidrawTestData.mixedScene()) as Either.Right).value
        assertEquals(9, result.scene.elements.size)
        assertEquals(mapOf("embeddable" to 1), result.report.unsupported)
        assertEquals(mapOf("stickynote" to 1), result.report.unknownTypes)
        assertEquals(1, result.report.deletedCount)
        assertEquals(1, result.report.unsupportedCount)
    }

    @Test
    fun filesMemberOrder_should_NotMatter_When_DataUrlComesFirstOrLast() = runTest {
        val url = ExcalidrawTestData.dataUrl("image/png", ExcalidrawTestData.pngBytes(8, 8))
        val first = """{"f1":{"dataURL":"$url","mimeType":"image/png","id":"f1","created":5}}"""
        val last = """{"f1":{"mimeType":"image/png","id":"f1","created":5,"dataURL":"$url"}}"""
        assertEquivalent(scene(emptyList(), first))
        assertEquivalent(scene(emptyList(), last))
        val a = (scanned(scene(emptyList(), first)) as Either.Right).value
        val b = (scanned(scene(emptyList(), last)) as Either.Right).value
        assertEquals(a.scene.files, b.scene.files)
        assertEquals(1, a.scene.files.size)
    }

    // ---- class 2: malformed input is rejected by both, at every position of a small fixture

    private val smallFixture = scene(
        listOf(element("rectangle", "r1", extra = ""","boundElements":[{"id":"a1","type":"arrow"}]"""), element("text", "t1", extra = ""","text":"hi\n""""), element("arrow", "a1", extra = ""","points":[[0,0],[5,5]]""")),
        ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("f1", "image/png", ExcalidrawTestData.pngBytes(4, 4))),
    )

    @Test
    fun scanner_should_RejectLikeOracle_When_TruncatedAtEveryOffset() = runTest {
        val all = bytes(smallFixture)
        for (n in 0 until all.size) {
            val cut = all.copyOf(n).decodeToString()
            assertEquivalent(cut, "truncated at $n")
        }
    }

    @Test
    fun scanner_should_RejectLikeOracle_When_OneBracketIsAddedOrMissing() = runTest {
        for (i in smallFixture.indices) {
            val c = smallFixture[i]
            if (c in "{}[]") assertEquivalent(smallFixture.removeRange(i, i + 1), "removed '$c' at $i")
            if (i % STRIDE == 0) for (extra in "{}[]") assertEquivalent(smallFixture.substring(0, i) + extra + smallFixture.substring(i), "inserted '$extra' at $i")
        }
    }

    @Test
    fun scanner_should_RejectLikeOracle_When_TextIsMutatedInTheNamedWays() = runTest {
        val mutations = mutableListOf<Pair<String, String>>()
        mutations += "trailing garbage" to smallFixture + "x"
        mutations += "trailing second object" to "$smallFixture{}"
        mutations += "bad escape" to smallFixture.replace("\"r1\"", "\"\\q\"")
        mutations += "short unicode escape" to smallFixture.replace("\"r1\"", "\"\\u12\"")
        mutations += "unquoted key" to smallFixture.replace("\"version\":", "version:")
        mutations += "single quotes" to smallFixture.replace("\"excalidraw\"", "'excalidraw'")
        mutations += "bare NaN" to smallFixture.replace("\"angle\":0", "\"angle\":NaN")
        mutations += "bare Infinity" to smallFixture.replace("\"angle\":0", "\"angle\":Infinity")
        mutations += "control char in string" to smallFixture.replace("\"hi\\n\"", "\"h\u0001i\"")
        mutations += "control char in key" to smallFixture.replace("\"version\"", "\"ver\u0001sion\"")
        mutations += "control char in dataURL" to smallFixture.replace("data:image/png;base64,", "data:image/png;base64,\u0001")
        mutations += "BOM in the middle" to smallFixture.replace("\"elements\"", "\"\uFEFFelements\"").replaceFirst("{", "{\uFEFF")
        mutations += "empty input" to ""
        mutations += "whitespace only" to "  \n"
        mutations += "root is an array" to "[]"
        mutations += "root is a number" to "123"
        mutations += "root is a string" to "\"x\""
        mutations += "missing colon" to smallFixture.replace("\"version\":2", "\"version\" 2")
        mutations += "missing comma at top level" to smallFixture.replace("\"version\":2,", "\"version\":2")
        mutations += "missing comma between elements" to smallFixture.replace("},{\"type\":\"text\"", "}{\"type\":\"text\"")
        for (i in smallFixture.indices) {
            if (smallFixture[i] == ']' || smallFixture[i] == '}') mutations += "trailing comma before position $i" to smallFixture.substring(0, i) + "," + smallFixture.substring(i)
        }
        for ((name, text) in mutations) assertEquivalent(text, name)
    }

    @Test
    fun scanner_should_AcceptBom_WhenItIsTheFirstBytes() = runTest {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(smallFixture)
        val a = ExcalidrawParser().parse(ImportSource.ofBytes(withBom))
        val b = ExcalidrawParser().parseWholeFile(withBom)
        assertTrue(a is Either.Right && b is Either.Right && a.value == b.value)
    }

    // ---- classes 3 to 6

    @Test
    fun scanner_should_LetTheLastDuplicateKeyWin_AtTopLevelAndInFiles() = runTest {
        val a = element("rectangle", "from-first")
        val b = element("rectangle", "from-last")
        val text = """{"type":"excalidraw","elements":[$a],"elements":[$b],"files":{},"files":{}}"""
        assertEquivalent(text)
        assertEquals(listOf("from-last"), (scanned(text) as Either.Right).value.scene.elements.map { it.id })

        val png = ExcalidrawTestData.pngBytes(4, 4)
        val first = ExcalidrawTestData.pngBytes(9, 9)
        val dup = """{"type":"excalidraw","elements":[],"files":{${ExcalidrawTestData.fileEntry("f1", "image/png", first)},${ExcalidrawTestData.fileEntry("f1", "image/png", png)}}}"""
        assertEquivalent(dup)
        assertEquals(4, (scanned(dup) as Either.Right).value.scene.files.single().widthPx)
    }

    @Test
    fun scanner_should_UnescapeKeysBeforeComparing() = runTest {
        val text = """{"type":"excalidraw","\u0065lements":[${element("rectangle", "r1")}],"fil\u0065s":{}}"""
        assertEquivalent(text)
        assertEquals(1, (scanned(text) as Either.Right).value.scene.elements.size)
    }

    @Test
    fun scanner_should_IgnoreWrongTypedElementsAndFiles_WithReasonCodes() = runTest {
        for (value in listOf("{}", "null", "7", "\"x\"", "true")) {
            val text = """{"type":"excalidraw","elements":$value,"files":[]}"""
            assertEquivalent(text, value)
            val r = (scanned(text) as Either.Right).value
            assertTrue(r.scene.elements.isEmpty())
            assertEquals(value != "null", r.report.count(ImportReason.ElementsNotAnArray) == 1, "elements=$value")
            assertEquals(1, r.report.count(ImportReason.FilesNotAnObject))
        }
    }

    // ---- class 8: over-limit input drops the span and keeps the rest

    @Test
    fun scanner_should_DropAnOversizeElementAndKeepTheOthers() = runTest {
        val big = element("text", "big", extra = ""","text":"${"x".repeat(2 * mb + mb / 2)}"""")
        val text = scene(listOf(element("rectangle", "a"), big, element("rectangle", "c")))
        val decoder = CountingDecoder()
        val result = (scanned(text, ExcalidrawParser(decoder = decoder)) as Either.Right).value
        assertEquals(listOf("a", "c"), result.scene.elements.map { it.id })
        assertEquals(ImportReason.ImportSpanTooLarge, result.report.issues.single().reason)
        assertTrue(decoder.maxSize <= limits.maxImportSpanBytes, "largest decode call ${decoder.maxSize}")
    }

    @Test
    fun scanner_should_DropAnOverDeepElementWithoutDecodingIt() = runTest {
        val depth = mb / 2 + mb / 4
        val deep = "[".repeat(depth) + "]".repeat(depth)
        val text = """{"type":"excalidraw","elements":[${element("rectangle", "a")},$deep,${element("rectangle", "c")}]}"""
        val decoder = CountingDecoder()
        val result = (scanned(text, ExcalidrawParser(decoder = decoder)) as Either.Right).value
        assertEquals(listOf("a", "c"), result.scene.elements.map { it.id })
        assertEquals(ImportReason.ImportTooDeep, result.report.issues.single().reason)
        assertTrue(decoder.maxSize < depth, "the over-deep span reached the decoder")
        assertEquals(3, decoder.sizes.size, "type member plus the two intact elements")
    }

    @Test
    fun scanner_should_RefuseTheFile_When_ATopLevelSpanIsOverTheCap() = runTest {
        val text = """{"type":"excalidraw","elements":[],"appState":{"x":"${"y".repeat(3 * mb)}"}}"""
        val left = (scanned(text) as Either.Left).value
        assertEquals(ImportReason.ImportSpanTooLarge, left.reason)
        assertEquals(limits.maxImportSpanBytes, left.cap)
    }

    @Test
    fun scanner_should_RefuseTheFile_When_ATopLevelSpanIsOverDeep() = runTest {
        val deep = "[".repeat(limits.maxJsonDepth + 1) + "]".repeat(limits.maxJsonDepth + 1)
        val left = (scanned("""{"type":"excalidraw","elements":[],"appState":$deep}""") as Either.Left).value
        assertEquals(ImportReason.ImportTooDeep, left.reason)
    }

    @Test
    fun scanner_should_RefuseBeforeReading_When_TheSourceIsOverTheImportCap() = runTest {
        val source = UnreadableSource(limits.maxImportBytes + 1)
        val decoder = CountingDecoder()
        val left = (ExcalidrawParser(decoder = decoder).parse(source) as Either.Left).value
        assertEquals(ImportReason.FileTooLarge, left.reason)
        assertEquals(limits.maxImportBytes, left.cap)
        assertEquals(0, source.reads)
        assertEquals(0, decoder.sizes.size)
    }

    // ---- class 9: an escape in a dataURL other than \/

    @Test
    fun scanner_should_MakeAPlaceholder_When_DataUrlHasAnEscapeOtherThanSlash() = runTest {
        val good = ExcalidrawTestData.fileEntry("good", "image/png", ExcalidrawTestData.pngBytes(4, 4))
        val escaped = ExcalidrawTestData.fileEntry("esc", "image/png", ExcalidrawTestData.pngBytes(4, 4) + byteArrayOf(1, 2)).replace("=", "\\u003d")
        val text = scene(emptyList(), "{$good,$escaped}")
        val result = (scanned(text) as Either.Right).value
        assertEquals(listOf("good"), result.scene.files.map { it.id })
        assertEquals(setOf("esc"), result.scene.placeholderFileIds)
        assertEquals(ImportReason.DataUrlEscaped, result.report.issues.single().reason)
    }

    @Test
    fun scanner_should_UnescapeSlashInADataUrl_LikeTheOracle() = runTest {
        val bytes = ExcalidrawTestData.pngBytes(4, 4) + ByteArray(40) { 0xFF.toByte() }
        val entry = ExcalidrawTestData.fileEntry("f1", "image/png", bytes).replace("/", "\\/")
        assertTrue(entry.contains("\\/"))
        assertEquivalent(scene(emptyList(), "{$entry}"))
    }

    // ---- the structural proof for a 20 MB file

    @Test
    fun scanner_should_NeverDecodeMoreThanOneSpan_When_TheFileIsTwentyMegabytes() = runTest {
        val filler = "z".repeat(700)
        val elements = (0 until ELEMENT_COUNT).map { element("rectangle", "e$it", extra = ""","customData":{"pad":"$filler"}""") }
        val image = ExcalidrawTestData.pngBytes(100, 100, IMAGE_BYTES)
        val text = scene(elements, ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("big", "image/png", image)))
        val all = bytes(text)
        assertTrue(all.size > 18 * mb && all.size <= limits.maxImportBytes, "synthetic file is ${all.size} bytes")

        val decoder = CountingDecoder()
        val slices = ArrayList<Int>()
        val budget = ParseBudget({ 0L }, Long.MAX_VALUE)
        val result = ExcalidrawParser(decoder = decoder, onBase64Slice = { slices += it }).parse(ImportSource.ofBytes(all), budget)

        assertTrue(result is Either.Right, "result $result")
        assertTrue(decoder.maxSize <= limits.maxImportSpanBytes, "largest decode call ${decoder.maxSize}")
        assertTrue(slices.isNotEmpty() && slices.all { it <= ParseBudget.BASE64_SLICE_CHARS }, "base64 slices ${slices.maxOrNull()}")
        assertTrue(slices.size > 100, "a 15 MB payload is many slices, got ${slices.size}")
        assertTrue(budget.maxBytesBetweenYields <= ParseBudget.YIELD_BYTES + limits.maxImportSpanBytes, "gap ${budget.maxBytesBetweenYields}")
        assertTrue(budget.yieldCount > 0)
        assertEquals(ELEMENT_COUNT, (result as Either.Right).value.scene.elements.size)
        assertEquals(image.size, result.value.scene.files.single().bytes.size)

        val reference = ExcalidrawParser().parseWholeFile(all, budget)
        assertTrue(reference is Either.Right && reference.value == result.value, "scanner result differs from the whole-file oracle")
    }

    private companion object {
        const val STRIDE = 7
        const val ELEMENT_COUNT = 4_000
        const val IMAGE_BYTES = 11_250_000
    }
}
