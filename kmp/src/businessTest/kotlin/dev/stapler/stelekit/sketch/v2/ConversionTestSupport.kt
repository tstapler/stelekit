// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.sidecarPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.convert.ProjectAcknowledgements
import dev.stapler.stelekit.sketch.v2.runtime.convert.SheetConverter
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.ui.sketch.SketchDocumentSource
import kotlinx.coroutines.flow.first

/** A project of two v1 sheets with no zones and no dimensions: the case the converter accepts. */
internal object ConversionFixtures {
    const val S1 = "p1-s1"
    const val S2 = "p1-s2"
    const val S1_PRIMITIVES = 12
    val slug = SketchTestSupport.project().slug

    fun file(rel: String): String = sidecarPath(slug, rel)

    private fun line(id: String, sheet: String, z: Int, layer: String) =
        SketchPrimitive(id, SheetUuid(sheet), "line", z * 16L, 0, z * 16L + 160, 480, layer = layer, z = z.toLong())

    private fun rect(id: String, sheet: String, z: Int) =
        SketchPrimitive(id, SheetUuid(sheet), "rect", 880, 400, 560, 320, layer = "", z = z.toLong())

    fun bundle(): ProjectBundle {
        val s1 = (0 until S1_PRIMITIVES - 1).map { line("s1-l$it", S1, it, "walls") } + rect("s1-r", S1, S1_PRIMITIVES - 1)
        val s2 = listOf(line("s2-l0", S2, 0, "walls"), rect("s2-r0", S2, 1))
        val base = SketchTestSupport.bundle(sheets = 0)
        return base.copy(
            sheets = listOf(SketchTestSupport.sheet(S1, position = 0), SketchTestSupport.sheet(S2, position = 1)),
            primitives = s1 + s2,
            dimensions = emptyList(),
        )
    }

    suspend fun device(bundle: ProjectBundle = bundle()): Device {
        val d = Device()
        d.store.write(slug, SidecarCodec.encode(bundle))
        d.repos.projects.replaceProjectContents(SketchTestSupport.P, bundle).getOrNull() ?: error("seed failed")
        d.repos.projects.loadProject(SketchTestSupport.P).getOrNull()?.project?.let { fresh ->
            val hash = SidecarCodec.bundleHash(SidecarCodec.encode(bundle))
            d.repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision))
        }
        return d
    }

    fun converter(
        d: Device,
        ready: Boolean = true,
        acknowledged: Boolean = true,
        technicalDataEditable: Boolean = false,
        crashPoint: ConversionCrashPoint = ConversionCrashPoint.None,
    ) = SheetConverter(
        d.repos, d.store, SidecarSlugLock(), ProjectAcknowledgements { acknowledged }, { ready },
        technicalDataEditable = technicalDataEditable, crashPoint = crashPoint,
    )

    fun documents(d: Device): SketchDocumentSource = SketchDocumentSource(d.repos, SheetProjectionSource { d.store })

    /** Every sidecar file (no temps) as path to bytes hash, the shape a test compares for "identical final state". */
    fun disk(d: Device): Map<String, String> = d.fs.allFilePaths()
        .filter { it.contains("/assets/stelekit-sketch/") && !it.endsWith(".tmp") }
        .associate { it.substringAfter("/stelekit-sketch/") to dev.stapler.stelekit.util.ContentHasher.sha256(d.fs.readFileBytes(it)!!) }

    suspend fun schemas(d: Device): Map<String, Long> =
        d.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.associate { it.uuid.value to it.schemaVersion }
}
