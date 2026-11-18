// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Task 5.1.1c: fuzz the importer, clipboard and library entry points; nothing throws, every bound holds. */
class ParserFuzzTest {
    private val limits = SceneLimits.Default
    private val config = PropTestConfig(iterations = ITERATIONS)

    private suspend fun import(text: String) = ExcalidrawParser().parse(ImportSource.ofBytes(text.encodeToByteArray()))

    private fun assertSane(result: Either<ImportError, ImportResult>) {
        val r = (result as? Either.Right)?.value ?: return
        val ids = r.scene.elements.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids survived")
        assertTrue(r.scene.elements.size <= limits.maxElements)
        for (e in r.scene.elements) {
            for (v in listOf(e.x, e.y, e.width, e.height)) assertTrue(v.isFinite() && kotlin.math.abs(v) <= limits.maxCoord, "bad coordinate $v in ${e.id}")
            assertTrue(e.angle.isFinite())
            assertTrue(e.frameId == null || e.frameId != e.id, "self frame on ${e.id}")
        }
        for (f in r.scene.files) assertTrue(f.widthPx <= limits.maxImageSidePx && f.heightPx <= limits.maxImageSidePx)
    }

    // ---- Story 5.1.1 acceptance criteria, one example each

    @Test
    fun import_should_RefuseAFileOverTheCap_BeforeParsing() = runTest {
        val source = UnreadableSource(limits.maxImportBytes + 1)
        val counting = CountingDecoder()
        val left = (ExcalidrawParser(decoder = counting).parse(source) as Either.Left).value
        assertEquals(ImportReason.FileTooLarge, left.reason)
        assertEquals(0, counting.sizes.size)
    }

    @Test
    fun import_should_ReturnTooManyElements_When_TenThousandElementsArrive() = runTest {
        val text = scene((0 until TEN_THOUSAND).map { element("rectangle", "e$it") })
        val left = (import(text) as Either.Left).value
        assertEquals(ImportReason.TooManyElements, left.reason)
        assertEquals(limits.maxElements.toLong(), left.cap)
    }

    @Test
    fun import_should_NotCrash_When_NestingIsTwoHundredDeep() = runTest {
        val deep = "[".repeat(DEEP) + "]".repeat(DEEP)
        val r = (import("""{"type":"excalidraw","elements":[${element("rectangle", "ok")},$deep]}""") as Either.Right).value
        assertEquals(listOf("ok"), r.scene.elements.map { it.id })
        assertEquals(1, r.report.count(ImportReason.ImportTooDeep))
    }

    @Test
    fun import_should_DropAnElement_When_ACoordinateIsANaNString() = runTest {
        val bad = element("rectangle", "bad").replace("\"x\":0", "\"x\":\"NaN\"")
        val r = (import(scene(listOf(bad, element("rectangle", "ok")))) as Either.Right).value
        assertEquals(listOf("ok"), r.scene.elements.map { it.id })
        assertEquals(ImportReason.NonFiniteCoordinate, r.report.issues.single().reason)
    }

    @Test
    fun import_should_RenameDuplicateIds_And_ClearCyclicFrames() = runTest {
        val a = element("rectangle", "dup", x = 1)
        val b = element("rectangle", "dup", x = 2)
        val cyc = element("frame", "f1", extra = ""","frameId":"f1"""").replace("\"frameId\":null,", "")
        val r = (import(scene(listOf(a, b, cyc))) as Either.Right).value
        assertEquals(listOf("dup", "dup-2", "f1"), r.scene.elements.map { it.id })
        assertEquals(1, r.report.count(ImportReason.DuplicateId))
        assertEquals(1, r.report.count(ImportReason.CyclicFrame))
        assertEquals(null, r.scene.elements.last().frameId)
        assertEquals(2.0, r.scene.elements[1].x)
    }

    @Test
    fun import_should_ClearAFrameCycleOfLengthThree() = runTest {
        val loop = listOf("a" to "b", "b" to "c", "c" to "a", "d" to "a").map { (id, frame) ->
            element("rectangle", id).replace("\"frameId\":null", "\"frameId\":\"$frame\"")
        }
        val r = (import(scene(loop)) as Either.Right).value
        assertEquals(3, r.report.count(ImportReason.CyclicFrame))
        assertEquals("a", r.scene.elements.single { it.id == "d" }.frameId, "a member that merely points into the cycle keeps its frame")
    }

    @Test
    fun import_should_KeepEmbeddableIframeAndMagicframeAsPlaceholdersWithACount() = runTest {
        val r = (import(scene(listOf(element("embeddable", "e1"), element("iframe", "i1"), element("magicframe", "m1"), element("iframe", "i2")))) as Either.Right).value
        assertEquals(4, r.scene.elements.size)
        assertTrue(r.scene.elements.all { it.unsupported })
        assertEquals(mapOf("embeddable" to 1, "iframe" to 2, "magicframe" to 1), r.report.unsupported)
        assertEquals(4, r.report.unsupportedCount)
    }

    @Test
    fun import_should_KeepLinksAsInertText() = runTest {
        val r = (import(scene(listOf(element("embeddable", "e1", extra = ""","link":"https://evil.example/x""""), element("rectangle", "r1", extra = ""","link":"javascript:alert(1)"""")))) as Either.Right).value
        assertEquals(listOf("https://evil.example/x", "javascript:alert(1)"), r.scene.elements.map { it.link })
    }

    @Test
    fun import_should_TruncateOverlongText_And_RejectTooManyPoints() = runTest {
        val long = element("text", "t1", extra = ""","text":"${"a".repeat(limits.maxTextChars + 5)}"""")
        val many = element("line", "l1", extra = ""","points":[${(0..limits.maxPointsPerElement).joinToString(",") { "[$it,0]" }}]""")
        val r = (import(scene(listOf(long, many))) as Either.Right).value
        assertEquals(listOf("t1"), r.scene.elements.map { it.id })
        assertEquals(limits.maxTextChars, (r.scene.elements.single().raw["text"] as kotlinx.serialization.json.JsonPrimitive).content.length)
        assertEquals(1, r.report.count(ImportReason.TextTruncated))
        assertEquals(1, r.report.count(ImportReason.TooManyPoints))
    }

    // ---- Throwable guard

    @Test
    fun parse_should_ReturnLeft_When_TheDecoderThrowsAnUnexpectedError() = runTest {
        val boom = SpanDecoder { throw IllegalStateException("boom") }
        val left = (ExcalidrawParser(decoder = boom).parse(ImportSource.ofBytes(ExcalidrawTestData.mixedScene().encodeToByteArray())) as Either.Left).value
        assertEquals(ImportReason.Unexpected, left.reason)
    }

    @Test
    fun parse_should_RethrowCancellation() = runTest {
        val cancel = SpanDecoder { throw kotlinx.coroutines.CancellationException("cancelled") }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            ExcalidrawParser(decoder = cancel).parse(ImportSource.ofBytes(ExcalidrawTestData.mixedScene().encodeToByteArray()))
        }
    }

    // ---- fuzz

    private val seed = ExcalidrawTestData.mixedScene() + ""

    private fun mutate(text: String, ops: List<Int>): String {
        var s = text
        val junk = listOf("{", "}", "[", "]", ",", ":", "\"", "\\", "\u0000", "NaN", "1e999", "-0", "null", "true", "﻿")
        for (op in ops) {
            if (s.isEmpty()) break
            val at = (op.toLong() and Int.MAX_VALUE.toLong()).toInt() % s.length
            s = when (op % MUTATION_KINDS) {
                0 -> s.removeRange(at, minOf(s.length, at + 1 + at % 7))
                1 -> s.substring(0, at) + junk[(op / MUTATION_KINDS).mod(junk.size)] + s.substring(at)
                2 -> s.substring(0, at)
                3 -> s.substring(0, at) + s.substring(at, minOf(s.length, at + 40)) + s.substring(at)
                else -> s.substring(0, at) + (op % 97).toChar() + s.substring(minOf(s.length, at + 1))
            }
        }
        return s
    }

    @Test
    fun import_should_NeverThrow_When_SceneBytesAreMutated() = runTest {
        checkAll(config, Arb.list(Arb.int(), 1..MAX_OPS)) { ops -> assertSane(import(mutate(seed, ops))) }
    }

    @Test
    fun import_should_NeverThrow_When_NumbersAreGarbage() = runTest {
        val values = listOf("\"NaN\"", "NaN", "1e999", "-1e999", "1e30", "1e-400", "0x10", "null", "[]", "{}", "true", "\"12\"", "-0", "1.5e3", "268435456", "268435457")
        checkAll(config, Arb.element(values), Arb.element(listOf("x", "y", "width", "height", "angle", "strokeWidth", "opacity"))) { value, field ->
            val raw = element("rectangle", "r1").replace(Regex("\"$field\":[^,}]+"), "\"$field\":$value")
            assertSane(import(scene(listOf(raw, element("rectangle", "r2")))))
        }
    }

    @Test
    fun import_should_NeverThrow_When_PointListsAreGarbage() = runTest {
        val points = listOf("[[0,0],[1,1]]", "[[0]]", "[[\"a\",1]]", "[1,2]", "[[1e999,0]]", "[[0,0],[null,1]]", "{}", "[[[0,0]]]", "[]")
        checkAll(config, Arb.element(points), Arb.element(listOf("line", "arrow", "freedraw"))) { pts, type ->
            assertSane(import(scene(listOf(element(type, "p1", extra = ""","points":$pts""")))))
        }
    }

    @Test
    fun clipboard_should_RefuseNonJsonText_And_NeverThrow() = runTest {
        checkAll(config, Arb.string(0..MAX_TEXT)) { text ->
            val source = ImportSource.ofText(text)
            val trimmed = text.removePrefix("﻿").trimStart(' ', '\t', '\r', '\n')
            if (!trimmed.startsWith("{")) {
                assertEquals(ImportReason.NotExcalidraw, (source as Either.Left).value.reason)
            } else {
                assertSane(ExcalidrawParser().parse((source as Either.Right).value))
            }
        }
    }

    @Test
    fun clipboard_should_RefuseAnOverlongStringBeforeEncodingIt() = runTest {
        val tiny = SceneLimits(maxImportBytes = SMALL_IMPORT_CAP)
        val text = "{" + " ".repeat(SMALL_IMPORT_CAP.toInt() + 1) + "}"
        val left = (ImportSource.ofText(text, tiny) as Either.Left).value
        assertEquals(ImportReason.FileTooLarge, left.reason)
        // Three-byte characters pass the length check but fail the encoded-size check.
        val wide = "{" + "€".repeat(SMALL_IMPORT_CAP.toInt() / 2) + "}"
        assertTrue(wide.length <= SMALL_IMPORT_CAP)
        assertEquals(ImportReason.FileTooLarge, (ImportSource.ofText(wide, tiny) as Either.Left).value.reason)
    }

    @Test
    fun library_should_NeverThrow_When_LibraryBytesAreMutated() = runTest {
        val library = LibraryImportSpanTest.sampleLibrary()
        checkAll(config, Arb.list(Arb.int(), 1..MAX_OPS)) { ops ->
            val result = ExcalidrawParser().parseLibrary(ImportSource.ofBytes(mutate(library, ops).encodeToByteArray()))
            assertTrue(result is Either.Right || result is Either.Left)
        }
    }

    @Test
    fun scanner_should_AgreeWithTheOracle_When_BytesAreMutated() = runTest {
        checkAll(config, Arb.list(Arb.int(), 1..MAX_OPS)) { ops ->
            val text = mutate(seed, ops)
            val a = import(text)
            val b = ExcalidrawParser().parseWholeFile(text.encodeToByteArray())
            // The scanner may add the span-limit drops the oracle lacks, but mutations of a 2 KB file never reach them.
            when {
                a is Either.Right && b is Either.Right -> assertEquals(b.value, a.value, "input: $text")
                a is Either.Left && b is Either.Left -> assertEquals(b.value.reason, a.value.reason, "input: $text")
                // kotlinx accepts some inputs RFC 8259 forbids (see KOTLINX_MISSING_COMMA); refusing those is correct.
                a is Either.Left && b is Either.Right && a.value.reason == ImportReason.MalformedJson && !StrictJson.isValid(text) -> Unit
                else -> error("scanner $a, oracle $b, input: $text")
            }
        }
    }

    @Test
    fun scanner_should_RefuseAMissingComma_That_KotlinxAccepts() = runTest {
        // kotlinx reads `[[0]2],3]` as `[[0,2],3]`; RFC 8259 needs a comma after the inner array, so this is malformed.
        assertTrue(!StrictJson.isValid(KOTLINX_MISSING_COMMA))
        assertEquals(ImportReason.MalformedJson, (import(KOTLINX_MISSING_COMMA) as Either.Left).value.reason)
        // Pins the divergence: once kotlinx rejects it too, the exemption in scanner_should_AgreeWithTheOracle can go.
        assertTrue(ExcalidrawParser().parseWholeFile(KOTLINX_MISSING_COMMA.encodeToByteArray()) is Either.Right)
    }

    @Test
    fun scanner_should_RefuseTheMinimisedFuzzFailure() = runTest {
        // Shrunk from seed 2256917155336893162: a stray `2` after an inner points array inside an element.
        val text = scene(listOf(element("line", "l1", extra = ""","points":[[0,0]2],[3,4]]""")))
        assertEquals(ImportReason.MalformedJson, (import(text) as Either.Left).value.reason)
        assertTrue(!StrictJson.isValid(text))
    }

    @Test
    fun scanner_should_RefuseMalformedJson_That_ALaterDuplicateKeyReplaces() = runTest {
        // Shrunk from fuzz seed 8: `"elements":ce":"..."` is malformed, but a later `"elements"` key replaced it.
        val good = element("rectangle", "r1")
        val cases = listOf(
            """{"elements":ce":"x","elements":[$good]}""",
            """{"a":{"b":},"a":1,"elements":[$good]}""",
            """{"elements":[{"type":}],"elements":[$good]}""",
            """{"files":{"f":{"id":}},"files":{},"elements":[$good]}""",
            """{"files":{"f":{"id":"a"},"f":{"id":}},"elements":[$good]}""",
        )
        for (text in cases) {
            val scanner = import(text)
            val oracle = ExcalidrawParser().parseWholeFile(text.encodeToByteArray())
            assertEquals(ImportReason.MalformedJson, (scanner as? Either.Left)?.value?.reason, "scanner $scanner on $text")
            assertEquals(ImportReason.MalformedJson, (oracle as? Either.Left)?.value?.reason, "oracle $oracle on $text")
        }
        val valid = """{"a":1,"a":2,"elements":[],"elements":[$good]}"""
        assertEquals(listOf("r1"), (import(valid) as Either.Right).value.scene.elements.map { it.id })
    }

    @Test
    fun strictJson_should_FollowRfc8259() {
        for (ok in listOf("""{"a":[1,[2],{}],"b":"é\n","c":-1.5e+3,"d":null}""", "{}", "[ ]")) assertTrue(StrictJson.isValid(ok), ok)
        for (bad in listOf("""{"a":[1 2]}""", "[1,]", """{"a":1,}""", "{a:1}", "[01]", "[\"\u0001\"]", """["\x"]""", "[NaN]", "{} {}", "[[0]2]")) {
            assertTrue(!StrictJson.isValid(bad), bad)
        }
    }

    /** A strict RFC 8259 recognizer, independent of kotlinx, used to prove an input the scanner refuses really is malformed. */
    private object StrictJson {
        fun isValid(text: String): Boolean {
            val p = Recognizer(text.removePrefix("﻿"))
            return try {
                p.ws()
                p.value()
                p.ws()
                p.atEnd()
            } catch (e: IllegalStateException) {
                false
            }
        }

        private class Recognizer(val s: String) {
            var i = 0

            fun atEnd() = i == s.length

            fun ws() {
                while (i < s.length && s[i] in " \t\r\n") i++
            }

            private fun need(c: Char) {
                check(i < s.length && s[i] == c)
                i++
            }

            private fun literal(word: String) {
                check(s.startsWith(word, i))
                i += word.length
            }

            fun value() {
                check(i < s.length)
                when (s[i]) {
                    '{' -> container('{', '}') { string(); ws(); need(':'); ws(); value() }
                    '[' -> container('[', ']') { value() }
                    '"' -> string()
                    't' -> literal("true")
                    'f' -> literal("false")
                    'n' -> literal("null")
                    else -> number()
                }
            }

            private fun container(open: Char, close: Char, member: () -> Unit) {
                need(open)
                ws()
                if (i < s.length && s[i] == close) {
                    i++
                    return
                }
                while (true) {
                    member()
                    ws()
                    check(i < s.length)
                    if (s[i] == ',') {
                        i++
                        ws()
                        continue
                    }
                    need(close)
                    return
                }
            }

            private fun string() {
                need('"')
                while (true) {
                    check(i < s.length)
                    val c = s[i++]
                    check(c >= ' ')
                    if (c == '"') return
                    if (c == '\\') escape()
                }
            }

            private fun escape() {
                check(i < s.length)
                val e = s[i++]
                if (e == 'u') repeat(HEX_DIGITS) { check(i < s.length && s[i++].isHex()) } else check(e in "\"\\/bfnrt")
            }

            private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

            private fun digits(): Int {
                val start = i
                while (i < s.length && s[i] in '0'..'9') i++
                return i - start
            }

            private fun number() {
                if (i < s.length && s[i] == '-') i++
                check(i < s.length)
                if (s[i] == '0') i++ else check(digits() > 0)
                if (i < s.length && s[i] == '.') {
                    i++
                    check(digits() > 0)
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    i++
                    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                    check(digits() > 0)
                }
            }
        }

        private const val HEX_DIGITS = 4
    }

    private companion object {
        const val ITERATIONS = 300
        const val TEN_THOUSAND = 10_000
        const val DEEP = 200
        const val MUTATION_KINDS = 5
        const val MAX_OPS = 6
        const val MAX_TEXT = 40
        const val SMALL_IMPORT_CAP = 64L
        const val KOTLINX_MISSING_COMMA = """{"a":[[0]2],3]}"""
    }
}
