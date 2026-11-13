// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.ViewTransform
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SketchCanvasGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private val project = ProjectUuid("p1")
    private val repos = inMemorySketchRepositories()
    private var vm: SketchViewModel? = null
    private var lastView: ViewTransform? = null

    @After
    fun tearDown() = vm?.onForgotten() ?: Unit

    private fun startCanvas(modifier: Modifier = Modifier.fillMaxSize()): SketchViewModel {
        runBlocking { repos.projects.saveProject(SketchProject(project, "proj-000001", "Test", 0)) }
        val model = SketchViewModel(repos, project)
        vm = model
        rule.setContent { Host(model, modifier) }
        rule.waitUntil(5_000) { model.uiState.value.phase == SketchPhase.READY }
        return model
    }

    @Composable
    private fun Host(model: SketchViewModel, modifier: Modifier) {
        val state by model.uiState.collectAsState()
        SketchCanvas(state, model::placePoint, model::select, modifier, onViewChange = { lastView = it }, onCommitDrag = { i, p -> model.commitDrag(i, p) })
    }

    /** The Robolectric window is about 320 dp wide, so tests stay within two inches of the origin.
     * Canvas origin is (16dp, 16dp) at zoom 1, which is 96dp per inch. */
    private fun Offset.atInches(x: Float, y: Float, density: Float): Offset = Offset((16 + 96 * x) * density, (16 + 96 * y) * density)

    @Test
    fun tapTapDrawsALineWithSnappedLength() {
        val model = startCanvas()
        val d = rule.density.density
        // Slightly off the 1 in grid on purpose: the points must snap to it.
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput { click(Offset.Zero.atInches(1f, 1f, d) + Offset(3f * d, 2f * d)) }
        rule.waitUntil(5_000) { model.uiState.value.pendingStart != null }
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput { click(Offset.Zero.atInches(2f, 1f, d) + Offset(-2f * d, 3f * d)) }
        rule.waitUntil(5_000) { model.uiState.value.document.scene.size == 1 }
        val line = model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line
        assertEquals(SketchPoint(Inches(16), Inches(16)), line.a)
        assertEquals(SketchPoint(Inches(32), Inches(16)), line.b)
        assertEquals(16L, line.b.x.sixteenths - line.a.x.sixteenths)
    }

    @Test
    fun selectToolDragOnEmptySpacePans() {
        val model = startCanvas()
        model.setTool(SketchTool.ZONE_SELECT)
        val d = rule.density.density
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput {
            swipe(Offset(200f * d, 200f * d), Offset(300f * d, 260f * d), durationMillis = 200)
        }
        rule.waitForIdle()
        assertNull(model.uiState.value.pendingStart)
        assertTrue(model.uiState.value.document.scene.primitives.isEmpty())
        val view = lastView
        assertTrue(view != null && view.origin.x.num > 16 * view.origin.x.den, "drag should have panned the view, got $view")
    }

    @Test
    fun narrowCanvasDoesNotCrash() {
        val model = startCanvas(Modifier.width(100.dp).fillMaxSize())
        val d = rule.density.density
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput { click(Offset(20f * d, 20f * d)) }
        rule.waitForIdle()
        assertTrue(model.uiState.value.phase == SketchPhase.READY)
    }

    @Test
    fun dragWithLineToolDrawsASnappedSelectedLine() {
        val model = startCanvas()
        val d = rule.density.density
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput {
            swipe(Offset.Zero.atInches(1f, 1f, d), Offset.Zero.atInches(2f, 1f, d) + Offset(3f * d, 2f * d), durationMillis = 300)
        }
        rule.waitUntil(5_000) { model.uiState.value.document.scene.size == 1 }
        val line = model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line
        assertEquals(SketchPoint(Inches(16), Inches(16)), line.a)
        assertEquals(SketchPoint(Inches(32), Inches(16)), line.b)
        assertEquals(line.id, model.uiState.value.selectedId)
        assertNull(model.uiState.value.pendingStart)
        assertTrue(lastView == null, "a drawing drag must not pan the view")
    }

    @Test
    fun selectToolDragOnAHandleMovesThatVertex() {
        val model = startCanvas()
        val d = rule.density.density
        model.drawShape(SketchPoint(Inches(16), Inches(16)), SketchPoint(Inches(32), Inches(16)))
        rule.waitUntil(5_000) { model.uiState.value.selectedId != null }
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput {
            swipe(Offset.Zero.atInches(2f, 1f, d), Offset.Zero.atInches(2f, 2f, d), durationMillis = 300)
        }
        rule.waitUntil(5_000) { (model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line).b.y.sixteenths != 16L }
        val line = model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line
        assertEquals(SketchPoint(Inches(16), Inches(16)), line.a)
        assertEquals(SketchPoint(Inches(32), Inches(32)), line.b)
    }

    @Test
    fun selectToolDragOnTheBodyMovesTheWholeShape() {
        val model = startCanvas()
        val d = rule.density.density
        model.drawShape(SketchPoint(Inches(16), Inches(16)), SketchPoint(Inches(48), Inches(16)))
        rule.waitUntil(5_000) { model.uiState.value.selectedId != null }
        model.setTool(SketchTool.ZONE_SELECT)
        rule.waitForIdle()
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput {
            swipe(Offset.Zero.atInches(2f, 1f, d), Offset.Zero.atInches(2f, 2f, d), durationMillis = 300)
        }
        rule.waitUntil(5_000) { (model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line).a.y.sixteenths != 16L }
        val line = model.uiState.value.document.scene.primitives.single() as SketchPrimitive.Line
        assertEquals(SketchPoint(Inches(16), Inches(32)), line.a)
        assertEquals(SketchPoint(Inches(48), Inches(32)), line.b)
    }
}
