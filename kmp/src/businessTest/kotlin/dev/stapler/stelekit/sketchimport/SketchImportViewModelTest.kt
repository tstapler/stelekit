// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketchimport

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.ui.sketchimport.SketchImportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SketchImportViewModelTest {
    private val registry = ClosetFusion.registry
    private val untaggable = """<svg xmlns="http://www.w3.org/2000/svg"><text x="10" y="10">50"</text></svg>""".encodeToByteArray()
    private lateinit var driver: SqlDriver
    private lateinit var assetRepo: SqlDelightAssetRepository
    private val repos = inMemorySketchRepositories()
    private lateinit var assets: AssetIndexService
    private lateinit var vm: SketchImportViewModel

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        assetRepo = SqlDelightAssetRepository(SteleDatabase(driver))
        repos.projects.saveProject(SketchTestSupport.project())
        assets = AssetIndexService(assetRepo)
        val flow = ImportFlow(assets, repos, FakeFileSystem(), EffectiveNotesPath("/graph"), writeActor = null)
        vm = SketchImportViewModel(flow, ProjectUuid("p1"), registry)
    }

    @AfterTest
    fun teardown() {
        vm.close()
        assets.close()
        driver.close()
    }

    private suspend fun rowCount() = assetRepo.countAssets().getOrNull()

    @Test
    fun open_should_ListUnboundLabelsForTagging() {
        vm.open("odd.svg", untaggable)
        val s = vm.state.value
        assertEquals(ImportPhase.Tagging, s.phase)
        assertEquals(1, s.items.size)
    }

    @Test
    fun cancel_should_LeaveNoRows() = runBlocking<Unit> {
        vm.open("odd.svg", untaggable)
        vm.choose(vm.state.value.items.single().ref, TagChoice.Existing(registry.dimensions.first().key))
        vm.cancel()
        assertEquals(ImportPhase.Cancelled, vm.state.value.phase)
        assertEquals(0L, rowCount())
        assertEquals(0, repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.size)
        assertEquals(0, repos.sheets.getSheets(ProjectUuid("p1")).first().getOrNull()!!.items.size)
    }

    @Test
    fun save_should_WriteTaggedDimension_AndPublishRegistryWithNewKey() = runBlocking<Unit> {
        vm.open("odd.svg", untaggable)
        vm.choose(vm.state.value.items.single().ref, TagChoice.New("custom.span", "Custom span"))
        vm.save()
        val done = withTimeout(30_000) { vm.state.first { it.phase == ImportPhase.Done || it.phase == ImportPhase.Failed } }
        assertEquals(ImportPhase.Done, done.phase)
        assertNotNull(done.registry!!.entry("custom.span"))
        assertEquals("custom.span", repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.single().key)
        assertEquals(1L, rowCount())
    }

    @Test
    fun save_should_ShowKeyErrorAndWriteNothing_When_NewKeyIsInvalid() = runBlocking<Unit> {
        vm.open("odd.svg", untaggable)
        vm.choose(vm.state.value.items.single().ref, TagChoice.New("Bad Key", ""))
        vm.save()
        assertEquals(KeyError.BadFormat, vm.state.value.keyError)
        assertEquals(ImportPhase.Tagging, vm.state.value.phase)
        assertEquals(0L, rowCount())
    }

    @Test
    fun open_should_Fail_ForUnsupportedFile() {
        vm.open("scan.png", byteArrayOf(1))
        assertEquals(ImportPhase.Failed, vm.state.value.phase)
    }
}
