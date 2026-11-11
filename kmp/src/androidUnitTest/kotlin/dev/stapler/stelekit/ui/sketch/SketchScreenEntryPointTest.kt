// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** First-open check for the flagged Sketch screen: composes the real host over real (in-memory) repositories. */
@RunWith(RobolectricTestRunner::class)
class SketchScreenEntryPointTest {
    @get:Rule
    val rule = createComposeRule()

    private var vm: SketchViewModel? = null

    @After
    fun tearDown() = vm?.onForgotten() ?: Unit

    @Test
    fun hostRendersToolbarAndCanvasAndToolChipsSwitchTools() {
        val repos = inMemorySketchRepositories()
        runBlocking { repos.projects.saveProject(SketchProject(ProjectUuid("p1"), "proj-000001", "Test", 0)) }
        val model = SketchViewModel(repos, ProjectUuid("p1"))
        vm = model
        var backs = 0
        rule.setContent { SketchScreen(viewModel = model, onNavigateBack = { backs++ }) }
        rule.waitUntil(5_000) { model.uiState.value.phase == SketchPhase.READY }
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).assertIsDisplayed()
        rule.onNodeWithText("Rectangle").performClick()
        rule.waitForIdle()
        assertEquals(SketchTool.RECTANGLE, model.uiState.value.tool)
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }
}
