// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.projectbridge.AnalyseToggleRules
import dev.stapler.stelekit.projectbridge.BridgeMapSettings
import dev.stapler.stelekit.projectbridge.OutboundPreview
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.ProjectSlugs
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class ProjectListViewModelTest {
    private val graphId = "g1"
    private val raw = BridgeMapSettings()
    private val bridge = ProjectBridgeSettings(raw)
    private val llm = LlmSettings(raw)
    private val repos: SketchRepositories = inMemorySketchRepositories()
    private var tick = 0L
    private val models = mutableListOf<ProjectListViewModel>()

    private val pageRepo = dev.stapler.stelekit.repository.InMemoryPageRepository()
    private val files = mutableMapOf<String, String>()

    private fun vm(withSources: Boolean = false, write: ProjectWriteRunner = { it() }, yaml: YamlPickerAction? = null) = ProjectListViewModel(
        repos.projects, graphId, bridge, llm, write, { ++tick }, Random(1),
        sources = if (withSources) dev.stapler.stelekit.projectbridge.SourceBindingService(repos, pageRepo, { files[it] }, { it() }, dev.stapler.stelekit.projectbridge.LocalBindingTable(raw)) else null,
        yaml = yaml,
        dispatcher = UnconfinedTestDispatcher(),
    ).also { models += it }

    @AfterTest
    fun tearDown() = models.forEach { it.close() }

    private fun configureProvider() {
        llm.setSelectedProviderId(LlmFeature.PROJECT_ANALYSIS, "anthropic")
        llm.acknowledge(LlmFeature.PROJECT_ANALYSIS, "anthropic", "")
    }

    @Test
    fun creatingAProjectStoresAnOpaqueSlugNeverDerivedFromTheName() {
        val vm = vm()
        vm.createProject("  Hall   closet ")
        val row = vm.state.value.projects.single()
        assertEquals("Hall closet", row.name)
        assertTrue(ProjectSlugs.isValid(row.slug))
        assertFalse(row.slug.contains("closet", ignoreCase = true))
    }

    @Test
    fun savedProjectHoldsNoPathUriOrTitleInItsSettings() {
        val vm = vm()
        vm.createProject("Garage")
        val saved: SketchProject = repos.projects.let { r -> kotlinx.coroutines.runBlocking { r.loadProject(ProjectUuid(vm.state.value.projects.single().uuid)) } }
            .getOrNull()!!.project
        assertTrue(saved.settings.isEmpty())
        assertEquals("", saved.sourceHash)
        assertNull(saved.lastSyncedSidecarHash)
    }

    @Test
    fun blankNameIsRejectedWithAMessageAndWritesNothing() {
        var writes = 0
        val vm = vm { writes++; it() }
        vm.createProject("   ")
        assertEquals(0, writes)
        assertNotNull(vm.state.value.message)
        assertTrue(vm.state.value.projects.isEmpty())
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun aFailedWriteIsReportedNotThrown() {
        val vm = vm { DomainError.DatabaseError.WriteFailed("disk").left() as Either<DomainError, Unit> }
        vm.createProject("Garage")
        assertEquals("Could not create the project", vm.state.value.message)
        assertTrue(vm.state.value.projects.isEmpty())
    }

    @Test
    fun listIsBoundedAndReportsMore() {
        val vm = vm()
        repeat(ProjectListViewModel.PAGE_SIZE + 3) { vm.createProject("P$it") }
        assertEquals(ProjectListViewModel.PAGE_SIZE, vm.state.value.projects.size)
        assertTrue(vm.state.value.hasMore)
    }

    @Test
    fun digestSettingsRoundTripThroughProjectBridgeSettings() {
        val vm = vm()
        vm.createProject("Closet")
        assertEquals("Closet Digest", vm.state.value.digestTitle)
        vm.setDigestEnabled(true)
        vm.setDigestTitle(" Closet Plan ")
        assertTrue(bridge.digestEnabled(graphId))
        assertEquals("Closet Plan", bridge.digestPageTitle(graphId, "x"))
        assertTrue(vm.state.value.digestEnabled)
        assertEquals("Closet Plan", vm.state.value.digestTitle)
    }

    @Test
    fun invalidDigestTitleKeepsTheStoredOneAndExplains() {
        val vm = vm()
        vm.setDigestTitle("a[[b]]")
        assertEquals("", raw.getString("home_sketch_digest_title_g1", ""))
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun analysisIsNotOfferedWithoutAnExplicitProvider() {
        val vm = vm()
        vm.createProject("Closet")
        assertFalse(vm.state.value.analyseOffered)
        vm.setAnalyseAfterSync(true)
        assertFalse(bridge.analyseAfterSync(graphId))
        assertNull(vm.state.value.pendingPreview)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun disabledSentinelProviderIsNotOffered() {
        llm.setSelectedProviderId(LlmFeature.PROJECT_ANALYSIS, LlmProviderRegistry.DISABLED_SENTINEL)
        val vm = vm()
        assertFalse(vm.state.value.analyseOffered)
    }

    @Test
    fun enablingAnalysisOpensThePreviewFirstAndOnlyConfirmingEnablesIt() {
        configureProvider()
        val vm = vm()
        vm.createProject("Closet")
        assertTrue(vm.state.value.analyseOffered)
        vm.setAnalyseAfterSync(true)
        val text = assertNotNull(vm.state.value.pendingPreview)
        assertFalse(bridge.analyseAfterSync(graphId), "nothing is enabled until the owner approves the text")
        vm.confirmPreview()
        assertTrue(bridge.analyseAfterSync(graphId))
        assertNull(vm.state.value.pendingPreview)
        assertTrue(bridge.isOutboundPreviewAccepted(graphId, text))
        assertEquals(OutboundPreview.hash(text), bridge.acceptedOutboundPreviewHash(graphId))
    }

    @Test
    fun cancellingThePreviewLeavesAnalysisOff() {
        configureProvider()
        val vm = vm()
        vm.createProject("Closet")
        vm.setAnalyseAfterSync(true)
        vm.dismissPreview()
        assertNull(vm.state.value.pendingPreview)
        assertFalse(bridge.analyseAfterSync(graphId))
    }

    @Test
    fun anAcceptedPreviewSkipsTheDialogAndTurningOffNeedsNoPreview() {
        configureProvider()
        val vm = vm()
        vm.createProject("Closet")
        val slug = vm.state.value.projects.single().slug
        bridge.acceptOutboundPreview(graphId, OutboundPreview.hash(AnalyseToggleRules.emptyPreview(slug)))
        vm.setAnalyseAfterSync(true)
        assertNull(vm.state.value.pendingPreview)
        assertTrue(vm.state.value.analyseAfterSync)
        vm.setAnalyseAfterSync(false)
        assertFalse(bridge.analyseAfterSync(graphId))
    }

    @Test
    fun enablingWithoutAnyProjectAsksForOneAndTurningOffStillWorks() {
        configureProvider()
        bridge.setAnalyseAfterSync(graphId, true)
        val vm = vm()
        vm.setAnalyseAfterSync(true)
        assertNotNull(vm.state.value.message)
        vm.setAnalyseAfterSync(false)
        assertFalse(bridge.analyseAfterSync(graphId))
    }

    @Test
    fun privateTermsAddedFromThePreviewAreStoredPerGraph() {
        val vm = vm()
        vm.addPrivateTerm("Maple")
        assertEquals(listOf("Maple"), vm.state.value.privateTerms)
        vm.addPrivateTerm("maple")
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun configuredProjectLookupFollowsTheRepository() = kotlinx.coroutines.test.runTest {
        assertFalse(hasConfiguredProject(repos))
        vm().createProject("Closet")
        assertTrue(hasConfiguredProject(repos))
    }

    @Test
    fun sourcePickerSearchesBindsAndUnbindsTheFirstProject() = kotlinx.coroutines.test.runTest {
        val id = dev.stapler.stelekit.projectbridge.uuidOf(5)
        pageRepo.savePage(dev.stapler.stelekit.projectbridge.testPage(id, "Closet Plan"))
        files["/g/pages/Closet Plan.md"] = "| a |"
        val vm = vm(withSources = true)
        assertTrue(vm.sourcesAvailable)
        vm.bindSource(dev.stapler.stelekit.projectbridge.SourceBindingKind.TABLE, id)
        assertNotNull(vm.state.value.message, "no project yet")
        vm.dismissMessage()
        vm.createProject("Closet")
        vm.searchSourcePages("clos")
        assertEquals(listOf(id), vm.state.value.pickerResults.map { it.uuid })
        vm.bindSource(dev.stapler.stelekit.projectbridge.SourceBindingKind.TABLE, id)
        assertEquals("Closet Plan", vm.state.value.boundSources.single().title)
        vm.unbindSource(vm.state.value.boundSources.single().bindingId)
        assertTrue(vm.state.value.boundSources.isEmpty())
    }

    @Test
    fun unreadablePageBindingShowsAMessage() = kotlinx.coroutines.test.runTest {
        val id = dev.stapler.stelekit.projectbridge.uuidOf(6)
        pageRepo.savePage(dev.stapler.stelekit.projectbridge.testPage(id, "Stub", loaded = false))
        val vm = vm(withSources = true)
        vm.createProject("Closet")
        vm.bindSource(dev.stapler.stelekit.projectbridge.SourceBindingKind.PROSE, id)
        assertNotNull(vm.state.value.message)
        assertTrue(vm.state.value.boundSources.isEmpty())
    }

    @Test
    fun pickerIsUnavailableWithoutAService() {
        val vm = vm()
        assertFalse(vm.sourcesAvailable)
        vm.searchSourcePages("clos")
        vm.unbindSource("00000001")
        assertTrue(vm.state.value.pickerResults.isEmpty())
    }

    private fun yamlAction(pick: suspend () -> String?) = YamlPickerAction(
        dev.stapler.stelekit.projectbridge.YamlSnapshotImporter(repos, { store }, { it() }), repos, pick,
    )
    private val store = dev.stapler.stelekit.projectbridge.newStore(dev.stapler.stelekit.projectbridge.SidecarFs())

    @Test
    fun chooseYamlRecordsTheSnapshotAndFlipsTheStatus() {
        val vm = vm(yaml = yamlAction { "a: 1\n" })
        assertTrue(vm.yamlAvailable)
        vm.chooseYaml()
        assertEquals("Create a project first", vm.state.value.message)
        vm.createProject("Closet")
        vm.chooseYaml()
        assertTrue(vm.state.value.yamlSnapshotSet)
        vm.chooseYaml()
        assertEquals("That YAML file is already the project's source", vm.state.value.message)
    }

    @Test
    fun chooseYamlIsInertWithoutAPicker() {
        val vm = vm()
        assertFalse(vm.yamlAvailable)
        vm.createProject("Closet")
        vm.chooseYaml()
        assertFalse(vm.state.value.yamlSnapshotSet)
        assertNull(vm.state.value.message)
    }
}
