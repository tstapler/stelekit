// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only (the CLI lives in jvmMain; the PNG case needs the desktop Skia raster)
package dev.stapler.stelekit.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E7: `render --paper A4 --scale 1:50 --format pdf` and the SVG and PNG page outputs of the sketch CLI. */
class SketchCliPaperTest {
    private class Run(val code: Int, val out: String)

    private fun cli(vararg args: String): Run {
        val out = StringBuilder()
        val code = SketchCli({ out.appendLine(it) }, { }).run(args.toList())
        return Run(code, out.toString())
    }

    private val spec = """{"spec":1,"mode":"plan","title":"Room","shapes":[{"id":"box","role":"furniture","x":"0","y":"0","w":"10 ft","h":"7 ft"}]}"""

    private fun input(dir: File, text: String = spec) = File(dir, "room.spec.json").also { it.writeText(text) }.path

    @Test
    fun pdf_should_BeWrittenAtThePaperAndScale() {
        val dir = Files.createTempDirectory("sketch-cli-paper").toFile()
        val run = cli("render", input(dir), "--paper", "A4", "--scale", "1:50", "--format", "pdf", "--orientation", "portrait", "--title", "Room", "--out", File(dir, "room.pdf").path)
        assertEquals(0, run.code, run.out)
        assertTrue(run.out.contains("scale 1:50"), run.out)
        val text = File(dir, "room.pdf").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/MediaBox [0 0 595.2756 841.8898]"), text.substringAfter("/MediaBox").take(40))
        assertTrue(text.contains("(Room)"))
    }

    @Test
    fun svg_should_CarryPhysicalSizeAndScaleMetadata() {
        val dir = Files.createTempDirectory("sketch-cli-paper").toFile()
        val run = cli("render", input(dir), "--paper", "Letter", "--scale", "1/4in=1ft", "--orientation", "landscape", "--out", File(dir, "room.svg").path)
        assertEquals(0, run.code, run.out)
        val svg = File(dir, "room.svg").readText()
        assertTrue(svg.contains("width=\"11in\" height=\"8.5in\""), svg.take(300))
        assertTrue(svg.contains("data-sketch-scale=\"1:48\""))
    }

    @Test
    fun png_should_BeWrittenAtTheDpi_AndBadFlagsAreUsageProblems() {
        val dir = Files.createTempDirectory("sketch-cli-paper").toFile()
        assertEquals(0, cli("render", input(dir), "--paper", "A4", "--dpi", "72", "--out", File(dir, "room.png").path).code)
        assertTrue(File(dir, "room.png").length() > 0)
        assertEquals(2, cli("render", input(dir), "--paper", "A9", "--out", File(dir, "x.svg").path).code)
        assertEquals(2, cli("render", input(dir), "--format", "gif", "--out", File(dir, "x.gif").path).code)
    }

    @Test
    fun aDrawingTooBigForOnePage_should_WarnOrTileIntoPages() {
        val dir = Files.createTempDirectory("sketch-cli-paper").toFile()
        val file = input(dir, spec.replace("10 ft", "200 ft"))
        val single = cli("render", file, "--paper", "Letter", "--scale", "1:1", "--out", File(dir, "big.pdf").path)
        assertTrue(single.out.contains("WARNING"), single.out)
        val tiled = cli("render", file, "--paper", "Letter", "--scale", "1:12", "--tile", "--out", File(dir, "tiled.pdf").path)
        assertEquals(0, tiled.code, tiled.out)
        assertTrue(tiled.out.contains("page(s)"), tiled.out)
        val refused = cli("render", file, "--paper", "Letter", "--scale", "2:1", "--tile", "--out", File(dir, "refused.pdf").path)
        assertEquals(1, refused.code)
    }
}
