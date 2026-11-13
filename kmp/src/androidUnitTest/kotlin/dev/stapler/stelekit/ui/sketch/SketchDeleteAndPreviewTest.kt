// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
class SketchDeleteAndPreviewTest {
    @get:Rule
    val rule = createComposeRule()

    private var vm: SketchViewModel? = null

    @After
    fun tearDown() = vm?.onForgotten() ?: Unit

    @Test
    fun deleteButtonAppearsForASelectionAndRemovesTheShape() {
        val repos = inMemorySketchRepositories()
        runBlocking { repos.projects.saveProject(SketchProject(ProjectUuid("p1"), "proj-000001", "Test", 0)) }
        val model = SketchViewModel(repos, ProjectUuid("p1"))
        vm = model
        rule.setContent { SketchScreen(viewModel = model, onNavigateBack = {}) }
        rule.waitUntil(5_000) { model.uiState.value.phase == SketchPhase.READY }
        rule.onNodeWithContentDescription(DELETE_SELECTED_DESCRIPTION).assertDoesNotExist()
        model.drawShape(SketchPoint(Inches(0), Inches(0)), SketchPoint(Inches(1920), Inches(960)))
        val id = model.uiState.value.document.scene.primitives.single().id
        model.select(id)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(DELETE_SELECTED_DESCRIPTION).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(0, model.uiState.value.document.scene.size)
        assertNull(model.uiState.value.selectedId)
    }

    @Test
    fun previewShowsPlaceholdersForMissingAndEmptySketches() {
        val preview = mutableStateOf<SketchPreview?>(SketchPreview.Missing)
        rule.setContent { SketchPreviewContent(preview.value) }
        rule.onNodeWithText(MISSING_SKETCH_TEXT).assertIsDisplayed()
        preview.value = SketchPreview.Empty
        rule.waitForIdle()
        rule.onNodeWithText(EMPTY_SKETCH_TEXT).assertIsDisplayed()
    }
}
