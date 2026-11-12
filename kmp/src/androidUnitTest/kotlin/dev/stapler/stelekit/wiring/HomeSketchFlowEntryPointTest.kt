// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.ui.GraphDialogLayer
import dev.stapler.stelekit.ui.NotificationManager
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.ScreenRouter
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.components.NavSectionList
import dev.stapler.stelekit.ui.components.settings.HOME_SKETCH_LABEL
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.ui.sketch.DIMENSION_FIELD_TAG
import dev.stapler.stelekit.ui.sketch.SKETCH_CANVAS_TAG
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end walk of the flagged home-sketch flow through the real [StelekitViewModel], the real nav list,
 * the real Settings Labs switch ([GraphDialogLayer]) and the real [ScreenRouter] over the wired in-memory
 * repositories of [WiredAppFixture]. Each numbered step is one assertion block; a failure names the broken step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h1600dp")
class HomeSketchFlowEntryPointTest {
    @get:Rule
    val rule = createComposeRule()

    private val dataDir: File = createTempDirectory("home_sketch_flow_").toFile()
    private var fixture: WiredAppFixture? = null
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun flagGatesNavThenProjectIsCreatedOpenedDrawnAndFlagOffReturnsToJournals() {
        val wired = runBlocking {
            WiredAppFixture.android(ApplicationProvider.getApplicationContext(), dataDir)
        }.also { fixture = it }
        val repos = wired.repos
        val ports = wired.ports
        val loader = GraphLoader(ports.fileSystem, repos.pageRepository, repos.blockRepository)
        val writer = GraphWriter(ports.fileSystem)
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = ports.fileSystem,
                pageRepository = repos.pageRepository,
                blockRepository = repos.blockRepository,
                searchRepository = repos.searchRepository,
                graphLoader = loader,
                graphWriter = writer,
                platformSettings = ports.settings,
                scope = scope,
                journalService = repos.journalService,
            ),
        )
        val bsm = BlockStateManager(blockRepository = repos.blockRepository, graphLoader = loader, scope = scope)
        val journals = JournalsViewModel(repos.journalService, bsm, scope)
        val allPages = AllPagesViewModel(repos.pageRepository, repos.blockRepository, scope)
        val stats = LibraryStatsViewModel(scope = scope)
        val search = SearchViewModel(repos.searchRepository, scope, repos.pageRepository)
        val notifications = NotificationManager()

        rule.setContent {
            val state by vm.uiState.collectAsState()
            val sketchOn by vm.homeSketchEnabledState.collectAsState()
            MaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    NavSectionList(
                        currentScreen = state.currentScreen,
                        onNavigate = { vm.navigateTo(it) },
                        onGitSetup = {},
                        homeSketchEnabled = sketchOn,
                    )
                    }
                    Box(Modifier.weight(1f)) {
                    ScreenRouter(
                        screen = state.currentScreen,
                        repos = repos,
                        blockStateManager = bsm,
                        journalsViewModel = journals,
                        allPagesViewModel = allPages,
                        libraryStatsViewModel = stats,
                        viewModel = vm,
                        searchViewModel = search,
                        notificationManager = notifications,
                        // No graph load runs in this harness, so the view model never clears its loading flag.
                        appState = state.copy(isLoading = false),
                        graphWriter = writer,
                        platformSettings = ports.settings,
                        fileSystem = ports.fileSystem,
                        graphLoader = loader,
                    )
                    }
                }
                GraphDialogLayer(
                    appState = state,
                    searchViewModel = search,
                    viewModel = vm,
                    notificationManager = notifications,
                    fileSystem = ports.fileSystem,
                    frameMetric = MutableStateFlow(FrameMetric()),
                )
            }
        }

        // 1. flag off (default): no Sketch entry
        rule.onNodeWithTag("nav-row-sketch").assertDoesNotExist()

        // 2. flip the real Settings > Labs switch: entry appears with no restart
        vm.setSettingsVisible(true)
        rule.onNodeWithText("Labs").performClick()
        rule.onNodeWithText(HOME_SKETCH_LABEL).assertIsDisplayed()
        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        assertTrue(vm.homeSketchEnabled, "Labs switch must persist the flag")
        vm.setSettingsVisible(false)
        rule.waitForIdle()
        rule.onNodeWithTag("nav-row-sketch").assertExists()

        // 3. open Sketch: project list, empty state
        rule.onNodeWithTag("nav-row-sketch").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Projects (optional)").assertIsDisplayed()
        rule.onNodeWithText("New sketch").assertIsDisplayed()
        rule.onNodeWithText("No projects yet").assertIsDisplayed()

        // 4. create a project: opaque slug, listed by name
        rule.onNodeWithText("New project").performClick()
        rule.onNodeWithText("Name").performTextInput("Garden shed")
        rule.onNodeWithText("Create").performClick()
        rule.waitUntil(5_000) { runBlocking { repos.sketchRepositories.projects.getProjects(10, 0).first() }.getOrNull()?.isNotEmpty() == true }
        val project = runBlocking { repos.sketchRepositories.projects.getProjects(10, 0).first() }.getOrNull()!!.single()
        assertTrue(Regex("proj-[a-z0-9]{6}").matches(project.slug), "slug must be opaque, was ${project.slug}")
        rule.onNodeWithText("Garden shed").assertIsDisplayed()

        // 7. project settings: Registry and constraints is reachable
        rule.onNodeWithText("Registry and constraints").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Registry and constraints").assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()

        // 5. open the project: SketchScreen over a sheet
        rule.onNodeWithText("Garden shed").performClick()
        rule.waitUntil(5_000) { vm.uiState.value.currentScreen == Screen.Sketch(project.uuid.value) }
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).assertIsDisplayed()

        // 6. draw with the non-drag crosshair path
        rule.onNodeWithContentDescription("Place at crosshair mode").performClick()
        rule.onNodeWithText("Place point").performClick()
        repeat(NUDGES) { rule.onNodeWithContentDescription("Nudge right", substring = true).performClick() }
        rule.onNodeWithText("Place point").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(5_000) { drawnLines(repos).isNotEmpty() }
        val line = drawnLines(repos).single()
        val before = line.x2 - line.x1
        assertTrue(before > 0, "line should run right of its start, was $before")

        // 6b. select it (Select tool, tap its midpoint) and type a dimension
        rule.onNodeWithText("Select").performClick()
        val density = rule.density.density
        val midX = (CANVAS_ORIGIN_DP + DP_PER_INCH * (line.x1 + line.x2) / (2.0 * SIXTEENTHS)) * density
        val midY = (CANVAS_ORIGIN_DP + DP_PER_INCH * line.y1 / SIXTEENTHS.toDouble()) * density
        rule.onNodeWithTag(SKETCH_CANVAS_TAG).performTouchInput { click(Offset(midX.toFloat(), midY.toFloat())) }
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("3 ft")
        rule.onNodeWithText("Set").performClick()
        rule.waitUntil(5_000) { drawnLines(repos).firstOrNull()?.let { it.x2 - it.x1 } == FEET3 }
        assertEquals(FEET3, drawnLines(repos).single().let { it.x2 - it.x1 })

        // 8. flag off while on the Sketch screen: entry disappears, app returns to Journals
        vm.setSettingsVisible(true)
        rule.onNodeWithText("Labs").performClick()
        rule.onNodeWithText(HOME_SKETCH_LABEL).assertIsDisplayed()
        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        vm.setSettingsVisible(false)
        rule.waitForIdle()
        assertTrue(!vm.homeSketchEnabled, "Labs switch must persist the flag off")
        assertEquals(Screen.Journals, vm.uiState.value.currentScreen)
        rule.onNodeWithTag("nav-row-sketch").assertDoesNotExist()
    }

    private fun drawnLines(repos: dev.stapler.stelekit.repository.RepositorySet): List<SketchPrimitive> = runBlocking {
        val sheet = repos.sketchRepositories.sheets.getSheets(projectUuidOf(repos)).first().getOrNull()?.items?.firstOrNull()
            ?: return@runBlocking emptyList()
        repos.sketchRepositories.sheets.getPrimitives(sheet.uuid).first().getOrNull().orEmpty().filter { it.kind == "line" }
    }

    private fun projectUuidOf(repos: dev.stapler.stelekit.repository.RepositorySet) = runBlocking {
        repos.sketchRepositories.projects.getProjects(1, 0).first().getOrNull().orEmpty().single().uuid
    }

    private companion object {
        const val NUDGES = 12
        const val CANVAS_ORIGIN_DP = 16.0
        const val DP_PER_INCH = 96.0
        const val SIXTEENTHS = 16
        const val FEET3 = 36L * SIXTEENTHS
    }
}
