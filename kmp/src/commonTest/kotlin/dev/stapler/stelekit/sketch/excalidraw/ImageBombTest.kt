// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.fileEntry
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.files
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.pngBytes
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.1.1d: decoded-image limits come from the file HEADER, so a decompression bomb is refused without allocating pixels. */
class ImageBombTest {
    private val limits = SceneLimits.Default

    private suspend fun import(vararg entries: String): ImportResult {
        val text = scene(listOf(element("image", "i1", extra = ""","fileId":"f0"""")), files(*entries))
        return (ExcalidrawParser().parse(ImportSource.ofBytes(text.encodeToByteArray())) as Either.Right).value
    }

    private fun reasons(r: ImportResult) = r.report.issues.associate { it.target to it.reason }

    @Test
    fun import_should_MakeAPlaceholder_When_APngHeaderDeclaresSixtyThousandSquare() = runTest {
        val bomb = pngBytes(BOMB_SIDE, BOMB_SIDE, FOUR_KB)
        val r = import(fileEntry("bomb", "image/png", bomb))
        assertEquals(ImportReason.ImageTooLarge, reasons(r)["bomb"])
        assertEquals(1, r.report.issues.size)
        assertTrue(r.scene.files.isEmpty())
        assertEquals(setOf("bomb"), r.scene.placeholderFileIds)
    }

    @Test
    fun import_should_MakeAPlaceholder_When_AJpegSofDeclaresTwentyThousandSquare() = runTest {
        val r = import(fileEntry("jbomb", "image/jpeg", ExcalidrawTestData.jpegBytes(JPEG_BOMB_SIDE, JPEG_BOMB_SIDE)))
        assertEquals(ImportReason.ImageTooLarge, reasons(r)["jbomb"])
    }

    @Test
    fun import_should_RefuseOnSideAndOnPixelCount_Separately() = runTest {
        val tooWide = pngBytes(limits.maxImageSidePx + 1, 1)
        val tooManyPixels = pngBytes(limits.maxImageSidePx, limits.maxImageSidePx) // 8192 x 8192 = 67 MP > 16 MP
        val fine = pngBytes(limits.maxImageSidePx / 2, limits.maxImageSidePx / 4) // 4096 x 2048 = 8.4 MP
        val r = import(fileEntry("wide", "image/png", tooWide), fileEntry("pixels", "image/png", tooManyPixels), fileEntry("fine", "image/png", fine))
        assertEquals(ImportReason.ImageTooLarge, reasons(r)["wide"])
        assertEquals(ImportReason.ImageTooLarge, reasons(r)["pixels"])
        assertEquals(listOf("fine"), r.scene.files.map { it.id })
    }

    @Test
    fun import_should_RefuseAMismatchBetweenDeclaredMimeAndBytes() = runTest {
        val r = import(fileEntry("liar", "image/jpeg", pngBytes(8, 8)), fileEntry("honest", "image/png", pngBytes(8, 8)))
        assertEquals(ImportReason.ImageMimeMismatch, reasons(r)["liar"])
        assertEquals(listOf("honest"), r.scene.files.map { it.id })
    }

    @Test
    fun import_should_RefuseSvgAndUnknownMimeTypes() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".encodeToByteArray()
        val r = import(fileEntry("svg", "image/svg+xml", svg), fileEntry("bmp", "image/bmp", pngBytes(8, 8)))
        assertEquals(ImportReason.ImageMimeNotAllowed, reasons(r)["svg"])
        assertEquals(ImportReason.ImageMimeNotAllowed, reasons(r)["bmp"])
        assertTrue(r.scene.files.isEmpty())
    }

    @Test
    fun import_should_AdmitImagesOnlyUntilTheSheetDecodedBudgetIsUsedUp() = runTest {
        val side = BUDGET_IMAGE_SIDE // 2000 x 2000 x 4 bytes = 16 MB decoded, so 4 fit in 64 MB (67,108,864)
        val entries = (0 until FORTY).map { fileEntry("img$it", "image/png", pngBytes(side, side)) }
        val r = import(*entries.toTypedArray())
        val perImage = side.toLong() * side * RGBA
        val fits = (limits.maxDecodedImageBytesPerSheet / perImage).toInt()
        assertEquals(fits, r.scene.files.size)
        assertEquals(FORTY - fits, r.report.count(ImportReason.ImageBudgetExceeded))
        assertEquals((0 until fits).map { "img$it" }, r.scene.files.map { it.id })
        assertTrue(r.scene.files.sumOf { it.widthPx.toLong() * it.heightPx * RGBA } <= limits.maxDecodedImageBytesPerSheet)
    }

    @Test
    fun import_should_RefuseACompressedFileOverTheByteCap_WithoutDecodingIt() = runTest {
        val slices = ArrayList<Int>()
        val tight = limits // the parser reads maxImageBytes from the limits it is given
        val small = SceneLimits(maxImageBytes = SMALL_CAP)
        val text = scene(emptyList(), files(fileEntry("big", "image/png", pngBytes(8, 8, SMALL_CAP.toInt() * 2))))
        val r = (ExcalidrawParser(small, onBase64Slice = { slices += it }).parse(ImportSource.ofBytes(text.encodeToByteArray())) as Either.Right).value
        assertEquals(ImportReason.ImageTooLarge, reasons(r)["big"])
        assertTrue(slices.isEmpty(), "an oversize file must be refused before the base64 payload is decoded")
        assertEquals(limits.maxImageBytes, tight.maxImageBytes)
    }

    @Test
    fun import_should_RefuseCorruptBase64AndMissingDataUrl() = runTest {
        val bad = """"bad":{"mimeType":"image/png","id":"bad","dataURL":"data:image/png;base64,@@@@"}"""
        val none = """"none":{"mimeType":"image/png","id":"none"}"""
        val r = import(bad, none)
        assertEquals(ImportReason.ImageInvalid, reasons(r)["bad"])
        assertEquals(ImportReason.ImageInvalid, reasons(r)["none"])
    }

    private companion object {
        const val BOMB_SIDE = 60_000
        const val JPEG_BOMB_SIDE = 20_000
        const val FOUR_KB = 4096
        const val BUDGET_IMAGE_SIDE = 2_000
        const val FORTY = 40
        const val RGBA = 4L
        const val SMALL_CAP = 1_024L
    }
}
