// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Hand-built `.excalidraw` text for the importer tests. commonTest cannot read resource files on wasmJs, so the
 * six Story 0.1.5 fixtures are read by `ExcalidrawFixtureImportTest` (businessTest, JVM); everything else is built here.
 */
@OptIn(ExperimentalEncodingApi::class)
object ExcalidrawTestData {
    val limits = SceneLimits.Default

    fun element(type: String, id: String, x: Number = 0, y: Number = 0, width: Number = 10, height: Number = 10, extra: String = "", deleted: Boolean = false): String =
        """{"type":"$type","id":"$id","x":$x,"y":$y,"width":$width,"height":$height,"angle":0,"strokeColor":"#1e1e1e","backgroundColor":"transparent","fillStyle":"solid","strokeWidth":2,"strokeStyle":"solid","roughness":1,"opacity":100,"groupIds":[],"frameId":null,"index":"a0","isDeleted":$deleted,"locked":false$extra}"""

    fun scene(elements: List<String>, files: String? = null, type: String = "excalidraw", appState: String = """{"gridSize":null,"viewBackgroundColor":"#ffffff"}"""): String =
        """{"type":"$type","version":2,"source":"https://excalidraw.com","elements":[${elements.joinToString(",")}],"appState":$appState${if (files != null) ""","files":$files""" else ""}}"""

    /** A PNG that is only a valid signature and IHDR, padded with zeros to [totalSize]; enough for the header probe. */
    fun pngBytes(width: Int, height: Int, totalSize: Int = PNG_MIN): ByteArray {
        val out = ByteArray(maxOf(totalSize, PNG_MIN))
        val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
        head.copyInto(out)
        be32(out, WIDTH_AT, width)
        be32(out, HEIGHT_AT, height)
        return out
    }

    fun jpegBytes(width: Int, height: Int): ByteArray {
        val out = ByteArray(JPEG_MIN)
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 17, 8).copyInto(out)
        out[JPEG_HEIGHT_AT] = (height shr BYTE_BITS).toByte()
        out[JPEG_HEIGHT_AT + 1] = height.toByte()
        out[JPEG_HEIGHT_AT + 2] = (width shr BYTE_BITS).toByte()
        out[JPEG_HEIGHT_AT + 3] = width.toByte()
        return out
    }

    fun dataUrl(mime: String, bytes: ByteArray): String = "data:$mime;base64," + Base64.Default.encode(bytes)

    fun fileEntry(id: String, mime: String, bytes: ByteArray): String =
        """"$id":{"mimeType":"$mime","id":"$id","dataURL":"${dataUrl(mime, bytes)}","created":1700000000000,"lastRetrieved":1700000000000}"""

    fun files(vararg entries: String): String = "{" + entries.joinToString(",") + "}"

    /** The well-formed baseline: every element kind the importer keeps, an unsupported kind, a deleted one and an unknown type. */
    fun mixedScene(): String = scene(
        listOf(
            element("rectangle", "r1", 0, 0, 100, 50, ""","boundElements":[{"id":"a1","type":"arrow"}]"""),
            element("ellipse", "e1", 200, 0, 60, 60),
            element("diamond", "d1", 300, 0, 60, 60),
            element("text", "t1", 10, 10, 80, 25, ""","text":"hello","originalText":"hello","fontSize":20,"fontFamily":1,"containerId":"r1""""),
            element("arrow", "a1", 100, 25, 100, 0, ""","points":[[0,0],[100,0]],"startBinding":{"elementId":"r1","focus":0,"gap":4},"endBinding":null"""),
            element("line", "l1", 0, 100, 50, 50, ""","points":[[0,0],[50,50]]"""),
            element("freedraw", "f1", 0, 200, 10, 10, ""","points":[[0,0],[1,2],[3,4]],"pressures":[0.5,0.5,0.5],"simulatePressure":false"""),
            element("frame", "fr1", -10, -10, 500, 300),
            element("embeddable", "emb1", 0, 400, 100, 100, ""","link":"https://example.com""""),
            element("stickynote", "s1"),
            element("rectangle", "gone", deleted = true),
        ),
    )

    private fun be32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr BE_SHIFT_3).toByte()
        b[at + 1] = (v ushr BE_SHIFT_2).toByte()
        b[at + 2] = (v ushr BYTE_BITS).toByte()
        b[at + 3] = v.toByte()
    }

    private const val PNG_MIN = 33
    private const val WIDTH_AT = 16
    private const val HEIGHT_AT = 20
    private const val JPEG_MIN = 32
    private const val JPEG_HEIGHT_AT = 7
    private const val BYTE_BITS = 8
    private const val BE_SHIFT_2 = 16
    private const val BE_SHIFT_3 = 24
}
