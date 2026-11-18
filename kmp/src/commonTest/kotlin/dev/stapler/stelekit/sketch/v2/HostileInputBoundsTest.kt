// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.projectbridge.CorruptReason
import dev.stapler.stelekit.projectbridge.SidecarClassifier
import dev.stapler.stelekit.projectbridge.SidecarFileKind
import dev.stapler.stelekit.projectbridge.SidecarFileState
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawParser
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.clipboard.ClipboardPayload
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review C (PR 685): hostile sheet and scene bytes are bounded by size, nesting and member counts BEFORE any tree is built,
 * so a small file cannot overflow the stack (C-1) or exhaust the heap (C-2, C-3), and a colour string from a sheet passes
 * the same filter as an imported one (C-4). Caps come from [SceneLimits]; payloads sit just over a small cap, never near a heap limit.
 */
class HostileInputBoundsTest {
    private val limits = SceneLimits.Default
    private val overDepth = limits.maxJsonDepth + 1
    private val bombDepth = 100_000

    private fun nested(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    private fun sheetWith(body: String) = ("""{"schema":2,"a":""" + body + "}").encodeToByteArray()

    // ---- C-1: classifier

    @Test
    fun classifyJson_should_RefuseAHundredThousandDeepSheetAsCorrupt_WithoutOverflowingTheStack() {
        val state = SidecarClassifier.classifyJson(sheetWith(nested(bombDepth)), SidecarFileKind.Sheet)
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidJson), state)
    }

    @Test
    fun classifyJson_should_RefuseNestingJustOverTheCap_And_AcceptNestingAtTheCap() {
        // The root object is depth 1, so `a` holding an array nest of N is depth N + 1.
        assertIs<SidecarFileState.Corrupt>(SidecarClassifier.classifyJson(sheetWith(nested(overDepth)), SidecarFileKind.Sheet))
        assertIs<SidecarFileState.Valid>(SidecarClassifier.classifyJson(sheetWith(nested(limits.maxJsonDepth - 1)), SidecarFileKind.Sheet))
    }

    @Test
    fun classifyJson_should_RefuseAFileOverTheSheetByteCap_BeforeParsing() {
        val tiny = SceneLimits(maxSheetBytes = 64)
        val bytes = sheetWith("\"" + "x".repeat(tiny.maxSheetBytes.toInt()) + "\"")
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidJson), SidecarClassifier.classifyJson(bytes, SidecarFileKind.Sheet, limits = tiny))
    }

    @Test
    fun schemaOf_should_ReturnNull_When_TheFileIsTooDeep() {
        assertNull(SidecarClassifier.schemaOf(sheetWith(nested(bombDepth))))
    }

    @Test
    fun decode_should_RefuseAHundredThousandDeepSheet_AsTooDeep() {
        val result = SheetCodecV2.decode(sheetWith(nested(bombDepth)))
        assertIs<SheetError.TooDeep>((result as Either.Left).value)
    }

    // ---- C-2: element count before the tree

    @Test
    fun decode_should_ReturnTooManyElements_WithoutParsingTheRest_When_TheArrayPassesTheCap() {
        val small = SceneLimits(maxElements = 10)
        // Unterminated on purpose: a decoder that builds the whole tree reports Malformed, one that counts first reports the cap.
        val text = """{"schema":2,"sheetUuid":"u","elements":[""" + "{},".repeat(small.maxElements + 1)
        val error = (SheetCodecV2.decode(text.encodeToByteArray(), small) as Either.Left).value
        assertEquals(SheetError.TooManyElements(small.maxElements + 1, small.maxElements), error)
    }

    @Test
    fun decode_should_AcceptExactlyTheElementCap_And_RefuseOneMore() {
        val small = SceneLimits(maxElements = 10)
        fun sheet(n: Int) = ("""{"schema":2,"sheetUuid":"u","elements":[""" + List(n) { "{}" }.joinToString(",") + "]}").encodeToByteArray()
        // `{}` is not a valid element, so exactly-at-cap fails later with a different error, never TooManyElements.
        assertNotEquals(SheetError.TooManyElements(10, 10), (SheetCodecV2.decode(sheet(10), small) as Either.Left).value)
        assertIs<SheetError.TooManyElements>((SheetCodecV2.decode(sheet(11), small) as Either.Left).value)
    }

    @Test
    fun clipboardFromText_should_RefuseAnOverlongString_And_AnElementBomb() {
        val small = SceneLimits(maxElements = 10, maxSheetBytes = 4_096)
        val bomb = ClipboardPayload.MARKER + """{"schema":2,"sheetUuid":"u","elements":[""" + "{},".repeat(small.maxElements + 1) + "{}]}"
        assertNull(ClipboardPayload.fromText(bomb, small))
        assertNull(ClipboardPayload.fromText(ClipboardPayload.MARKER + "x".repeat(small.maxSheetBytes.toInt() + 1), small))
    }

    // ---- C-3: top-level members of a scene file

    @Test
    fun parse_should_RefuseAFileWithTooManyTopLevelMembers() = runTest {
        val small = SceneLimits(maxJsonRootMembers = 8)
        val members = (0 until small.maxJsonRootMembers + 1).joinToString(",") { "\"k$it\":1" }
        val text = """{"type":"excalidraw","elements":[],$members}"""
        val result = ExcalidrawParser(small).parse(ImportSource.ofBytes(text.encodeToByteArray()))
        assertEquals(ImportReason.TooManyElements, (result as Either.Left).value.reason)
        val oracle = ExcalidrawParser(small).parseWholeFile(text.encodeToByteArray())
        assertEquals(ImportReason.TooManyElements, (oracle as Either.Left).value.reason)
    }

    @Test
    fun parse_should_AcceptTopLevelMembersUpToTheCap_And_NotCountADuplicateKeyTwice() = runTest {
        val small = SceneLimits(maxJsonRootMembers = 8)
        val members = (0 until small.maxJsonRootMembers - 2).joinToString(",") { "\"k$it\":1" }
        val text = """{"type":"excalidraw","elements":[],$members,"k0":2,"k0":3}"""
        assertTrue(ExcalidrawParser(small).parse(ImportSource.ofBytes(text.encodeToByteArray())) is Either.Right)
    }

    @Test
    fun parse_should_CountTheElementsAndFilesMembersTowardTheTopLevelCap_LikeAnyOtherMember() = runTest {
        val small = SceneLimits(maxJsonRootMembers = 8)
        val junk = (0 until small.maxJsonRootMembers - 1).joinToString(",") { "\"k$it\":1" }
        // `junk` holds cap-1 members, so "elements" or "files" is the (cap)th: accepted. One more junk key before it is cap+1: refused.
        val oneOver = "$junk,\"k99\":1"
        listOf(
            """{$junk,"elements":[]}""" to true,
            """{$oneOver,"elements":[]}""" to false,
            """{$junk,"files":{}}""" to true,
            """{$oneOver,"files":{}}""" to false,
        ).forEach { (text, accepted) ->
            val scanner = ExcalidrawParser(small).parse(ImportSource.ofBytes(text.encodeToByteArray()))
            val oracle = ExcalidrawParser(small).parseWholeFile(text.encodeToByteArray())
            assertEquals(accepted, scanner is Either.Right, "scanner on $text")
            assertEquals(accepted, oracle is Either.Right, "oracle on $text")
            if (!accepted) assertEquals(ImportReason.TooManyElements, (scanner as Either.Left).value.reason)
        }
    }

    // ---- C-4: colour filter on the native decode path

    @Test
    fun decode_should_ReplaceAHostileColour_WithTheDefault() {
        val doc = SheetFixtures.rects(1)
        val text = SheetFixtures.bytes(doc).decodeToString()
        val hostile = "url(https://evil.example/x.svg#a)"
        val tampered = text.replace("\"stroke\":\"#1e1e1e\"", "\"stroke\":\"$hostile\"").replace("\"bg\":\"transparent\"", "\"bg\":\"$hostile\"")
        val style = (SheetCodecV2.decode(tampered.encodeToByteArray()) as Either.Right).value.scene.elementsInOrder.first().base.style
        assertEquals("#1e1e1e", style.strokeColor)
        assertEquals("transparent", style.backgroundColor)
    }

    @Test
    fun decode_should_KeepAnOrdinaryColour() {
        val text = SheetFixtures.bytes(SheetFixtures.rects(1)).decodeToString()
        val tampered = text.replace("\"stroke\":\"#1e1e1e\"", "\"stroke\":\"rgb(10, 20, 30)\"")
        val style = (SheetCodecV2.decode(tampered.encodeToByteArray()) as Either.Right).value.scene.elementsInOrder.first().base.style
        assertEquals("rgb(10, 20, 30)", style.strokeColor)
    }
}
