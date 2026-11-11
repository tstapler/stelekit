// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.SketchPoint
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
class SketchA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private val project = ProjectUuid("p1")
    private val repos = inMemorySketchRepositories()
    private var vm: SketchViewModel? = null

    @After
    fun tearDown() = vm?.onForgotten() ?: Unit

    private fun start(): SketchViewModel {
        runBlocking { repos.projects.saveProject(SketchProject(project, "proj-000001", "Test", 0)) }
        val model = SketchViewModel(repos, project)
        vm = model
        rule.setContent { Host(model) }
        rule.waitUntil(5_000) { model.uiState.value.phase == SketchPhase.READY }
        return model
    }

    @Composable
    private fun Host(model: SketchViewModel) {
        val state by model.uiState.collectAsState()
        SketchNonDragMode(state, model, Modifier.fillMaxSize())
    }

    private fun place() = rule.onNodeWithText("Place point").performClick()

    @Test
    fun crosshairNudgeAndPlaceTwiceDrawsALineWithoutAnyDrag() {
        val model = start()
        rule.onNodeWithText("Crosshair").performClick()
        place()
        rule.waitUntil(5_000) { model.uiState.value.pendingStart != null }
        rule.onNodeWithContentDescription("Nudge right, 10 pixels step").performClick()
        rule.onNodeWithContentDescription("Nudge down, 10 pixels step").performClick()
        place()
        rule.waitUntil(5_000) { model.uiState.value.document.scene.size == 1 }
        assertNull(model.uiState.value.pendingStart)
    }

    @Test
    fun nudgeButtonsAreAtLeast48dpAndLabelledWithTheirStep() {
        start()
        rule.onNodeWithText("Crosshair").performClick()
        rule.onNodeWithContentDescription("Nudge left, 10 pixels step").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("1 pixel").performClick()
        rule.onNodeWithContentDescription("Nudge up, 1 pixel step").assertExists()
    }

    @Test
    fun canvasDescribesEveryShapeWithItsValue() {
        val model = start()
        model.placePoint(SketchPoint(Inches(16), Inches(16)))
        model.placePoint(SketchPoint(Inches(208), Inches(16)))
        rule.waitUntil(5_000) { model.uiState.value.document.scene.size == 1 }
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).assertContentDescriptionEquals("Line 1, 1 ft")
    }

    @Test
    fun moveVertexActionJumpsTheCrosshairThenApplyMovesIt() {
        val model = start()
        model.placePoint(SketchPoint(Inches(16), Inches(16)))
        model.placePoint(SketchPoint(Inches(208), Inches(16)))
        rule.waitUntil(5_000) { model.uiState.value.document.scene.size == 1 }
        val id = model.uiState.value.document.scene.primitives.single().id
        model.select(id)
        rule.waitForIdle()
        val actions = rule.onNodeWithTag(SKETCH_CANVAS_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move vertex 1", "Move vertex 2"), actions.map { it.label })
        rule.runOnUiThread { assertTrue(actions[1].action()) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Nudge right, 10 pixels step").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.waitUntil(5_000) { model.uiState.value.document.scene.vertexAt(VertexRef(id, 1)) != SketchPoint(Inches(208), Inches(16)) }
    }
}
