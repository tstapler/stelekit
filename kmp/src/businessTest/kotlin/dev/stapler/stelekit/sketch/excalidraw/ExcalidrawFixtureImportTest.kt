// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.1.1 over the six hand-authored Excalidraw v0.18.1 fixtures (`kmp/src/commonTest/resources/excalidraw`,
 * research/features.md section 14): the span scanner and the whole-file oracle must give EQUAL results on each.
 * The fixtures are hand-authored, NOT real exports (owner Task 0.1.5d is open), so this proves tolerance of the
 * documented shape only and is UNMEASURED against real files.
 */
class ExcalidrawFixtureImportTest {
    private val names = listOf("shapes-text", "bound-arrows", "frame-group", "image", "freedraw", "legacy-binding")

    private fun fixture(name: String): ByteArray =
        WiringInputs.dataFile("excalidraw/$name.excalidraw", "kmp/src/commonTest/resources/excalidraw/$name.excalidraw").encodeToByteArray()

    private fun scan(name: String): ImportResult = runBlocking {
        val bytes = fixture(name)
        val a = ExcalidrawParser().parse(ImportSource.ofBytes(bytes))
        val b = ExcalidrawParser().parseWholeFile(bytes)
        assertTrue(a is Either.Right && b is Either.Right, "$name: scanner $a, oracle $b")
        assertEquals((b as Either.Right).value, (a as Either.Right).value, "$name differs from the whole-file oracle")
        a.value
    }

    @Test
    fun fixtures_should_ImportEqualToTheWholeFileOracle() {
        for (name in names) scan(name)
    }

    @Test
    fun shapesText_should_KeepSixElementsAndNoIssues() {
        val r = scan("shapes-text")
        assertEquals(listOf("rect1", "t1", "dia1", "ell1", "t2", "line1"), r.scene.elements.map { it.id })
        assertEquals(emptyList(), r.report.issues)
        assertEquals("#ffffff", r.scene.appState?.viewBackgroundColor)
    }

    @Test
    fun frameGroup_should_KeepThreeUnsupportedKindsAsPlaceholdersWithACount() {
        val r = scan("frame-group")
        assertEquals(7, r.scene.elements.size)
        assertEquals(mapOf("magicframe" to 1, "embeddable" to 1, "iframe" to 1), r.report.unsupported)
        assertEquals(3, r.report.unsupportedCount)
        assertEquals(setOf("mf1", "emb1", "ifr1"), r.scene.elements.filter { it.unsupported }.map { it.id }.toSet())
    }

    @Test
    fun image_should_AdmitTheEmbeddedPngAndKeepBothImageElements() {
        val r = scan("image")
        assertEquals(2, r.scene.elements.size)
        val file = r.scene.files.single()
        assertEquals("file1", file.id)
        assertEquals("image/png", file.mimeType)
        assertEquals(1, file.widthPx)
        assertEquals(1, file.heightPx)
        assertTrue(r.scene.placeholderFileIds.isEmpty())
    }

    @Test
    fun boundArrows_freedraw_and_legacyBinding_should_KeepEveryElement() {
        assertEquals(listOf("a", "b", "c", "arr1", "arr2", "elbow1"), scan("bound-arrows").scene.elements.map { it.id })
        assertEquals(listOf("fd1", "fd2"), scan("freedraw").scene.elements.map { it.id })
        val legacy = scan("legacy-binding").scene.elements
        assertEquals(listOf("la", "lb", "larr"), legacy.map { it.id })
        assertTrue(legacy.last().raw["startBinding"] != null, "legacy {focus, gap} binding stays in the raw members for the mapper")
    }

    @Test
    fun stickynote_should_BeAnUnknownType_NotASupportedKind() {
        val text = """{"type":"excalidraw","version":2,"elements":[{"type":"stickynote","id":"s1","x":0,"y":0,"width":10,"height":10,"baseHeight":5}]}"""
        val r = runBlocking { ExcalidrawParser().parse(ImportSource.ofBytes(text.encodeToByteArray())) }
        val ok = (r as Either.Right).value
        assertEquals(emptyList(), ok.scene.elements)
        assertEquals(mapOf("stickynote" to 1), ok.report.unknownTypes)
    }
}
