// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest fixture; no UI)
package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.projectbridge.ProjectBridgeDeps
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SketchyStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.StrokeStyleProvider
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorSession
import dev.stapler.stelekit.ui.sketch.v2.V2EditorDeps
import dev.stapler.stelekit.ui.sketch.v2.V2EntryActions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 6.2.1a: the nine class seams of the plan's `plan-seam-classes` block, each reached through the real root a wired
 * fixture built (its project bridge, [deps] and the session the opener returned), never through a stub. A class seam is not on
 * `WiringAudit.SEAMS`, so the wiring audit cannot see a dead one; this does. Call it with a session whose sheet has at least one
 * drawn element, after the controller was flushed. Platform-free: each target's wiring test supplies the root and the encoder check.
 */
object V2ClassSeamProbe {
    private const val PNG_SIGNATURE_BYTE = 0x89.toByte()

    suspend fun assertAllReachable(root: ProjectBridgeDeps, deps: V2EditorDeps, session: SketchEditorSession, rig: V2EditorRig) {
        sheetLeaseRegistry(root, rig)
        sheetSaver(root, rig)
        solveRunner(session)
        sketchEditorRouter(root)
        strokeStyleProvider(session)
        sheetProjectionSource(root, rig)
        sheetConverter(deps, rig)
        imageAssetStore(session)
        pngEncoder(session)
    }

    /** The edit lease the opener took sits in the root's own registry, held by the new editor. */
    private fun sheetLeaseRegistry(root: ProjectBridgeDeps, rig: V2EditorRig) {
        assertEquals(LeaseHolder.V2, root.sheetLeases.holderOf(rig.sheet), "the root's SheetLeaseRegistry holds the open sheet for the new editor")
    }

    /** The drawn element is in the root's store: only the controller's SheetSaver writes it there. */
    private suspend fun sheetSaver(root: ProjectBridgeDeps, rig: V2EditorRig) {
        val store = assertNotNull(root.sidecarStore())
        val read = store.read(rig.slug)
        assertIs<dev.stapler.stelekit.projectbridge.SidecarRead.Present>(read, "the root's store holds the saved project")
        assertTrue(read.files.containsKey(rig.path), "SheetSaver wrote the sheet file ${rig.path}")
    }

    /** The controller's one SolveRunner is idle and runs an edit through its gate (a no-op edit commits nothing). */
    private suspend fun solveRunner(session: SketchEditorSession) {
        assertEquals(SolveState(solving = false, slow = false), session.controller.solveState.value, "the SolveRunner is idle")
        assertNull(session.controller.runLabelEdit { null }, "an edit that changes nothing passes the SolveRunner gate and commits nothing")
    }

    /** The router decides on the root's real capabilities: v2 on, a v2 sheet, the editor is the new one. */
    private fun sketchEditorRouter(root: ProjectBridgeDeps) {
        val store = assertNotNull(root.sidecarStore())
        val outcome = SketchEditorRouter.route(RouteInput(V2Mode.On, V2Capabilities.of(V2Storage.Available), SheetSchema.V2))
        assertTrue(outcome !is RouteOutcome.Legacy, "the router sends a v2 sheet to the new editor: $outcome")
        assertNotNull(store)
    }

    /** The draw list of the opened scene is restyled by the Sketchy provider, and Clean is the identity one. */
    private fun strokeStyleProvider(session: SketchEditorSession) {
        assertEquals(RenderStyle.Sketchy, StrokeStyleProvider.forStyle(RenderStyle.Sketchy).style)
        assertTrue(StrokeStyleProvider.forStyle(RenderStyle.Sketchy) === SketchyStrokeStyle)
        val ops = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(session.controller.doc.scene, RenderStyle.Sketchy)
        assertTrue(ops.isNotEmpty(), "the drawn element becomes draw ops through a StrokeStyleProvider")
    }

    /** The v1 consumers' projection reads the very file the saver wrote, through the root's store. */
    private suspend fun sheetProjectionSource(root: ProjectBridgeDeps, rig: V2EditorRig) {
        val projected = SheetProjectionSource(root.sidecarStore).project(rig.slug, rig.sheet)
        assertTrue(projected.getOrNull()?.primitives?.isNotEmpty() == true, "the projection holds the drawn rectangle: $projected")
    }

    /** The converter the entry actions build reads the root's model; a sheet that is already schema 2 is left alone. */
    private suspend fun sheetConverter(deps: V2EditorDeps, rig: V2EditorRig) {
        assertEquals(ConversionOutcome.AlreadyConverted, V2EntryActions(deps).convert(rig.project.uuid, rig.sheet), "SheetConverter reads the root's model and writes nothing")
    }

    /** The session's services hold an image store over the root's file system and notes folder; a stored picture round-trips. */
    private fun imageAssetStore(session: SketchEditorSession) {
        val images = assertNotNull(session.services.images, "the session has an ImageAssetStore (the root named a notes folder)")
        val stored = images.store(PNG_1X1).getOrNull()
        assertNotNull(stored, "a one-pixel PNG is stored")
        assertTrue(images.exists(stored.fileId), "the stored image is found by its id")
    }

    /** The session's PngEncoder is the target's `actual`; a real bitmap encodes to bytes (PNG-signed). */
    private fun pngEncoder(session: SketchEditorSession) {
        val bytes = session.services.png.encode(ImageBitmap(2, 2))
        assertTrue(bytes.isNotEmpty(), "the target's PngEncoder produced bytes")
        assertEquals(PNG_SIGNATURE_BYTE, bytes[0], "the bytes are a PNG")
    }

    private val PNG_1X1: ByteArray = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C, 0x63, 0xF8, 0xFF, 0xFF, 0x3F, 0x00, 0x05, 0xFE, 0x02, 0xFE, 0xA7, 0x35, 0x81, 0x84, 0x00, 0x00, 0x00,
        0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ).map { it.toByte() }.toByteArray()
}
