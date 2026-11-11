// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchSheetKind
import dev.stapler.stelekit.sketch.ViewDirection
import dev.stapler.stelekit.sketch.Zone
import dev.stapler.stelekit.sketch.ZoneReport
import dev.stapler.stelekit.sketch.ZoneSet
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
class SketchSheetPanelsTest {
    @get:Rule
    val rule = createComposeRule()

    private var vm: SketchViewModel? = null

    @After
    fun tearDown() = vm?.onForgotten() ?: Unit

    @Test
    fun sheetPropertiesPanelReportsKindDirectionAndGridStep() {
        val kinds = mutableListOf<SketchSheetKind>()
        val dirs = mutableListOf<ViewDirection>()
        val scales = mutableListOf<Long>()
        rule.setContent {
            SheetPropertiesPanel(SheetProps(SketchSheetKind.ELEVATION), { kinds += it }, { dirs += it }, { scales += it })
        }
        rule.onNodeWithText("Section").performClick()
        rule.onNodeWithText("From back").performClick()
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("0")
        rule.onNodeWithText("Set").performClick()
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("2")
        rule.onNodeWithText("Set").performClick()
        assertEquals(listOf(SketchSheetKind.SECTION), kinds)
        assertEquals(listOf(ViewDirection.FROM_BACK), dirs)
    }

    @Test
    fun layerPanelReportsEveryEdit() {
        val log = mutableListOf<String>()
        val props = SheetProps(layers = listOf(SketchLayer("wall")))
        rule.setContent {
            LayerPanel(props, hasSelection = true, onAdd = { log += "add:$it" }, onToggleVisible = { n, v -> log += "vis:$n:$v" },
                onToggleLock = { n, l -> log += "lock:$n:$l" }, onAssignSelection = { log += "assign:$it" })
        }
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.onNodeWithText("Move selection here").performClick()
        rule.onNodeWithText("Move selection to default layer").performClick()
        rule.onNodeWithText("New layer").performTextInput("trim")
        rule.onNodeWithText("Add").performClick()
        assertEquals(listOf("vis:wall:false", "lock:wall:true", "assign:wall", "assign:", "add:trim"), log)
    }

    @Test
    fun zonePanelReportsEditsAndFlagsBadText() {
        val log = mutableListOf<String>()
        val zones = ZoneSet(listOf(Zone("left", "oops"), Zone("right")))
        val report = ZoneReport(emptyMap(), setOf("left"), false)
        rule.setContent {
            ZonePanel(zones, report, { log += "add:$it" }, { k, t -> log += "text:$k:$t" }, { k, t -> log += "total:$k:$t" }, { log += "remove:$it" })
        }
        rule.onAllNodesWithText("Not a length. Try 12 3/16\"")[0].assertIsDisplayed()
        rule.onAllNodesWithText("Set")[0].performClick()
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodesWithText("Remove")[1].performClick()
        rule.onNodeWithText("New zone").performTextInput("mid")
        rule.onAllNodesWithText("Add")[0].performClick()
        assertEquals(listOf("text:left:oops", "total:left:true", "remove:right", "add:mid"), log)
        assertNull(null)
    }

    @Test
    fun hostShowsLengthFieldForSelectedLineAndSheetPanelsEditTheModel() {
        val repos = inMemorySketchRepositories()
        runBlocking { repos.projects.saveProject(SketchProject(ProjectUuid("p1"), "proj-000001", "Test", 0)) }
        val model = SketchViewModel(repos, ProjectUuid("p1"))
        vm = model
        rule.setContent { SketchScreen(viewModel = model, onNavigateBack = {}) }
        rule.waitUntil(5_000) { model.uiState.value.phase == SketchPhase.READY }
        model.placePoint(SketchPoint(Inches(0), Inches(0)))
        model.placePoint(SketchPoint(Inches(64), Inches(0)))
        val id = model.uiState.value.document.scene.primitives.single().id
        model.select(id)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Set").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextReplacement("10")
        rule.onAllNodesWithText("Set")[0].performClick()
        rule.waitForIdle()
        val line = model.uiState.value.document.scene.primitives.single() as dev.stapler.stelekit.sketch.SketchPrimitive.Line
        assertEquals(Inches(160), line.b.x)
        rule.onNodeWithText("Sheet panels").performScrollTo().performClick()
        rule.onNodeWithText("Elevation").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(SketchSheetKind.ELEVATION, model.uiState.value.document.sheet.kind)
        rule.onNodeWithText("Hide sheet panels").assertIsDisplayed()
    }

    @Test
    fun smallHelpersFormatAndBoundValues() {
        assertEquals("1' 4\"", lengthTextOf(-16 * 16))
        assertEquals(16L, sanitizedScale(Inches(16)))
        assertNull(sanitizedScale(Inches(0)))
        assertNull(sanitizedScale(Inches(SheetProps.MAX_SCALE_SIXTEENTHS + 1)))
        assertTrue(SketchSheetKind.entries.all { it.label().isNotBlank() })
        assertTrue(ViewDirection.entries.all { it.label().isNotBlank() })
    }
}
