// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.clipboard.SceneClipboard
import dev.stapler.stelekit.sketch.v2.commands.AlignEdge
import dev.stapler.stelekit.sketch.v2.commands.AlignElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DistributeAxis
import dev.stapler.stelekit.sketch.v2.commands.DistributeElements
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.FlipElements
import dev.stapler.stelekit.sketch.v2.commands.GroupElements
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.ReorderElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.commands.UngroupElements
import dev.stapler.stelekit.sketch.v2.commands.ZOrderMove
import dev.stapler.stelekit.sketch.v2.library.ElementLibrary
import dev.stapler.stelekit.sketch.v2.library.LibraryCodec
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.ui.sketch.v2.EdgesChoice
import dev.stapler.stelekit.ui.sketch.v2.FontChoice
import dev.stapler.stelekit.ui.sketch.v2.FontSizeChoice
import dev.stapler.stelekit.ui.sketch.v2.NamedColor
import dev.stapler.stelekit.ui.sketch.v2.SloppinessChoice
import dev.stapler.stelekit.ui.sketch.v2.StrokeWidthChoice
import dev.stapler.stelekit.ui.sketch.v2.StyleActions
import dev.stapler.stelekit.ui.sketch.v2.StyleProperty

/** The four groups of the Excalidraw 0.18.1 baseline (requirements.md, "Excalidraw feature baseline", D-22). */
enum class BaselineKind { Style, Arrange, Align, Library }

/**
 * One counted baseline feature. [target] is the command or control that implements it (a [StyleProperty], an arrange
 * action or command class, an align edge or axis, a library operation); [test] is the existing test class that covers it;
 * [exercise] is the scenario the completeness test runs, so a feature with no working implementation fails by name.
 */
class BaselineFeature(
    val id: String,
    val name: String,
    val kind: BaselineKind,
    val target: String,
    val test: String,
    val exercise: suspend (BaselineFixture) -> Boolean,
)

/** A small document every baseline scenario runs on: three rectangles in a row, a text, an arrow and a saved library. */
class BaselineFixture {
    private var next = 0

    val ids = ElementIdSource { ElementId("n${next++}") }

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun rect(id: String, x: Int, groups: List<GroupId> = emptyList()) =
        RectElement(ElementBase(ElementId(id), groupIds = groups), u(x), u(0), u(10), u(10))

    val rects: List<ElementId> = listOf(ElementId("r1"), ElementId("r2"), ElementId("r3"))
    val text = ElementId("t1")
    val arrow = ElementId("a1")
    val everything: List<ElementId> = rects + text + arrow

    /** r1 (x 0), r2 (x 30), r3 (x 100); r1 and r2 share a group so Ungroup has work to do. */
    val doc: SketchDocumentV2 = SketchDocumentV2(
        listOf(
            rect("r1", 0, listOf(GroupId("g"))), rect("r2", 30, listOf(GroupId("g"))), rect("r3", 100),
            TextElement(ElementBase(text), u(0), u(30), u(40), u(20), text = "label"),
            ArrowElement(ElementBase(arrow), listOf(ScenePoint(u(0), u(60)), ScenePoint(u(50), u(60)))),
        ).fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("fixture add ${e.id}") },
    )

    /** True when [command] applies to [doc] and touches at least one element. */
    fun changes(command: Command): Boolean = command.apply(doc).getOrNull()?.changedIds?.isNotEmpty() == true

    fun style(patch: StylePatch) = changes(StyleElements(everything, patch))
}

private val red = NamedColor("#e03131", "Red 600")

/** The 41 ids, in the order of requirements.md. Changing this list means changing that section too. */
object FeatureBaseline {
    val features: List<BaselineFeature> = style() + arrange() + align() + library()

    private fun style() = listOf(
        f("FB-SP-01", "Stroke colour", BaselineKind.Style, StyleProperty.StrokeColor, "StylePanelModelTest") { it.style(StyleActions.strokeColor(red)) },
        f("FB-SP-02", "Background colour", BaselineKind.Style, StyleProperty.BackgroundColor, "StylePanelModelTest") { it.style(StyleActions.backgroundColor(red)) },
        f("FB-SP-03", "Fill style", BaselineKind.Style, StyleProperty.FillStyle, "StylePanelModelTest") { it.style(StyleActions.fillStyle(FillStyle.CrossHatch)) },
        f("FB-SP-04", "Stroke width", BaselineKind.Style, StyleProperty.StrokeWidth, "StylePanelModelTest") { it.style(StyleActions.strokeWidth(StrokeWidthChoice.ExtraBold)) },
        f("FB-SP-05", "Stroke style", BaselineKind.Style, StyleProperty.StrokeStyle, "StylePanelModelTest") { it.style(StyleActions.strokeStyle(StrokeStyle.Dashed)) },
        f("FB-SP-06", "Sloppiness", BaselineKind.Style, StyleProperty.Sloppiness, "StylePanelModelTest") { it.style(StyleActions.sloppiness(SloppinessChoice.Clean)) },
        f("FB-SP-07", "Edges", BaselineKind.Style, StyleProperty.Edges, "StylePanelModelTest") { it.style(StyleActions.edges(EdgesChoice.Round)) },
        f("FB-SP-08", "Font family", BaselineKind.Style, StyleProperty.FontFamily, "StylePanelModelTest") { it.changes(StyleActions.font(listOf(it.text), FontChoice.Code)) },
        f("FB-SP-09", "Font size", BaselineKind.Style, StyleProperty.FontSize, "StylePanelModelTest") { it.changes(StyleActions.fontSize(listOf(it.text), FontSizeChoice.XL)) },
        f("FB-SP-10", "Text align", BaselineKind.Style, StyleProperty.TextAlign, "StyleTypeCommandsTest") { it.changes(StyleActions.textAlign(listOf(it.text), TextAlign.Center)) },
        f("FB-SP-11", "Vertical align", BaselineKind.Style, StyleProperty.VerticalAlign, "StyleTypeCommandsTest") { it.changes(StyleActions.verticalAlign(listOf(it.text), VerticalAlign.Middle)) },
        f("FB-SP-12", "Arrowhead start", BaselineKind.Style, StyleProperty.ArrowheadStart, "StyleTypeCommandsTest") { it.changes(StyleActions.arrowheads(listOf(it.arrow), Arrowhead.Circle, null)) },
        f("FB-SP-13", "Arrowhead end", BaselineKind.Style, StyleProperty.ArrowheadEnd, "StyleTypeCommandsTest") { it.changes(StyleActions.arrowheads(listOf(it.arrow), null, Arrowhead.Triangle)) },
        f("FB-SP-14", "Arrow type", BaselineKind.Style, StyleProperty.ArrowType, "StyleTypeCommandsTest") { it.changes(StyleActions.arrowType(listOf(it.arrow), ArrowType.Round)) },
        f("FB-SP-15", "Opacity", BaselineKind.Style, StyleProperty.Opacity, "StylePanelModelTest") { it.style(StyleActions.opacity(50)) },
    )

    private fun arrange() = listOf(
        f("FB-AR-01", "Send backward", BaselineKind.Arrange, "SendBackward", "ElementCommandsTest") { it.changes(ReorderElements(listOf(it.rects[2]), ZOrderMove.Backward)) },
        f("FB-AR-02", "Bring forward", BaselineKind.Arrange, "BringForward", "ElementCommandsTest") { it.changes(ReorderElements(listOf(it.rects[0]), ZOrderMove.Forward)) },
        f("FB-AR-03", "Send to back", BaselineKind.Arrange, "SendToBack", "ElementCommandsTest") { it.changes(ReorderElements(listOf(it.arrow), ZOrderMove.ToBack)) },
        f("FB-AR-04", "Bring to front", BaselineKind.Arrange, "BringToFront", "ElementCommandsTest") { it.changes(ReorderElements(listOf(it.rects[0]), ZOrderMove.ToFront)) },
        f("FB-AR-05", "Group", BaselineKind.Arrange, "Group", "GroupFrameTest") { it.changes(GroupElements(listOf(it.rects[2], it.text), GroupId("g2"))) },
        f("FB-AR-06", "Ungroup", BaselineKind.Arrange, "Ungroup", "GroupFrameTest") { it.changes(UngroupElements(listOf(it.rects[0]))) },
        f("FB-AR-07", "Lock and unlock", BaselineKind.Arrange, "LockElements", "ElementCommandsTest") { it.changes(LockElements(it.everything, true)) },
        f("FB-AR-08", "Duplicate", BaselineKind.Arrange, "DuplicateElements", "ElementCommandsTest") {
            it.changes(DuplicateElements(listOf(it.rects[2]), mapOf(it.rects[2] to ElementId("dup")), 10.0, 10.0))
        },
        f("FB-AR-09", "Delete", BaselineKind.Arrange, "DeleteElements", "ElementCommandsTest") { it.changes(DeleteElements(listOf(it.rects[2]))) },
        f("FB-AR-10", "Copy styles", BaselineKind.Arrange, "SceneClipboard.copyStyle", "AlignCommandsTest") {
            SceneClipboard.copyStyle(it.doc.scene[it.rects[0]] ?: error("fixture")).strokeColor != null
        },
        f("FB-AR-11", "Paste styles", BaselineKind.Arrange, "SceneClipboard.pasteStyle", "AlignCommandsTest") {
            it.changes(SceneClipboard.pasteStyle(StyleActions.strokeColor(red), listOf(it.rects[2])))
        },
        f("FB-AR-12", "Flip horizontal", BaselineKind.Arrange, "FlipElements.horizontal", "SelectionCommandsTest") { it.changes(FlipElements(listOf(it.rects[0], it.rects[2]), horizontal = true)) },
        f("FB-AR-13", "Flip vertical", BaselineKind.Arrange, "FlipElements.vertical", "SelectionCommandsTest") { it.changes(FlipElements(listOf(it.rects[0], it.text), horizontal = false)) },
    )

    private fun align() = listOf(
        f("FB-AL-01", "Align left", BaselineKind.Align, "Left", "AlignCommandsTest") { it.changes(AlignElements(it.rects, AlignEdge.Left)) },
        f("FB-AL-02", "Align horizontal centre", BaselineKind.Align, "CenterHorizontal", "AlignCommandsTest") { it.changes(AlignElements(it.rects, AlignEdge.CenterHorizontal)) },
        f("FB-AL-03", "Align right", BaselineKind.Align, "Right", "AlignCommandsTest") { it.changes(AlignElements(it.rects, AlignEdge.Right)) },
        f("FB-AL-04", "Align top", BaselineKind.Align, "Top", "AlignCommandsTest") { it.changes(AlignElements(listOf(it.rects[0], it.text), AlignEdge.Top)) },
        f("FB-AL-05", "Align vertical centre", BaselineKind.Align, "CenterVertical", "AlignCommandsTest") { it.changes(AlignElements(listOf(it.rects[0], it.text), AlignEdge.CenterVertical)) },
        f("FB-AL-06", "Align bottom", BaselineKind.Align, "Bottom", "AlignCommandsTest") { it.changes(AlignElements(listOf(it.rects[0], it.text), AlignEdge.Bottom)) },
        f("FB-AL-07", "Distribute horizontally", BaselineKind.Align, "Horizontal", "AlignCommandsTest") { it.changes(DistributeElements(it.rects, DistributeAxis.Horizontal)) },
        f("FB-AL-08", "Distribute vertically", BaselineKind.Align, "Vertical", "AlignCommandsTest") { it.changes(DistributeElements(listOf(it.rects[0], it.text, it.arrow), DistributeAxis.Vertical)) },
    )

    private fun library() = listOf(
        f("FB-LB-01", "Add selection to the library", BaselineKind.Library, "ElementLibrary.add", "ElementLibraryTest") { it.libraryWithOneItem().items.size == 1 },
        f("FB-LB-02", "Insert an item", BaselineKind.Library, "ElementLibrary.insert", "ElementLibraryTest") {
            val library = it.libraryWithOneItem()
            library.insert(library.items.single().id, it.ids)?.let { cmd -> it.changes(cmd) } == true
        },
        f("FB-LB-03", "Delete an item", BaselineKind.Library, "ElementLibrary.delete", "ElementLibraryTest") {
            val library = it.libraryWithOneItem()
            library.delete(library.items.single().id).items.isEmpty()
        },
        f("FB-LB-04", "Import .excalidrawlib", BaselineKind.Library, "LibraryCodec.decode", "ElementLibraryTest") {
            val text = LibraryCodec.encode(it.libraryWithOneItem().items)
            LibraryCodec.decode(ImportSource.ofBytes(text.encodeToByteArray())).getOrNull()?.items?.size == 1
        },
        f("FB-LB-05", "Export .excalidrawlib", BaselineKind.Library, "LibraryCodec.encode", "ElementLibraryTest") {
            LibraryCodec.encode(it.libraryWithOneItem().items).contains(LibraryCodec.TYPE)
        },
    )

    private fun BaselineFixture.libraryWithOneItem(): ElementLibrary =
        ElementLibrary.empty().add("item", "one", listOf(doc.scene[rects[0]] ?: error("fixture")), created = 1L).getOrNull() ?: error("library refused the fixture item")

    private fun f(id: String, name: String, kind: BaselineKind, target: StyleProperty, test: String, exercise: suspend (BaselineFixture) -> Boolean) =
        BaselineFeature(id, name, kind, target.name, test, exercise)

    private fun f(id: String, name: String, kind: BaselineKind, target: String, test: String, exercise: suspend (BaselineFixture) -> Boolean) =
        BaselineFeature(id, name, kind, target, test, exercise)
}
