// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.ConstraintTemplate
import dev.stapler.stelekit.projectbridge.FusionSettingsLoader
import dev.stapler.stelekit.projectbridge.FusionSettingsState
import dev.stapler.stelekit.projectbridge.PreviewVerdict
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.projectbridge.ProjectFusionSettings
import dev.stapler.stelekit.projectbridge.loadFusionSetup
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class RegistryEditorViewModelTest {
    private val repos = inMemorySketchRepositories()
    private val models = mutableListOf<RegistryEditorViewModel>()
    private var yaml: String? = "niche:\n  width: 136\nzones:\n  - short: \"a\"\n    width: 60\n  - short: \"b\"\n    width: 76\n"

    private fun vm() = RegistryEditorViewModel(repos.projects, { it() }, { yaml }, UnconfinedTestDispatcher()).also { models += it }

    private suspend fun createProject() {
        repos.projects.saveProject(ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L))
    }

    @AfterTest
    fun tearDown() = models.forEach { it.close() }

    @Test
    fun withoutAProjectEditsAreRefusedWithAMessage() = runTest {
        val vm = vm()
        vm.addKey("a.b", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "")
        assertEquals("Create a project first", vm.state.value.message)
        assertFalse(vm.state.value.hasProject)
    }

    @Test
    fun registryAndConstraintsAreSavedIntoTheSettingsTheFusionStageReads() = runTest {
        createProject()
        val vm = vm()
        assertEquals(3, vm.state.value.suggestions.size)
        vm.state.value.suggestions.map { it.path }.forEach(vm::acceptSuggestion)
        assertTrue(vm.state.value.suggestions.isEmpty())
        vm.addConstraint(ConstraintTemplate.PartsSum("niche.width", listOf("zones.a.width", "zones.b.width"), "Zones"))

        val setup = assertNotNull(loadFusionSetup(repos))
        assertEquals(3, setup.registry.dimensions.size)
        assertEquals(1, setup.spec.constraints.size)
        assertEquals("Zones", setup.spec.checks.single().label)
        assertEquals(PreviewVerdict.HOLD, vm.state.value.preview.single().verdict)
        val saved = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project
        assertTrue(saved.modelRevision > 0)
        listOf("content://", "file://", "/home", ".md", ".yaml").forEach { assertFalse(saved.settings.toString().contains(it), it) }
    }

    @Test
    fun theSolverPreviewFollowsTheYamlSnapshot() = runTest {
        createProject()
        yaml = yaml!!.replace("76", "77")
        val vm = vm()
        vm.state.value.suggestions.map { it.path }.forEach(vm::acceptSuggestion)
        vm.addConstraint(ConstraintTemplate.PartsSum("niche.width", listOf("zones.a.width", "zones.b.width"), "Zones"))
        assertEquals(PreviewVerdict.FAIL, vm.state.value.preview.single().verdict)
        vm.removeConstraint(vm.state.value.spec.constraints.single().id)
        assertTrue(vm.state.value.preview.isEmpty())
        assertTrue(vm.state.value.spec.checks.isEmpty())
    }

    @Test
    fun refusedEditsLeaveTheSavedSettingsUntouched() = runTest {
        createProject()
        val vm = vm()
        vm.addKey("niche.width", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "")
        vm.addKey("niche.width", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "")
        assertEquals("That key already exists", vm.state.value.message)
        assertEquals(1, assertNotNull(loadFusionSetup(repos)).registry.dimensions.size)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun damagedSettingsShowNeedsAttentionAndStartOverClearsThem() = runTest {
        val project = ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L)
        val damaged = JsonObject(project.settings + (ProjectFusionSettings.REGISTRY_KEY to JsonPrimitive("junk")))
        repos.projects.saveProject(project.copy(settings = damaged))
        val vm = vm()
        assertEquals(FusionSettingsLoader.BAD_REGISTRY, vm.state.value.needsAttention)
        assertNull(loadFusionSetup(repos))

        vm.addKey("a.b", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "")
        assertEquals("Start over before editing", vm.state.value.message)
        vm.startOver()
        assertNull(vm.state.value.needsAttention)
        val saved = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project
        assertTrue(FusionSettingsLoader.load(saved.settings) is FusionSettingsState.Valid)
    }

    @Test
    fun renameAndDeleteGoThroughThePersistedSpec() = runTest {
        createProject()
        val vm = vm()
        vm.state.value.suggestions.map { it.path }.forEach(vm::acceptSuggestion)
        vm.addConstraint(ConstraintTemplate.Equality("zones.a.width", "zones.b.width"))
        vm.deleteKey("zones.a.width")
        assertEquals("zones.a.width is still used by a constraint or derivation", vm.state.value.message)
        vm.renameKey("zones.a.width", "zone.left")
        assertEquals(listOf("zone.left", "zones.b.width"), vm.state.value.spec.constraints.single().dimensions)
        assertEquals("zone.left", assertNotNull(loadFusionSetup(repos)).registry.dimensions.first { it.key == "zone.left" }.key)
    }

    @Test
    fun kindAliasAndActionBindingsReachTheSavedRegistry() = runTest {
        createProject()
        val vm = vm()
        val actions = vm.actions(onBack = { backs++ })
        actions.onBack()
        assertEquals(1, backs)
        actions.onAddKey("a.b", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "A")
        actions.onToggleKind("a.b", dev.stapler.stelekit.fusion.DimensionKind.COUNT)
        actions.onAddAlias("a.b", dev.stapler.stelekit.projectbridge.AliasKind.PROSE, "the a")
        assertEquals(listOf("the a"), vm.state.value.registry.entry("a.b")!!.aliases.prose)
        assertEquals(dev.stapler.stelekit.fusion.DimensionKind.COUNT, assertNotNull(loadFusionSetup(repos)).registry.entry("a.b")!!.kind)
        actions.onRemoveAlias("a.b", dev.stapler.stelekit.projectbridge.AliasKind.PROSE, "the a")
        assertTrue(vm.state.value.registry.entry("a.b")!!.aliases.prose.isEmpty())
        actions.onRenameKey("a.b", "c.d")
        actions.onAddKey("e.f", dev.stapler.stelekit.fusion.DimensionKind.LENGTH, "")
        actions.onAddConstraint(ConstraintTemplate.Equality("c.d", "e.f"))
        actions.onRemoveConstraint(1)
        actions.onDeleteKey("c.d")
        actions.onAcceptSuggestion("nope")
        actions.onStartOver()
        actions.onDismissMessage()
        assertEquals(listOf("e.f"), vm.state.value.registry.dimensions.map { it.key })
    }

    private var backs = 0
}
