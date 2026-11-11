// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketchimport

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudRig
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportFlowTest {
    private val notes = EffectiveNotesPath("/graph")
    private val registry = ClosetFusion.registry
    private val project = SketchTestSupport.project(uuid = "p1", slug = "proj-0a1b2c")
    private val svg = ClosetFixtureText.load("closet-elevation.svg").encodeToByteArray()
    private val untaggable = """<svg xmlns="http://www.w3.org/2000/svg"><text x="10" y="10">50"</text></svg>""".encodeToByteArray()

    private lateinit var driver: SqlDriver
    private lateinit var assetRepo: SqlDelightAssetRepository
    private lateinit var repos: SketchRepositories
    private lateinit var fs: FakeFileSystem
    private lateinit var assets: AssetIndexService
    private lateinit var flow: ImportFlow

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        assetRepo = SqlDelightAssetRepository(SteleDatabase(driver))
        repos = inMemorySketchRepositories()
        repos.projects.saveProject(project)
        fs = FakeFileSystem()
        assets = AssetIndexService(assetRepo)
        flow = ImportFlow(assets, repos, fs, notes, writeActor = null)
    }

    @AfterTest
    fun teardown() {
        assets.close()
        driver.close()
    }

    private suspend fun rows() = assetRepo.getAssets(limit = 100, offset = 0).first().getOrNull().orEmpty()

    private suspend fun dims() = repos.dimensions.getDimensions(ProjectUuid("p1")).first().getOrNull().orEmpty()

    private suspend fun sheets() = repos.sheets.getSheets(ProjectUuid("p1")).first().getOrNull()!!.items

    @Test
    fun prepare_should_BindClosetLabelsAndWriteNothing() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "closet-elevation.svg", svg).getOrNull()!!
        assertEquals(10, preview.labels.bound.size)
        assertEquals(0, preview.taggingItems.size)
        assertEquals(0, rows().size)
        assertEquals(0, sheets().size)
        assertEquals(0, dims().size)
    }

    @Test
    fun prepare_should_Reject_UnsupportedTypeAndEmptyBytes() {
        assertIs<ImportError.UnsupportedType>(flow.prepare(registry, "scan.pdf", byteArrayOf(1, 2)).leftOrNull())
        assertIs<ImportError.Failed>(flow.prepare(registry, "scan.png", byteArrayOf(1, 2)).leftOrNull())
        assertEquals(ImportError.Empty, flow.prepare(registry, "a.svg", ByteArray(0)).leftOrNull())
    }

    @Test
    fun commit_should_CopyToDrawingsDir_RegisterDrawing_AndWriteSheetAndSvgObservations() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "closet-elevation.svg", svg).getOrNull()!!

        val outcome = flow.commit(ProjectUuid("p1"), preview, registry, emptyMap()).getOrNull()!!

        assertEquals("/graph/assets/stelekit-sketch/proj-0a1b2c/drawings/${preview.contentHash.take(8)}.svg", outcome.assetPath)
        assertTrue(fs.readFileBytes(outcome.assetPath)!!.contentEquals(svg))
        val row = rows().single()
        assertEquals(AssetMediaType.DRAWING, row.mediaType)
        assertEquals(outcome.assetPath, row.filePath)
        assertEquals(row.uuid.value, sheets().single().sourceAssetUuid)
        val saved = dims()
        assertEquals(10, saved.size)
        assertTrue(saved.all { it.sourceKind == "svg" && it.sourceRef.startsWith("svg:") })
    }

    @Test
    fun commit_should_LeaveNoImageRow_When_SameBytesWereAlreadyIndexedAsPhoto() = runBlocking<Unit> {
        fs.writeFileBytes("/graph/assets/closet.svg", svg)
        assets.registerAsset("/graph/assets/closet.svg", notes, writeActor = null, fileSystem = fs, mimeHint = "image/png")
        assertEquals(AssetMediaType.IMAGE, rows().single().mediaType)
        val preview = flow.prepare(registry, "closet-elevation.svg", svg).getOrNull()!!

        flow.commit(ProjectUuid("p1"), preview, registry, emptyMap()).getOrNull()!!

        val all = rows()
        assertEquals(1, all.size)
        assertEquals(0, all.count { it.mediaType == AssetMediaType.IMAGE })
        assertEquals(AssetMediaType.DRAWING, all.single().mediaType)
    }

    @Test
    fun commit_should_MakeZeroCloudCalls_ForImportedDrawing() = runBlocking<Unit> {
        val rig = CloudRig(scaled = CloudRig.SMALL_JPEG, sessionCap = 100).also {
            it.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)
        }
        val preview = flow.prepare(registry, "closet-elevation.svg", svg).getOrNull()!!
        val outcome = flow.commit(ProjectUuid("p1"), preview, registry, emptyMap()).getOrNull()!!
        // Positive control: an ordinary photo in the same index does reach the mock endpoint.
        fs.writeFileBytes("/graph/assets/photo.jpg", CloudRig.SMALL_JPEG)
        assets.registerAsset("/graph/assets/photo.jpg", notes, writeActor = null, fileSystem = fs)
        rig.fs.writeFileBytes(outcome.assetPath, CloudRig.SMALL_JPEG)
        rig.fs.writeFileBytes("/graph/assets/photo.jpg", CloudRig.SMALL_JPEG)

        val service = AssetPipelineService(
            PluginRegistry().also { it.register(rig.plugin) }, assetRepo,
            DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
        )
        service.scheduleBackfill()
        withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished || it is PipelineProgress.Paused } }

        assertEquals(1, rig.http.requests.size, "only the photo was sent")
        assertEquals(AssetMediaType.DRAWING, rows().first { it.filePath == outcome.assetPath }.mediaType)
    }

    @Test
    fun commit_should_SaveTaggedLabelAsSvgObservation_And_SkipUntagged() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "odd.svg", untaggable).getOrNull()!!
        val item = preview.taggingItems.single()
        val key = registry.dimensions.first { it.kind == DimensionKind.LENGTH }.key

        flow.commit(ProjectUuid("p1"), preview, registry, mapOf(item.ref to key)).getOrNull()!!

        val d = dims().single()
        assertEquals(key, d.key)
        assertEquals(item.value, d.value)
        assertEquals("svg", d.sourceKind)
        assertEquals(item.ref, d.sourceRef)
    }

    @Test
    fun commit_should_Fail_When_ProjectMissing_And_WriteNothing() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "closet-elevation.svg", svg).getOrNull()!!
        val result = flow.commit(ProjectUuid("nope"), preview, registry, emptyMap())
        assertEquals(ImportError.ProjectMissing, result.leftOrNull())
        assertEquals(0, rows().size)
    }

    private val png = pngHeader(1000, 500)
    private val widthKey get() = registry.dimensions.first { it.kind == DimensionKind.LENGTH }.key

    @Test
    fun prepare_should_ReadPngSizeWithNoLabels() {
        val preview = flow.prepare(registry, "sheet.png", png).getOrNull()!!
        assertEquals(PngSize(1000, 500), preview.raster)
        assertEquals(0, preview.taggingItems.size)
    }

    @Test
    fun commit_should_RegisterPngAsDrawing_AndSaveOnlyTheUserReference() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "sheet.png", png).getOrNull()!!
        val ref = RasterReference(widthKey, NormalizedPoint(0.0, 0.5), NormalizedPoint(1.0, 0.5), Rational(96))

        val outcome = flow.commit(ProjectUuid("p1"), preview, registry, emptyMap(), ref).getOrNull()!!

        assertEquals(AssetMediaType.DRAWING, rows().single().mediaType)
        val d = dims().single()
        assertEquals("png", d.sourceKind)
        assertEquals(Rational(96), d.value)
        assertEquals(1, outcome.dimensionCount)
    }

    @Test
    fun commit_should_SaveNoDimension_When_PngHasNoReference() = runBlocking<Unit> {
        val preview = flow.prepare(registry, "sheet.png", png).getOrNull()!!
        assertEquals(0, flow.commit(ProjectUuid("p1"), preview, registry, emptyMap()).getOrNull()!!.dimensionCount)
    }

    @Test
    fun commit_should_UpgradePhotoToDrawing_When_SamePngBytesImportedAsDrawing() = runBlocking<Unit> {
        fs.writeFileBytes("/graph/assets/sheet.png", png)
        assets.registerAsset("/graph/assets/sheet.png", notes, writeActor = null, fileSystem = fs)
        assertEquals(AssetMediaType.IMAGE, rows().single().mediaType)

        flow.commit(ProjectUuid("p1"), flow.prepare(registry, "sheet.png", png).getOrNull()!!, registry, emptyMap()).getOrNull()!!

        assertEquals(listOf(AssetMediaType.DRAWING), rows().map { it.mediaType })
    }

    @Test
    fun commit_should_MakeZeroCloudCalls_ForImportedPng_ThroughBackfill() = runBlocking<Unit> {
        val rig = CloudRig(scaled = CloudRig.SMALL_JPEG, sessionCap = 100).also {
            it.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)
        }
        fs.writeFileBytes("/graph/assets/sheet.png", png)
        assets.registerAsset("/graph/assets/sheet.png", notes, writeActor = null, fileSystem = fs)
        val outcome = flow.commit(ProjectUuid("p1"), flow.prepare(registry, "sheet.png", png).getOrNull()!!, registry, emptyMap()).getOrNull()!!
        rig.fs.writeFileBytes(outcome.assetPath, CloudRig.SMALL_JPEG)
        rig.fs.writeFileBytes("/graph/assets/sheet.png", CloudRig.SMALL_JPEG)

        val service = AssetPipelineService(
            PluginRegistry().also { it.register(rig.plugin) }, assetRepo,
            DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
        )
        service.scheduleBackfill()
        withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished || it is PipelineProgress.Paused } }

        assertEquals(0, rig.http.requests.size, "an imported PNG drawing is never sent")
    }
}
