// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only (the CLI lives in jvmMain; the PNG case needs the desktop Skia raster)
package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.util.ContentHasher
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5 in the agent CLI: `build` keeps a calibrated photo under a traced plan, `describe` reads it back, `render` leaves it out unless asked. */
class SketchUnderlayCliTest {
    private class Run(val code: Int, val out: String)

    private fun cli(vararg args: String): Run {
        val out = StringBuilder()
        val code = SketchCli({ out.appendLine(it) }, { out.append(it) }).run(args.toList())
        return Run(code, out.toString())
    }

    private fun solidPhoto(file: File, color: Color): ByteArray {
        val image = BufferedImage(PHOTO_W, PHOTO_H, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, PHOTO_W, PHOTO_H)
        g.dispose()
        ImageIO.write(image, "png", file)
        return file.readBytes()
    }

    private fun workspace(): File = Files.createTempDirectory("sketch-underlay-cli").toFile()

    private fun spec(dir: File): File = File(dir, "kitchen.spec.json").also { it.writeText(SPEC) }

    private fun built(dir: File): File {
        solidPhoto(File(dir, "photo.png"), Color.RED)
        val sheet = File(dir, "kitchen.v2.json")
        val result = cli("build", spec(dir).path, "--out", sheet.path)
        assertEquals(0, result.code, result.out)
        return sheet
    }

    @Test
    fun `build keeps the photo as a locked calibrated underlay at the calibrated scale`() {
        val dir = workspace()
        try {
            val photoBytes = solidPhoto(File(dir, "photo.png"), Color.RED)
            val sheet = built(dir)
            val doc = (SheetCodecV2.decode(sheet.readBytes()) as Either.Right).value
            val state = assertNotNull(doc.scene.underlay)
            assertEquals(ContentHasher.sha256(photoBytes), state.element.fileId)
            assertEquals(PHOTO_W, state.meta.widthPx)
            assertEquals(PHOTO_H, state.meta.heightPx)
            assertTrue(state.locked)
            assertEquals(0.4, state.layer.opacity, 1.0e-9)
            // 3 feet over 200 px is 2.88 scene units per pixel.
            assertEquals(PHOTO_W * 2.88, state.placement.width, 1.0e-6)
            assertEquals(CalibrationNote.Calibrated(576L, perspective = false), CalibrationNote.of(state))
            assertTrue(state.layer.order < 0, "below every other layer")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `describe reports the underlay and its calibration, and leaves it out of the element list`() {
        val dir = workspace()
        try {
            val text = cli("describe", built(dir).path).out
            assertTrue("UNDERLAY" in text, text)
            assertTrue("calibrated: 3' 0\" ref" in text, text)
            assertTrue("locked" in text && "40% opacity" in text, text)
            assertFalse("underlay:" in text.substringAfter("ELEMENTS").substringBefore("UNDERLAY"), "the photo is not a drawn element")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `render leaves the underlay out unless it is asked for with the photo file`() {
        val dir = workspace()
        try {
            val sheet = built(dir)
            val plain = File(dir, "plain.svg")
            assertEquals(0, cli("render", sheet.path, "--out", plain.path).code)
            assertFalse("data:image" in plain.readText(), "no photo in the default render")
            val withPhoto = File(dir, "photo.svg")
            val run = cli("render", sheet.path, "--out", withPhoto.path, "--underlay", File(dir, "photo.png").path)
            assertEquals(0, run.code, run.out)
            assertTrue("data:image/png;base64" in withPhoto.readText(), "the photo is drawn when asked for")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `render refuses a photo that is not the one the sheet names`() {
        val dir = workspace()
        try {
            val sheet = built(dir)
            val other = File(dir, "other.png")
            solidPhoto(other, Color.BLUE)
            val run = cli("render", sheet.path, "--out", File(dir, "x.svg").path, "--underlay", other.path)
            assertEquals(1, run.code)
            assertTrue("not the sheet's photo" in run.out, run.out)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `png render shows the photo under the plan only when it is included`() {
        val dir = workspace()
        try {
            val sheet = built(dir)
            val plain = File(dir, "plain.png")
            val with = File(dir, "with.png")
            assertEquals(0, cli("render", sheet.path, "--out", plain.path, "--width", "800").code)
            assertEquals(0, cli("render", sheet.path, "--out", with.path, "--width", "800", "--underlay", File(dir, "photo.png").path).code)
            val a = ImageIO.read(plain)
            val b = ImageIO.read(with)
            // A point well inside the traced room: white on its own, the red photo at 40 % when included.
            val plainPixel = Color(a.getRGB(a.width / 2, a.height / 2))
            val photoPixel = Color(b.getRGB(b.width / 2, b.height / 2))
            assertEquals(Color.WHITE, plainPixel)
            assertTrue(photoPixel.green < PHOTO_BLEND_MAX_GREEN && photoPixel.red > photoPixel.green, "photo shows through: $photoPixel")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `place stores the photo in the graph assets and refuses a different one`() {
        val dir = workspace()
        try {
            val sheet = built(dir)
            val graph = File(dir, "graph").also { it.mkdirs() }
            val hash = ContentHasher.sha256(File(dir, "photo.png").readBytes())
            val run = cli("place", sheet.path, "--graph", graph.path, "--underlay-image", File(dir, "photo.png").path)
            assertEquals(0, run.code, run.out)
            assertTrue(File(graph, "assets/$hash.png").isFile)
            val other = File(dir, "other.png")
            solidPhoto(other, Color.GREEN)
            assertEquals(1, cli("place", sheet.path, "--graph", graph.path, "--underlay-image", other.path).code)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a spec without an underlay builds a sheet with none`() {
        val doc = SketchSpecBuilder(kotlinx.serialization.json.Json.parseToJsonElement(PLAIN_SPEC) as kotlinx.serialization.json.JsonObject).build().doc
        assertNull(assertNotNull(doc).scene.underlay)
        assertNull(doc.scene.layer(UnderlayIds.LAYER))
    }

    private companion object {
        const val PHOTO_W = 400
        const val PHOTO_H = 300
        const val PHOTO_BLEND_MAX_GREEN = 250

        val SPEC = """{
  "spec": 1, "mode": "plan", "title": "Kitchen photo", "space": "photo",
  "calibration": {"refs": [{"a": [50, 50], "b": [250, 50], "length": "3'"}], "snap": "none"},
  "underlay": {"image": "photo.png", "opacity": 40},
  "shapes": [{"id": "room", "x": 40, "y": 40, "w": 320, "h": 220}]
}"""

        val PLAIN_SPEC = """{"spec": 1, "mode": "plan", "title": "Plain", "shapes": [{"id": "room", "x": "0", "y": "0", "w": "10'", "h": "8'"}]}"""
    }
}
