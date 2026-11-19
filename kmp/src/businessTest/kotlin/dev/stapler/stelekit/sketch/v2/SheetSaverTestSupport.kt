// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarReconciler
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/** A saver over a real [Device] (database plus sidecar folder) holding sheet A of [MixedProjectFixtures] as a v2 file. */
internal class SaverRig(
    val device: Device,
    context: CoroutineContext,
    val slugLock: SidecarSlugLock = SidecarSlugLock(),
    externalChanges: Flow<Unit> = emptyFlow(),
    signals: QualitySignals? = null,
    storeOf: (dev.stapler.stelekit.projectbridge.ProjectSidecarStore) -> dev.stapler.stelekit.projectbridge.ProjectSidecarStore = { it },
) {
    val sheet = SheetUuid(MixedProjectFixtures.SHEET_A)
    val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)
    val logLines = mutableListOf<String>()
    val diagnostics = InMemoryDiagnosticsPort(FixedStrings)
    val reconciler = SidecarReconciler(device.repos, device.store, null, slugLock)
    val loadedBytes: ByteArray = MixedProjectFixtures.v2Bytes(MixedProjectFixtures.SHEET_A, MixedProjectFixtures.sheetAShapes)
    val saver = SheetSaver(
        SheetTarget(MixedProjectFixtures.slug, SketchTestSupport.P, sheet, ContentHasher.sha256(loadedBytes)),
        SheetSaverPorts(
            device.repos, storeOf(device.store), slugLock, diagnostics,
            log = SketchLog(SketchLogSink { logLines += it }, signals = signals),
            externalChanges = externalChanges,
        ),
        context = context,
        encodeContext = context,
    )

    fun diskBytes(): ByteArray = device.fs.readFileBytes(MixedProjectFixtures.sidecarFile(path))!!

    fun loadedDoc(): SketchDocumentV2 = SheetCodecV2.decode(loadedBytes).getOrNull()!!

    fun rows() = diagnostics.diagnostics.value.entries.map { it.row }

    companion object {
        fun rect(id: String, x: Int) = RectElement(
            ElementBase(ElementId(id), zKey = ZKey("a9$id")),
            SceneUnit.ofInt(x), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
        )

        /** [doc] plus one more rectangle, so every call with a distinct [id] is a distinct scene. */
        fun withRect(doc: SketchDocumentV2, id: String, x: Int): SketchDocumentV2 =
            doc.copy(scene = doc.scene.add(rect(id, x)).getOrNull()!!)
    }
}

private object FixedStrings : BannerStringSource {
    override fun summary(row: BannerRowId) = "Couldn't save."
    override fun shortTitle(row: BannerRowId) = "Not saved"
    override fun primaryLabel(row: BannerRowId) = "Retry"
    override fun moreItems(row: BannerRowId) = emptyList<String>()
}
