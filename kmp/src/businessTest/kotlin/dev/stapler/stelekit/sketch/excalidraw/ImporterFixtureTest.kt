// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.DocPatch
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.importer.MissingImageReason
import dev.stapler.stelekit.sketch.v2.importer.StelekitCustomData
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 5.1.2 over the six Story 0.1.5 fixtures: kinds, geometry, text, bindings (current `fixedPoint` and legacy
 * `focus`/`gap`), z-order, placeholders and the one undoable `ImportScene` command.
 *
 * The fixtures are HAND-AUTHORED from Excalidraw v0.18.1 `types.ts`, NOT real exports (owner Task 0.1.5d is open and
 * `kmp/src/commonTest/resources/excalidraw/real/` does not exist): hand-authored fixtures only, UNMEASURED against real exports.
 */
class ImporterFixtureTest {
    private fun fixture(name: String): ByteArray =
        WiringInputs.dataFile("excalidraw/$name.excalidraw", "kmp/src/commonTest/resources/excalidraw/$name.excalidraw").encodeToByteArray()

    private fun import(name: String): ImportedScene = runBlocking {
        val result = ExcalidrawImporter().import(ImportSource.ofBytes(fixture(name)))
        assertIs<Either.Right<ImportedScene>>(result, "$name: $result")
        result.value
    }

    private inline fun <reified T> ImportedScene.element(id: String): T = assertIs<T>(elements.single { it.id.value == id })

    private fun ImportedScene.asDocument(): SketchDocumentV2 =
        Transaction.begin(SketchDocumentV2()).apply(command()).getOrNull()!!.commit().getOrNull()!!.after

    @Test
    fun shapesText_should_MapKindsGeometryTextAndStyle() {
        println("ImporterFixtureTest: hand-authored fixtures only, UNMEASURED against real exports (Task 0.1.5d open)")
        val scene = import("shapes-text")
        assertEquals(listOf("rect1", "t1", "dia1", "ell1", "t2", "line1"), scene.elements.map { it.id.value })
        assertEquals("Imported 6 of 6.", scene.report.summary())

        val rect = scene.element<RectElement>("rect1")
        assertEquals(listOf(0.0, 0.0, 160.0, 80.0), listOf(rect.x.value, rect.y.value, rect.width.value, rect.height.value))
        assertEquals(RoundnessKind.AdaptiveRadius, rect.base.style.roundness?.kind)
        assertEquals("#a5d8ff", rect.base.style.backgroundColor)

        val label = scene.element<TextElement>("t1")
        assertEquals("Hello", label.text)
        assertEquals(TextAlign.Center, label.textAlign)
        assertEquals(5, label.fontFamily)
        assertEquals(ElementId("rect1"), label.containerId)

        assertIs<DiamondElement>(scene.elements[2])
        val ellipse = scene.element<EllipseElement>("ell1")
        assertEquals(listOf(470.0, 45.0, 70.0, 45.0), listOf(ellipse.cx.value, ellipse.cy.value, ellipse.rx.value, ellipse.ry.value))

        val line = scene.element<LineElement>("line1")
        assertEquals(listOf(0.0 to 240.0, 120.0 to 280.0, 240.0 to 240.0), line.points.map { it.x.value to it.y.value })
        assertEquals("#ffffff", scene.backgroundColor)
    }

    @Test
    fun boundArrows_should_KeepFixedPointArrowheadsAndArrayZOrder() {
        val scene = import("bound-arrows")
        assertEquals(listOf("a", "b", "c", "arr1", "arr2", "elbow1"), scene.elements.map { it.id.value })
        assertTrue(scene.report.zOrderFromArray, "the fixture's indexes a6..a9,a0,a1 conflict with array order, so array order wins")
        val keys = scene.elements.map { it.base.zKey }
        assertEquals(keys.sorted(), keys, "z-keys follow the array")

        val first = scene.element<ArrowElement>("arr1")
        assertEquals(ElementId("a"), first.startBinding?.elementId)
        assertEquals(1.0 to 0.5, first.startBinding?.let { it.fixedX to it.fixedY })
        assertEquals(0.0 to 0.5, first.endBinding?.let { it.fixedX to it.fixedY })
        assertEquals(Arrowhead.None, first.startArrowhead)
        assertEquals(Arrowhead.Arrow, first.endArrowhead)

        val second = scene.element<ArrowElement>("arr2")
        assertEquals(Arrowhead.Circle, second.startArrowhead, "the legacy name `dot` is a circle")
        assertEquals(Arrowhead.Triangle, second.endArrowhead)
        assertTrue(scene.element<ArrowElement>("elbow1").elbowed)
        assertEquals(0, scene.report.legacyBindings)
    }

    @Test
    fun legacyBinding_should_ConvertFocusGapToAFixedPointFromTheEndpoint() {
        val scene = import("legacy-binding")
        val arrow = scene.element<ArrowElement>("larr")
        assertEquals(1.0 to 0.5, arrow.startBinding?.let { it.fixedX to it.fixedY })
        assertEquals(0.0 to 0.5, arrow.endBinding?.let { it.fixedX to it.fixedY })
        assertEquals(2, scene.report.legacyBindings)
    }

    @Test
    fun frameGroup_should_KeepFrameMembershipGroupsAndGreyPlaceholders() {
        val scene = import("frame-group")
        assertEquals(7, scene.elements.size)
        assertEquals("Frame A", scene.element<FrameElement>("fr1").base.name)
        val member = scene.element<RectElement>("g1")
        assertEquals(ElementId("fr1"), member.base.frameId)
        assertEquals(listOf("grp1"), member.base.groupIds.map { it.value })

        for ((id, type) in listOf("mf1" to "magicframe", "emb1" to "embeddable", "ifr1" to "iframe")) {
            val placeholder = scene.element<RectElement>(id)
            assertEquals(JsonPrimitive(type), placeholder.base.extras[StelekitCustomData.EXTRA_UNSUPPORTED_TYPE])
            assertEquals("#e9ecef", placeholder.base.style.backgroundColor)
        }
        assertEquals(mapOf("magicframe" to 1, "embeddable" to 1, "iframe" to 1), scene.report.placeholders)
        assertEquals("Imported 7 of 7. Kept as grey placeholders: 1 magicframe, 1 embeddable, 1 iframe.", scene.report.summary())
    }

    @Test
    fun image_should_StoreOnePictureByHashAndKeepFlipAndCrop() {
        val scene = import("image")
        val one = scene.element<ImageElement>("img1")
        val two = scene.element<ImageElement>("img2")
        assertNotNull(one.fileId)
        assertEquals(one.fileId, two.fileId, "both elements share file1, so one hash")
        assertEquals(64, one.fileId!!.length)
        assertEquals(1, scene.images.size)
        assertEquals(one.fileId, scene.images.single().fileId)
        assertEquals(listOf(false, true), listOf(one.flipX, two.flipX))
        assertEquals(null, one.crop)
        assertEquals(1.0, two.crop?.width)
        assertTrue(scene.report.missingImages.isEmpty())
    }

    @Test
    fun freedraw_should_KeepPressuresOnlyWhenTheyMatchThePoints() {
        val scene = import("freedraw")
        val measured = scene.element<FreedrawElement>("fd1")
        assertEquals(12, measured.points.size)
        assertEquals(12, measured.pressures.size)
        assertEquals(false, measured.simulatePressure)
        val simulated = scene.element<FreedrawElement>("fd2")
        assertEquals(emptyList(), simulated.pressures)
        assertTrue(simulated.simulatePressure)
    }

    @Test
    fun everyFixture_should_ImportIntoACleanReconciledSceneInOneUndoStep() {
        for (name in listOf("shapes-text", "bound-arrows", "frame-group", "image", "freedraw", "legacy-binding")) {
            val imported = import(name)
            val committed = Transaction.begin(SketchDocumentV2()).apply(imported.command()).getOrNull()!!.commit().getOrNull()!!
            assertEquals(imported.elements.size, committed.after.scene.size, name)
            assertTrue(reconcile(committed.after.scene).isClean, "$name: reconcile of the imported scene repairs nothing")
            assertTrue(committed.after.scene.elementsInOrder.all { it.base.ordinal > 0 }, "$name: every element got an ordinal")
            assertEquals(SketchDocumentV2().scene, committed.patch.backward(committed.after).getOrNull()!!.scene, "$name: one undo removes the import")
        }
    }

    @Test
    fun boundArrows_should_KeepReciprocalBindingsThroughImport() {
        val doc = import("bound-arrows").asDocument()
        val a = doc.scene[ElementId("a")] as RectElement
        val b = doc.scene[ElementId("b")] as RectElement
        assertEquals(listOf("arr1"), a.boundElements.map { it.id.value })
        assertEquals(setOf("arr1", "arr2"), b.boundElements.map { it.id.value }.toSet())
    }

    @Test
    fun importingTwice_should_GiveTheSecondCopyFreshIdsAndRemapItsBindings() {
        val first = import("bound-arrows")
        val second = import("bound-arrows")
        val once = first.asDocument()
        val twice = Transaction.begin(once).apply(second.command()).getOrNull()!!.commit().getOrNull()!!.after
        assertEquals(12, twice.scene.size)
        assertEquals(12, twice.scene.order.toSet().size)
        val copy = twice.scene.elementsInOrder.filterIsInstance<ArrowElement>().single { it.id.value.startsWith("arr1~") }
        val target = copy.startBinding!!.elementId
        assertNotEquals(ElementId("a"), target, "the copy binds to the copied rectangle, not the original")
        assertTrue(target.value.startsWith("a~"))
        assertTrue(reconcile(twice.scene).isClean)
    }

    @Test
    fun patchOfTheImport_should_NotApplyToAChangedDocument() {
        val doc = import("shapes-text").asDocument()
        val patch = DocPatch.diff(SketchDocumentV2(), doc)
        assertTrue(patch.forward(SketchDocumentV2()).isRight())
        assertTrue(patch.forward(doc).isLeft())
    }
}
