// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoad
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoader
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 6.2.0a: the editor controller applies tool commands in one transaction, undoes, saves, reloads and solves through the runner only. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SketchEditorControllerTest {
    private val sheet = SheetUuid("s2")
    private val slug = "proj-0a1b2c"
    private val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)

    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun rect(id: String, x: Int, layer: LayerId = LayerId.Default) =
        RectElement(ElementBase(ElementId(id), layerId = layer, zKey = ZKey("a$id")), u(x), u(0), u(10), u(10))

    private fun line(id: String) = LineElement(
        ElementBase(ElementId(id), zKey = ZKey("a$id")),
        listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(100.0, 0.0)),
    )

    private fun docOf(vararg elements: dev.stapler.stelekit.sketch.v2.scene.SceneElement) =
        AddElements(elements.toList()).apply(SketchDocumentV2(sheetUuid = sheet.value)).fold({ error("fixture: $it") }, { it.doc })

    private fun ids(): ElementIdSource {
        var n = 0
        return ElementIdSource { ElementId("n${n++}") }
    }

    private fun TestScope.controller(initial: SketchDocumentV2, saver: SheetSaver? = null): SketchEditorController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tools = StandardToolRegistry.create(FixedAdvanceTextMetricsProvider())
        return SketchEditorController(
            initial,
            EditorPorts(tools, saver, SolveRunner(context = dispatcher), ids()),
            dispatcher,
        )
    }

    private fun SketchEditorController.drag(x0: Double, y0: Double, x1: Double, y1: Double) {
        var t = 0L
        fun p(phase: RawPhase, x: Double, y: Double) = tools.onPointer(RawPointer(1, PointerKind.Mouse, phase, ScreenPx(x, y), timestamp = t++))
        p(RawPhase.Down, x0, y0)
        p(RawPhase.Move, (x0 + x1) / 2, (y0 + y1) / 2)
        p(RawPhase.Move, x1, y1)
        p(RawPhase.Up, x1, y1)
    }

    private fun SketchEditorController.rects() = doc.scene.elementsInOrder.filterIsInstance<RectElement>()

    @Test
    fun drag_should_DrawOneRectangleAndSelectIt_When_TheRectangleToolIsActive() = runTest {
        val c = controller(docOf())
        c.tools.chooseTool(ToolId.Rectangle)

        c.drag(10.0, 10.0, 110.0, 60.0)

        val drawn = c.rects().single()
        assertEquals(100.0, drawn.width.value)
        assertEquals(50.0, drawn.height.value)
        assertEquals(setOf(drawn.id.value), c.ui.selection)
        assertEquals(ToolId.Select, c.ui.activeTool, "the tool returns to Select after a commit")
        assertEquals(1, c.history.undoDepth, "one gesture is one history step")
        c.close()
    }

    @Test
    fun undoRedo_should_RemoveAndRestoreTheShape() = runTest {
        val c = controller(docOf())
        c.tools.chooseTool(ToolId.Rectangle)
        c.drag(10.0, 10.0, 60.0, 60.0)

        c.undo()
        assertTrue(c.rects().isEmpty())
        assertTrue(c.ui.selection.isEmpty(), "a selection of a vanished element is dropped")
        c.keys.run(KeyAction.Redo)
        assertEquals(1, c.rects().size)
        c.close()
    }

    @Test
    fun savedScene_should_ReloadAsTheSameScene_When_TheDebounceElapsed() = runTest {
        val repos = inMemorySketchRepositories()
        val store = ScriptedSidecarStore()
        val project = SketchProject(ProjectUuid("p1"), slug, "Kitchen", 1L)
        val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
        val initial = docOf()
        val loadedBytes = SheetCodecV2.encode(initial).getOrNull()!!
        repos.projects.replaceProjectContents(
            project.uuid,
            ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(loadedBytes))),
        )
        store.files.putAll(SidecarCodec.encode(ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(loadedBytes)))))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val saver = SheetSaver(
            SheetTarget(slug, project.uuid, sheet, ContentHasher.sha256(loadedBytes)),
            SheetSaverPorts(repos, store, SidecarSlugLock(), InMemoryDiagnosticsPort(dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings.Banners)),
            context = dispatcher,
            encodeContext = dispatcher,
        )
        val c = controller(initial, saver)
        c.tools.chooseTool(ToolId.Rectangle)
        c.drag(10.0, 10.0, 90.0, 40.0)

        advanceUntilIdle()

        assertEquals(SaveState.Saved, c.saves.state.value)
        val reloaded = assertIs<SheetLoad.Loaded>(SheetLoader.load(store, slug, sheet))
        assertEquals(c.doc.scene, reloaded.doc.scene)
        assertContentEquals(SheetCodecV2.encode(c.doc).getOrNull(), store.files.getValue(path))
        c.close()
    }

    @Test
    fun solveOnEdit_should_BeOneUndoStep_When_ALineIsConstrained() = runTest {
        val constrained = docOf(line("L1")).let {
            AddConstraint(Constraint(ConstraintId("h"), ConstraintKind.Horizontal, listOf(ConstraintTarget(ElementId("L1"))))).apply(it).fold({ e -> error("$e") }, { a -> a.doc })
        }
        val c = controller(constrained)
        c.tools.select(listOf(ElementId("L1")))

        c.submit(dev.stapler.stelekit.sketch.v2.commands.SetLinePoints(ElementId("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(50.0, 30.0))))
        advanceUntilIdle()

        val end = (c.doc.scene[ElementId("L1")] as LineElement).points[1]
        assertEquals(0.0, end.y.value, 1e-9, "the solver kept the line horizontal")
        assertEquals(1, c.history.undoDepth, "the edit and its solve are one history entry")
        c.undo()
        assertEquals(constrained.scene, c.doc.scene)
        c.close()
    }

    @Test
    fun lockedElement_should_NotBeSelectedMovedOrDeleted() = runTest {
        val locked = LockElements(listOf(ElementId("r1")), true).apply(docOf(rect("r1", 0))).fold({ error("$it") }, { it.doc })
        val c = controller(locked)

        c.drag(5.0, 5.0, 5.0, 5.0)
        assertTrue(c.ui.selection.isEmpty(), "a click on a locked element selects nothing")
        c.tools.select(listOf(ElementId("r1")))
        c.keys.run(KeyAction.Delete)
        c.keys.run(KeyAction.Nudge(1, 0, far = false))

        assertEquals(locked.scene, c.doc.scene, "a locked element is left as it was")
        assertEquals(0, c.history.undoDepth)
        c.close()
    }

    @Test
    fun hiddenLayer_should_NotBeHitByTheSelectTool() = runTest {
        val layer = LayerId("l2")
        val withLayer = AddLayer(layer, "Second").apply(docOf()).fold({ error("$it") }, { it.doc })
        val doc = AddElements(listOf(rect("r1", 0, layer))).apply(withLayer).fold({ error("$it") }, { it.doc })
        val hidden = SetLayerVisible(layer, false).apply(doc).fold({ error("$it") }, { it.doc })
        val c = controller(hidden)

        c.drag(5.0, 5.0, 5.0, 5.0)

        assertTrue(c.ui.selection.isEmpty())
        c.close()
    }

    @Test
    fun readOnlySheet_should_RefuseEveryEdit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val c = SketchEditorController(
            docOf(rect("r1", 0)),
            EditorPorts(StandardToolRegistry.create(FixedAdvanceTextMetricsProvider()), ids = ids()),
            dispatcher,
            readOnly = true,
        )
        c.tools.chooseTool(ToolId.Rectangle)

        c.drag(10.0, 10.0, 60.0, 60.0)

        assertEquals(1, c.rects().size)
        assertTrue(!c.canUndo)
        c.close()
    }
}
