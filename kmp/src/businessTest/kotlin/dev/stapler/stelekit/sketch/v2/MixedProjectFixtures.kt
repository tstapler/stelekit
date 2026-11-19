// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.v2.bridge.V1ToV2
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.ui.sketch.SketchDocumentSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A project of two v1 sheets built from fixed shapes (a wall outline of four lines, a counter rectangle with flipped
 * corners, named zones). [build] can convert sheet A to schema 2 the way the converter will (lift then encode), so a test
 * compares what every consumer sees with and without the conversion.
 */
internal object MixedProjectFixtures {
    const val SHEET_A = "p1-a"
    const val SHEET_B = "p1-b"
    val slug = SketchTestSupport.project().slug

    fun sidecarFile(rel: String): String = dev.stapler.stelekit.projectbridge.sidecarPath(slug, rel)

    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun line(id: String, ax: Long, ay: Long, bx: Long, by: Long, layer: String = "walls") =
        SketchPrimitive.Line(id, pt(ax, ay), pt(bx, by), layer)

    private fun rect(id: String, ax: Long, ay: Long, bx: Long, by: Long, layer: String = "") =
        SketchPrimitive.Rect(id, pt(ax, ay), pt(bx, by), layer)

    /** Sheet A's shapes, in paint order; the second rectangle is drawn right to left so its corners are flipped. */
    val sheetAShapes: List<SketchPrimitive> = listOf(
        line("a-l1", 0, 0, 960, 0), line("a-l2", 960, 0, 960, 480), line("a-l3", 960, 480, 0, 480), line("a-l4", 0, 480, 0, 0),
        rect("a-r1", 80, 80, 400, 240), rect("a-r2", 880, 400, 560, 320, layer = "walls"),
    )

    val sheetBShapes: List<SketchPrimitive> = listOf(line("b-l1", 0, 0, 608, 0), rect("b-r1", 16, 16, 160, 96))

    val zones: JsonArray = JsonArray(
        listOf(
            JsonObject(mapOf("key" to JsonPrimitive("left"), "text" to JsonPrimitive("3'"))),
            JsonObject(mapOf("key" to JsonPrimitive("right"), "text" to JsonPrimitive("2'"))),
            JsonObject(mapOf("key" to JsonPrimitive("all"), "text" to JsonPrimitive("5'"), "total" to JsonPrimitive(true))),
        ),
    )

    private fun sceneOf(shapes: List<SketchPrimitive>): SketchScene =
        shapes.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: error("fixture shape rejected") }

    private fun storedOf(sheet: String, shapes: List<SketchPrimitive>): List<dev.stapler.stelekit.model.SketchPrimitive> =
        shapes.mapIndexed { z, p ->
            when (p) {
                is SketchPrimitive.Line -> dev.stapler.stelekit.model.SketchPrimitive(p.id, SheetUuid(sheet), "line", p.a.x.sixteenths, p.a.y.sixteenths, p.b.x.sixteenths, p.b.y.sixteenths, layer = p.layer, z = z.toLong())
                is SketchPrimitive.Rect -> dev.stapler.stelekit.model.SketchPrimitive(p.id, SheetUuid(sheet), "rect", p.a.x.sixteenths, p.a.y.sixteenths, p.b.x.sixteenths, p.b.y.sixteenths, layer = p.layer, z = z.toLong())
            }
        }

    private fun sheetRow(uuid: String, position: Long) =
        SketchTestSupport.sheet(uuid, position = position).copy(zones = zones)

    /** The v2 file of [shapes]: lifted exactly as the converter lifts a v1 sheet. */
    fun v2Bytes(sheet: String, shapes: List<SketchPrimitive>, extra: List<dev.stapler.stelekit.sketch.v2.scene.SceneElement> = emptyList()): ByteArray {
        val props = SheetProps(layers = listOf(SketchLayer("walls")))
        var scene = V1ToV2.lift(sceneOf(shapes), props).getOrNull() ?: error("lift failed")
        for (e in extra) scene = scene.add(e).getOrNull() ?: error("extra element rejected")
        return SheetCodecV2.encode(SketchDocumentV2(scene = scene, sheetUuid = sheet)).getOrNull() ?: error("encode failed")
    }

    /** An ellipse and nothing else a v1 sheet could hold; it projects to nothing, so it is counted as not shown. */
    fun ellipse(id: String): dev.stapler.stelekit.sketch.v2.scene.SceneElement {
        fun u(v: Double) = SceneUnit.of(v).getOrNull() ?: error("unit")
        return EllipseElement(ElementBase(ElementId(id), zKey = ZKey("a900000")), u(100.0), u(100.0), u(40.0), u(20.0))
    }

    fun bundle(convertA: Boolean, extraInA: List<dev.stapler.stelekit.sketch.v2.scene.SceneElement> = emptyList()): ProjectBundle {
        val rowA = sheetRow(SHEET_A, 0)
        val rowB = sheetRow(SHEET_B, 1)
        val base = SketchTestSupport.bundle(sheets = 0)
        val v1Primitives = storedOf(SHEET_B, sheetBShapes) + if (convertA) emptyList() else storedOf(SHEET_A, sheetAShapes)
        val sheets: List<SketchSheet> = listOf(if (convertA) rowA.copy(schemaVersion = SheetPaths.SCHEMA_V2) else rowA, rowB)
        val payloads = if (convertA) mapOf(SheetUuid(SHEET_A) to SheetPayload.V2Opaque.of(v2Bytes(SHEET_A, sheetAShapes, extraInA))) else emptyMap()
        return base.copy(sheets = sheets, primitives = v1Primitives, sheetPayloads = payloads)
    }

    /** A device holding the project in its database and its sidecar folder. */
    suspend fun device(
        convertA: Boolean,
        extraInA: List<dev.stapler.stelekit.sketch.v2.scene.SceneElement> = emptyList(),
        lease: dev.stapler.stelekit.platform.SketchWriterLease = dev.stapler.stelekit.platform.AlwaysHeldSketchWriterLease(),
    ): Device {
        val d = Device(lease = lease)
        val bundle = bundle(convertA, extraInA)
        d.store.write(slug, SidecarCodec.encode(bundle))
        d.repos.projects.replaceProjectContents(SketchTestSupport.P, bundle).getOrNull() ?: error("seed failed")
        return d
    }

    fun documents(d: Device): SketchDocumentSource = SketchDocumentSource(d.repos, SheetProjectionSource { d.store })
}
