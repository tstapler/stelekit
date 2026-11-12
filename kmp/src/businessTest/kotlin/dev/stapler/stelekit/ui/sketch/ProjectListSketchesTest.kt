// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.projectbridge.BridgeMapSettings
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.StandaloneSketchService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListSketchesTest {
    private val raw = BridgeMapSettings()
    private val repos = inMemorySketchRepositories()
    private val models = mutableListOf<ProjectListViewModel>()

    private fun vm(service: StandaloneSketchService? = StandaloneSketchService(repos.projects, { it() }, null, { 1L })) =
        ProjectListViewModel(
            repos.projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L },
            newSketch = service, dispatcher = UnconfinedTestDispatcher(),
        ).also { models += it }

    @AfterTest
    fun tearDown() = models.forEach { it.close() }

    @Test
    fun newSketchNeedsNoProjectAndOpensTheEditorAtOnce() {
        val vm = vm()
        var opened: String? = null
        vm.createSketch { opened = it }
        assertNotNull(opened)
        assertEquals(listOf(opened), vm.state.value.sketches.map { it.uuid })
        assertTrue(vm.state.value.projects.isEmpty())
    }

    @Test
    fun sketchesStayOutOfTheProjectListAndOutOfAnalysis() {
        val vm = vm()
        vm.createSketch { }
        vm.createProject("Garage")
        assertEquals(listOf("Garage"), vm.state.value.projects.map { it.name })
        assertEquals(1, vm.state.value.sketches.size)
        vm.setAnalyseAfterSync(true)
        assertEquals("Choose a Project analysis provider in Settings first", vm.state.value.message)
    }

    @Test
    fun aFailedStartIsReportedAndNothingOpens() {
        val failing = StandaloneSketchService(repos.projects, { DomainError.DatabaseError.WriteFailed("x").left() }, null, { 1L })
        val vm = vm(failing)
        var opened = false
        vm.createSketch { opened = true }
        assertFalse(opened)
        assertEquals("Could not start a sketch", vm.state.value.message)
    }

    @Test
    fun withoutASketchServiceTheActionIsUnavailable() {
        val vm = vm(service = null)
        vm.createSketch { }
        assertFalse(vm.sketchesAvailable)
        assertEquals("Sketching is not available here", vm.state.value.message)
    }
}
