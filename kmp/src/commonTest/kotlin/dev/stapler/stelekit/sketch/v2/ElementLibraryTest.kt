// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.library.LibraryCodec
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.library.ElementLibrary
import dev.stapler.stelekit.sketch.v2.library.InMemoryLibraryStorage
import dev.stapler.stelekit.sketch.v2.library.LibraryError
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 3.5.6: the library store, caps, insertion with fresh ids and the `.excalidrawlib` round trip. */
class ElementLibraryTest {
    private val three = listOf(rect("a", 0, 0), circle("b", 30, 30), line("c", 0, 0, 20, 20))

    @Test
    fun insertingTwice_should_GiveFreshDisjointIds() {
        val library = ElementLibrary.empty().add("i1", "Box group", three, created = 1L).get()
        val ids = W6Kit.SeqIds("n")
        val doc = run(SketchDocumentV2(), library.insert("i1", ids, 5.0, 5.0)!!, library.insert("i1", ids, 50.0, 5.0)!!)
        assertEquals(6, doc.scene.size)
        assertEquals(6, doc.scene.order.toSet().size)
        assertTrue(doc.scene.order.none { it.value in setOf("a", "b", "c") })
    }

    @Test
    fun add_should_RefuseTheTwoHundredAndFirstItemWithTheExactMessage() {
        val limit = SceneLimits.Default.maxLibraryItems
        var library = ElementLibrary.empty()
        for (i in 1..limit) library = library.add("i$i", null, listOf(rect("r$i", 0, 0)), i.toLong()).get()
        val error = library.add("one-more", null, three, 0L).swap().get()
        assertIs<LibraryError.TooManyItems>(error)
        assertEquals("Library is full (200 items). Delete an item to add more.", error.message)
        assertEquals(limit, library.items.size)
    }

    @Test
    fun add_should_RefuseWhenTheStoredBytesCapIsPassed_WithTheMegabyteMessage() {
        val small = SceneLimits(maxLibraryBytes = 2 * 1_048_576L)
        var library = ElementLibrary.empty(small)
        val big = (1..400).map { rect("r$it", it, 0) }
        var refused: LibraryError? = null
        var n = 0
        while (refused == null && n < 100) {
            n++
            library.add("i$n", null, big.map { it.withBase(it.base.copy(id = dev.stapler.stelekit.sketch.v2.scene.ElementId("i$n-${it.id.value}"))) }, 0L).fold({ refused = it }, { library = it })
        }
        assertIs<LibraryError.TooManyBytes>(refused)
        assertEquals("Library is full (2 MB). Delete an item to add more.", refused.message)
        assertTrue(library.totalBytes <= small.maxLibraryBytes)
    }

    @Test
    fun emptySelection_should_BeRefused() {
        assertEquals(LibraryError.NothingSelected, ElementLibrary.empty().add("i", null, emptyList(), 0L).swap().get())
    }

    @Test
    fun storage_should_RoundTripAndSurviveGarbage() {
        val library = ElementLibrary.empty().add("i1", "Box", three, 7L).get()
        val storage = InMemoryLibraryStorage()
        library.save(storage)
        val back = ElementLibrary.load(storage)
        assertEquals(library.items.map { it.id to it.elements }, back.items.map { it.id to it.elements })
        assertEquals(0, ElementLibrary.load(InMemoryLibraryStorage("not json")).items.size)
        assertEquals(0, ElementLibrary.load(InMemoryLibraryStorage(null)).items.size)
    }

    @Test
    fun delete_should_RemoveTheItem() {
        val library = ElementLibrary.empty().add("i1", null, three, 1L).get().add("i2", null, three, 2L).get()
        assertEquals(listOf("i2"), library.delete("i1").items.map { it.id })
    }

    @Test
    fun excalidrawlib_should_RoundTripKindsAndGeometry() = runTest {
        val library = ElementLibrary.empty().add("i1", "Box group", three, 1L).get()
        val text = LibraryCodec.encode(library.items)
        assertTrue(text.contains("\"type\":\"excalidrawlib\"") && text.contains("\"libraryItems\""))
        val decoded = LibraryCodec.decode(ImportSource.ofBytes(text.encodeToByteArray())).get()
        val item = decoded.items.single()
        assertEquals(three.map { it::class }, item.elements.map { it::class })
        val original = library.items.single().elements
        assertEquals(original.map { dev.stapler.stelekit.sketch.v2.view.SceneBounds.of(it) }, item.elements.map { dev.stapler.stelekit.sketch.v2.view.SceneBounds.of(it) })
    }

    @Test
    fun decode_should_FallBackToDefaultColours_When_AFileCarriesAUrlOrOverlongColour() = runTest {
        val hostile = "url(https://evil.example/x.svg#a)"
        val rect = """{"type":"rectangle","id":"r","x":0,"y":0,"width":10,"height":10,"angle":0,"strokeColor":"$hostile","backgroundColor":"${"a".repeat(65)}","strokeWidth":2,"roughness":1,"opacity":100,"groupIds":[],"locked":false}"""
        val file = """{"type":"excalidrawlib","version":2,"source":"test","libraryItems":[{"id":"i","status":"unpublished","created":1,"elements":[$rect]}]}"""
        val style = (LibraryCodec.decode(ImportSource.ofBytes(file.encodeToByteArray())).get().items.single().elements.single()).base.style
        assertEquals("#1e1e1e", style.strokeColor)
        assertEquals("transparent", style.backgroundColor)
        val ok = file.replace(hostile, "rgb(1, 2, 3)")
        assertEquals("rgb(1, 2, 3)", LibraryCodec.decode(ImportSource.ofBytes(ok.encodeToByteArray())).get().items.single().elements.single().base.style.strokeColor)
    }

    @Test
    fun encode_should_WriteCanonicalNumbers_And_DecodeShouldReadThemBack() = runTest {
        val rect = dev.stapler.stelekit.sketch.v2.scene.RectElement(
            dev.stapler.stelekit.sketch.v2.scene.ElementBase(dev.stapler.stelekit.sketch.v2.scene.ElementId("r")),
            dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(0.1 + 0.2).getOrNull()!!, dev.stapler.stelekit.sketch.v2.scene.SceneUnit.ofInt(2),
            dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(1.5).getOrNull()!!, dev.stapler.stelekit.sketch.v2.scene.SceneUnit.ofInt(4),
        )
        val item = ElementLibrary.empty().add("i", null, listOf(rect), 1L).get().items.single()
        val text = LibraryCodec.encode(listOf(item))
        assertTrue(text.contains("\"x\":0.30000000000000004"), text)
        assertTrue(text.contains("\"width\":1.5"), text)
        val back = LibraryCodec.decode(ImportSource.ofBytes(text.encodeToByteArray())).get().items.single().elements.single()
        assertEquals(0.1 + 0.2, (back as dev.stapler.stelekit.sketch.v2.scene.RectElement).x.value)
    }

    @Test
    fun merge_should_AddWhatFitsInFileOrder_And_NeverStoreAPartialItem() {
        val limits = SceneLimits(maxLibraryItems = 2)
        val items = (1..3).map { ElementLibrary.empty().add("i$it", null, three, it.toLong()).get().items.single() }
        val result = ElementLibrary.empty(limits).merge(items)
        assertEquals(2, result.added)
        assertEquals(1, result.skippedFull)
        assertEquals(listOf("i1", "i2"), result.library.items.map { it.id })
    }

    private fun itemJson(i: Int, elements: Int): String {
        val rects = (1..elements).joinToString(",") { e ->
            """{"type":"rectangle","id":"i$i-$e","x":$e,"y":0,"width":10,"height":10,"angle":0,"strokeColor":"#1e1e1e","backgroundColor":"transparent","strokeWidth":2,"roughness":1,"opacity":100,"groupIds":[],"locked":false}"""
        }
        return """{"id":"item$i","status":"unpublished","created":$i,"elements":[$rects]}"""
    }

    @Test
    fun largeLibrary_should_ImportThroughPerItemSpans_And_SkipOnlyTheOversizedItem() = runTest {
        // Scaled version of the 9 MB / 120 item case: every decode call stays at most maxImportSpanBytes.
        val limits = SceneLimits(maxImportSpanBytes = 20_000L)
        val items = (1..40).map { itemJson(it, 40) } + itemJson(99, 400)
        val file = """{"type":"excalidrawlib","version":2,"source":"test","libraryItems":[${items.joinToString(",")}]}"""
        var largest = 0
        val counting = dev.stapler.stelekit.sketch.excalidraw.SpanDecoder { text ->
            largest = maxOf(largest, text.length)
            dev.stapler.stelekit.sketch.excalidraw.SpanDecoder.Default.decode(text)
        }
        assertTrue(file.length > 5 * limits.maxImportSpanBytes, "the file is far larger than one span")
        val decoded = LibraryCodec.decode(ImportSource.ofBytes(file.encodeToByteArray()), limits, counting).get()
        assertEquals(40, decoded.items.size, "only the 400-element item is skipped")
        assertTrue(largest <= limits.maxImportSpanBytes, "largest decode call was $largest")
        assertTrue(decoded.report.issues.any { it.reason == dev.stapler.stelekit.sketch.excalidraw.ImportReason.ImportSpanTooLarge })
    }
}
