// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleArrowType
import dev.stapler.stelekit.sketch.v2.commands.StyleArrowheads
import dev.stapler.stelekit.sketch.v2.commands.StyleText
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.arrowType
import dev.stapler.stelekit.sketch.v2.scene.withArrowType
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.StyleActions
import dev.stapler.stelekit.ui.sketch.v2.StylePanelModel
import dev.stapler.stelekit.ui.sketch.v2.StyleProperty
import dev.stapler.stelekit.ui.sketch.v2.StyleValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks 3.3.1b and 3.5.4c (FB-SP-10 to FB-SP-14): text alignment, arrowheads and the arrow type, one undoable command each. */
class StyleTypeCommandsTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun arrow(id: String, type: ArrowType = ArrowType.Sharp) =
        ArrowElement(ElementBase(ElementId(id)), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(10), u(0)))).withArrowType(type)

    private fun text(id: String) = TextElement(ElementBase(ElementId(id)), u(0), u(20), u(40), u(20), text = "t")

    private fun doc(vararg elements: SceneElement) =
        SketchDocumentV2(elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") })

    private fun run(doc: SketchDocumentV2, command: Command) = command.apply(doc).getOrNull() ?: error("command refused")

    @Test
    fun arrowType_should_SetRoundnessForRound_ClearItForSharp_AndFlagElbow() {
        val start = doc(arrow("a"))
        val round = run(start, StyleArrowType(listOf(ElementId("a")), ArrowType.Round)).doc
        val roundArrow = assertIs<ArrowElement>(round.scene[ElementId("a")])
        assertEquals(Roundness(RoundnessKind.ProportionalRadius), roundArrow.base.style.roundness)
        assertEquals(ArrowType.Round, roundArrow.arrowType)

        val elbow = assertIs<ArrowElement>(run(round, StyleArrowType(listOf(ElementId("a")), ArrowType.Elbow)).doc.scene[ElementId("a")])
        assertTrue(elbow.elbowed)
        assertNull(elbow.base.style.roundness)
        assertEquals(ArrowType.Elbow, elbow.arrowType)

        val sharp = assertIs<ArrowElement>(run(round, StyleArrowType(listOf(ElementId("a")), ArrowType.Sharp)).doc.scene[ElementId("a")])
        assertEquals(ArrowType.Sharp, sharp.arrowType)
    }

    @Test
    fun arrowType_should_KeepTheRoundnessItAlreadyHad_AndSkipOtherKinds() {
        val custom = Roundness(RoundnessKind.AdaptiveRadius, 12.0)
        val styled = arrow("a").let { it.copy(base = it.base.copy(style = ElementStyle(roundness = custom))) }
        assertEquals(custom, styled.withArrowType(ArrowType.Round).base.style.roundness)
        val applied = run(doc(styled, text("t")), StyleArrowType(listOf(ElementId("a"), ElementId("t")), ArrowType.Elbow))
        assertEquals(setOf(ElementId("a")), applied.changedIds)
    }

    @Test
    fun arrowType_should_ChangeNothing_WhenAlreadyThatType_AndRefuseAnUnknownId() {
        assertTrue(run(doc(arrow("a")), StyleArrowType(listOf(ElementId("a")), ArrowType.Sharp)).changedIds.isEmpty())
        assertIs<CommandError.UnknownElement>(StyleArrowType(listOf(ElementId("zz")), ArrowType.Round).apply(doc(arrow("a"))).leftOrNull())
    }

    @Test
    fun verticalAlign_should_SetOnlyTextElements() {
        val applied = run(doc(text("t"), arrow("a")), StyleText(listOf(ElementId("t"), ElementId("a")), verticalAlign = VerticalAlign.Bottom))
        assertEquals(VerticalAlign.Bottom, assertIs<TextElement>(applied.doc.scene[ElementId("t")]).verticalAlign)
        assertEquals(setOf(ElementId("t")), applied.changedIds)
    }

    @Test
    fun textAlign_should_KeepVerticalAlignAndFontFields() {
        val applied = run(doc(text("t")), StyleText(listOf(ElementId("t")), textAlign = TextAlign.Right))
        val t = assertIs<TextElement>(applied.doc.scene[ElementId("t")])
        assertEquals(TextAlign.Right, t.textAlign)
        assertEquals(VerticalAlign.Top, t.verticalAlign)
    }

    @Test
    fun arrowheads_should_SetOneSideAtATime() {
        val first = run(doc(arrow("a")), StyleArrowheads(listOf(ElementId("a")), start = Arrowhead.Circle))
        val second = run(first.doc, StyleArrowheads(listOf(ElementId("a")), end = Arrowhead.Triangle))
        val a = assertIs<ArrowElement>(second.doc.scene[ElementId("a")])
        assertEquals(Arrowhead.Circle, a.startArrowhead)
        assertEquals(Arrowhead.Triangle, a.endArrowhead)
    }

    @Test
    fun panelModel_should_ShowArrowTypeAndVerticalAlignOnlyForTheKindsThatHaveThem() {
        val d = doc(arrow("a"), arrow("b", ArrowType.Round), text("t"), RectElement(ElementBase(ElementId("r")), u(0), u(0), u(5), u(5)))
        val scene = d.scene
        fun model(vararg ids: String) = StylePanelModel.of(ids.map { checkNotNull(scene[ElementId(it)]) }, ElementStyle(), ToolId.Select, scene)

        assertNull(model("r").arrowType)
        assertNull(model("r").verticalAlign)
        assertEquals(StyleValue.Same(ArrowType.Sharp), model("a").arrowType)
        assertEquals(StyleValue.Mixed, model("a", "b").arrowType)
        assertEquals(StyleValue.Same(VerticalAlign.Top), model("t").verticalAlign)
        assertEquals(StyleValue.Same(ArrowType.Round), model("b", "r").arrowType)
        assertTrue(StyleProperty.entries.containsAll(listOf(StyleProperty.ArrowType, StyleProperty.VerticalAlign)))
    }

    @Test
    fun styleActions_should_BuildTheSameCommandsThePanelSends() {
        val ids = listOf(ElementId("a"))
        assertEquals(StyleArrowType(ids, ArrowType.Elbow), StyleActions.arrowType(ids, ArrowType.Elbow))
        assertEquals(StyleText(ids, verticalAlign = VerticalAlign.Middle), StyleActions.verticalAlign(ids, VerticalAlign.Middle))
        assertEquals(StyleArrowheads(ids, Arrowhead.Bar, null), StyleActions.arrowheads(ids, Arrowhead.Bar, null))
    }
}
