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
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.ProjectSourceCollector
import dev.stapler.stelekit.projectbridge.ProjectSourceReader
import dev.stapler.stelekit.projectbridge.ProjectSourceTexts
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.ui.sketchimport.SketchImportViewModel
import dev.stapler.stelekit.ui.sketchimport.placed
import dev.stapler.stelekit.ui.sketchimport.tapped
import dev.stapler.stelekit.ui.sketchimport.typed
import dev.stapler.stelekit.ui.sketchimport.calibrated
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RasterImportFlowTest {
    private val png = pngHeader(1000, 500)
    private val fileName = "closet-private-name.png"
    private lateinit var driver: SqlDriver
    private lateinit var assets: AssetIndexService
    private val repos = inMemorySketchRepositories()
    private lateinit var vm: SketchImportViewModel

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        assets = AssetIndexService(SqlDelightAssetRepository(SteleDatabase(driver)))
        repos.projects.saveProject(SketchTestSupport.project())
        val flow = ImportFlow(assets, repos, FakeFileSystem(), EffectiveNotesPath("/graph"), writeActor = null)
        vm = SketchImportViewModel(flow, ProjectUuid("p1"), ClosetFusion.registry)
    }

    @AfterTest
    fun teardown() {
        vm.close()
        assets.close()
        driver.close()
    }

    private fun place(x1: Double, x2: Double) = vm.editRaster { r ->
        r.tapped(NormalizedPoint(x1, 0.5)).placed().tapped(NormalizedPoint(x2, 0.5)).placed()
    }

    private suspend fun saved() = run {
        vm.save()
        withTimeout(30_000) { vm.state.first { it.phase == ImportPhase.Done || it.phase == ImportPhase.Failed } }
    }

    private suspend fun project() = repos.projects.loadProject(ProjectUuid("p1")).getOrNull()!!.project

    private fun calibrateAndTag() {
        vm.open(fileName, png)
        place(0.1, 0.6)
        vm.editRaster { it.typed("100\"").calibrated() }
        place(0.1, 0.35)
        vm.bindRaster(TagChoice.New("niche.span", "Niche span"))
    }

    @Test
    fun open_should_StartTheCalibrationStepForAPng_AndNotForAnSvg() {
        vm.open(fileName, png)
        assertNotNull(vm.state.value.raster)
        assertNotNull(vm.rasterImage())
        vm.cancel()
        assertNull(vm.state.value.raster)
    }

    @Test
    fun save_should_WritePngTagObservation_AndTheCalibrationRecord() = runBlocking<Unit> {
        calibrateAndTag()
        val done = saved()
        assertEquals(ImportPhase.Done, done.phase)
        val dim = repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.single()
        assertEquals("niche.span", dim.key)
        assertEquals("png-tag", dim.sourceKind)
        assertEquals(Rational(50), dim.value)
        assertEquals(RasterTagging.MEASURED_PRECISION, dim.precision)
        val record = PngTagSources.of(project().settings).single()
        assertEquals(1, record.basisVersion)
        assertTrue(record.pixelsPerMeter.toDouble() > 0.0)
        assertEquals(listOf(PngTagValue("niche.span", Rational(50), measured = true)), record.tags)
    }

    @Test
    fun savedRecord_should_NameNoFile_AndJoinFusionThroughTheCollector() = runBlocking<Unit> {
        calibrateAndTag()
        val registry = assertNotNull(saved().registry)
        val settingsText = project().settings.toString()
        assertFalse("closet-private-name" in settingsText)
        assertFalse(".png" in settingsText)
        val tags = readPngTags(repos)
        val collected = ProjectSourceCollector(registry, reader = ProjectSourceReader { ProjectSourceTexts(pngTags = tags) }).collect()
        val obs = collected.observations.single { it.dimension == "niche.span" }
        assertEquals(SourceKind.PNG_TAG, obs.source.kind)
        assertEquals(Rational(50), obs.value)
        assertEquals(collected.pngObservations, listOf(obs))
    }

    @Test
    fun save_should_BindTheReferenceAtTheExactTypedLength() = runBlocking<Unit> {
        vm.open(fileName, png)
        place(0.1, 0.6)
        vm.editRaster { it.typed("5 3/8\"").calibrated() }
        vm.bindRaster(TagChoice.New("custom.depth", "Custom depth"), toReference = true)
        assertEquals(1, vm.state.value.raster!!.tags.size)
        val done = saved()
        assertEquals(ImportPhase.Done, done.phase, done.error)
        val dim = repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.single()
        assertEquals(Rational(43, 8), dim.value)
        assertEquals(Rational.ZERO, dim.precision)
    }

    @Test
    fun save_should_LeaveAnUncalibratedPngWithoutTagsOrRecord() = runBlocking<Unit> {
        vm.open(fileName, png)
        assertEquals(ImportPhase.Done, saved().phase)
        assertTrue(repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.isEmpty())
        assertTrue(PngTagSources.of(project().settings).isEmpty())
    }

    @Test
    fun cancel_should_WriteNothing() = runBlocking<Unit> {
        calibrateAndTag()
        vm.cancel()
        assertTrue(repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull()!!.isEmpty())
        assertTrue(PngTagSources.of(project().settings).isEmpty())
    }

    @Test
    fun bindRaster_should_ShowKeyErrorForABadNewKey_AndSkipClearsTheLine() {
        vm.open(fileName, png)
        place(0.1, 0.6)
        vm.editRaster { it.typed("100\"").calibrated() }
        place(0.1, 0.35)
        vm.bindRaster(TagChoice.New("Bad Key", ""))
        assertEquals(KeyError.BadFormat, vm.state.value.keyError)
        vm.bindRaster(TagChoice.Skip)
        assertTrue(vm.state.value.raster!!.points.isEmpty())
        vm.bindRaster(TagChoice.Existing("not.there"))
        assertEquals(KeyError.Unknown("not.there"), vm.state.value.keyError)
    }

    @Test
    fun bindRaster_should_ReportACountKey() {
        vm.open(fileName, png)
        place(0.1, 0.6)
        vm.editRaster { it.typed("100\"").calibrated() }
        place(0.1, 0.35)
        val countKey = ClosetFusion.registry.dimensions.first { it.kind == DimensionKind.COUNT }.key
        vm.bindRaster(TagChoice.Existing(countKey))
        assertEquals(RasterInputError.NotALengthKey(countKey), vm.state.value.raster!!.error)
    }
}
