// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AcceptAllProvenance
import dev.stapler.stelekit.sketch.v2.commands.AcceptProvenance
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Provenance
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.unreviewed
import dev.stapler.stelekit.sketch.v2.scene.withProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E8: the additive `extras.provenance` marker, its codec, and the accept commands with undo. */
class ProvenanceTest {
    private fun rect(id: String, x: Int, provenance: Provenance? = null) = RectElement(
        ElementBase(ElementId(id), zKey = ZKey("a$x")).let { if (provenance == null) it else it.withProvenance(provenance) },
        SceneUnit.ofInt(x), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
    )

    private fun docOf(vararg els: RectElement) =
        SketchDocumentV2(els.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }, sheetUuid = "sheet-1")

    private fun bytes(doc: SketchDocumentV2) = SheetCodecV2.encode(doc).getOrNull()!!

    @Test
    fun codec_should_LeaveASheetWithoutProvenanceByteIdentical_When_ItRoundTrips() {
        val old = docOf(rect("a", 0), rect("b", 20))
        val first = bytes(old)
        assertFalse(first.decodeToString().contains("provenance"))
        assertTrue(first.contentEquals(bytes(SheetCodecV2.decode(first).getOrNull()!!)))
    }

    @Test
    fun codec_should_RoundTripTheMarker_AndGoBackToTheOldBytes_When_ItIsRemoved() {
        val plain = docOf(rect("a", 0), rect("b", 20))
        val marked = docOf(rect("a", 0, Provenance(confidence = 70, note = "read off the tape")), rect("b", 20))
        val encoded = bytes(marked)
        assertTrue(encoded.decodeToString().contains("\"provenance\""))
        val decoded = SheetCodecV2.decode(encoded).getOrNull()!!
        assertEquals(Provenance(Provenance.AGENT, 70, "read off the tape"), decoded.scene[ElementId("a")]!!.base.provenance)
        assertNull(decoded.scene[ElementId("b")]!!.base.provenance)
        assertTrue(encoded.contentEquals(bytes(decoded)), "re-encoding keeps the bytes")
        val cleared = decoded.scene[ElementId("a")]!!.let { it.withBase(it.base.withProvenance(null)) }
        assertTrue(bytes(plain).contentEquals(bytes(decoded.copy(scene = decoded.scene.replace(cleared).getOrNull()!!))), "removing the marker restores the old bytes")
    }

    @Test
    fun reader_should_IgnoreAMarkerOfAnUnknownShape_When_ExtrasAreNotOurs() {
        assertNull(Provenance.read(JsonObject(mapOf(Provenance.KEY to JsonPrimitive("agent")))))
        assertNull(Provenance.read(JsonObject(mapOf(Provenance.KEY to JsonObject(mapOf("source" to JsonPrimitive("agent"), "confidence" to JsonPrimitive(900)))))))
    }

    @Test
    fun acceptAll_should_MarkOnlyAgentElementsAccepted_AndUndoRestoresThem() {
        val doc = docOf(rect("human", 0), rect("ai1", 20, Provenance()), rect("ai2", 40, Provenance(confidence = 50)))
        assertEquals(listOf("ai1", "ai2"), doc.scene.unreviewed().map { it.id.value })
        val committed = Transaction.begin(doc).apply(AcceptAllProvenance).getOrNull()!!.commit().getOrNull()!!
        val history = HistoryV2.of(doc).commit(committed).getOrNull()!!
        assertTrue(history.present.scene.unreviewed().isEmpty())
        assertTrue(history.present.scene[ElementId("ai1")]!!.base.provenance!!.accepted, "the marker stays, so the sheet still says an agent wrote it")
        assertNull(history.present.scene[ElementId("human")]!!.base.provenance)
        assertEquals(doc, history.undo().present, "one undo step brings every ghost back")
        assertEquals(history.present, history.undo().redo().present)
    }

    @Test
    fun accept_should_RefuseWithNoChange_When_NothingNeedsReview() {
        val doc = docOf(rect("human", 0), rect("done", 20, Provenance(accepted = true)))
        assertEquals(CommandError.NoChange, (AcceptAllProvenance.apply(doc) as arrow.core.Either.Left).value)
        assertEquals(CommandError.NoChange, (AcceptProvenance(listOf(ElementId("human"), ElementId("done"))).apply(doc) as arrow.core.Either.Left).value)
        assertEquals(CommandError.UnknownElement("nope"), (AcceptProvenance(listOf(ElementId("nope"))).apply(doc) as arrow.core.Either.Left).value)
    }

    @Test
    fun acceptAll_should_SkipElementsOnHiddenOrLockedLayers() {
        val base = docOf(rect("ai1", 0, Provenance()), rect("ai2", 20, Provenance()))
        val hiddenLayer = dev.stapler.stelekit.sketch.v2.scene.Layer(dev.stapler.stelekit.sketch.v2.scene.LayerId("h"), "H", visible = false, order = 1)
        val scene = base.scene.withLayers(base.scene.layers + hiddenLayer)
        val ai2 = scene[ElementId("ai2")]!!
        val moved = scene.replace(ai2.withBase(ai2.base.copy(layerId = hiddenLayer.id))).getOrNull()!!
        val after = (AcceptAllProvenance.apply(base.copy(scene = moved)) as arrow.core.Either.Right).value.doc
        assertEquals(listOf("ai2"), after.scene.unreviewed().map { it.id.value })
    }

    @Test
    fun accept_should_TouchOnlyTheNamedElements() {
        val doc = docOf(rect("ai1", 0, Provenance()), rect("ai2", 20, Provenance()))
        val after = (AcceptProvenance(listOf(ElementId("ai1"))).apply(doc) as arrow.core.Either.Right).value.doc
        assertEquals(listOf("ai2"), after.scene.unreviewed().map { it.id.value })
    }
}
