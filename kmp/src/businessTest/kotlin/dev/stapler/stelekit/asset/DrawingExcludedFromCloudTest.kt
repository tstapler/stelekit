@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudRig
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.wiring.FakeSettings
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 0.3: drawings never reach the cloud image pipeline, by path, by hash identity and by file
 * content. Every assertion re-reads the persisted row from a real in-memory SQLite database.
 */
class DrawingExcludedFromCloudTest {
    private val notes = EffectiveNotesPath("/graph")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val svgPlain = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>".encodeToByteArray()
    private val svgExport = (
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><metadata>{\"kind\":\"${DrawingContentSniffer.MARKER}\"}</metadata></svg>"
        ).encodeToByteArray()
    private val sketch = "/graph/assets/stelekit-sketch/proj-1"

    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        repo = SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @AfterTest
    fun teardown() {
        AssetBuildFlags.internalBuild = false
        driver.close()
    }

    /** FakeFileSystem that reports real subdirectories derived from the written paths. */
    private class TreeFileSystem : FakeFileSystem() {
        private val paths = mutableSetOf<String>()
        fun put(path: String, bytes: ByteArray) { paths += path; writeFileBytes(path, bytes) }
        override fun listDirectories(path: String): List<String> {
            val prefix = "$path/"
            return paths.filter { it.startsWith(prefix) && it.removePrefix(prefix).contains('/') }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }
    }

    private fun rig() = CloudRig(scaled = CloudRig.SMALL_JPEG, sessionCap = 100).also {
        it.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)
    }

    private suspend fun drain(rig: CloudRig): PipelineProgress {
        val service = AssetPipelineService(
            PluginRegistry().also { it.register(rig.plugin) }, repo,
            DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
        )
        service.scheduleBackfill()
        return withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished || it is PipelineProgress.Paused } }
    }

    private suspend fun rows() = repo.getAssets(limit = 100, offset = 0).first().getOrNull().orEmpty()

    private suspend fun backfill(fs: TreeFileSystem) {
        val service = AssetIndexService(repo)
        service.startBackfill(notes, writeActor = null, fileSystem = fs)
        withTimeout(30_000) { service.progress.first { it is AssetIndexProgress.Finished } }
        service.close()
    }

    @Test
    fun startBackfill_should_ClassifyDrawingsAndSkipSketchManagedFiles_And_MakeNoCloudCall() = runBlocking<Unit> {
        val rig = rig()
        val fs = TreeFileSystem()
        fs.put("$sketch/drawings/plan.svg", svgPlain)
        fs.put("$sketch/drawings/scan.png", png)
        fs.put("$sketch/project-model.json", "{}".encodeToByteArray())
        fs.put("$sketch/sheets/sheet-1.json", "{}".encodeToByteArray())
        fs.put("$sketch/sources/yaml-ab12cd34.yaml", "a: 1".encodeToByteArray())
        fs.put("$sketch/sheets/sheet-1.json.tmp", "{".encodeToByteArray())
        fs.put("$sketch/exports/digest.md", "# digest".encodeToByteArray())
        fs.put("$sketch/exports/sketch.svg", svgExport)
        // Positive control: an ordinary photo in the same graph does reach the (mock) cloud endpoint.
        fs.put("/graph/assets/photo.jpg", CloudRig.SMALL_JPEG)
        rig.fs.writeFileBytes("/graph/assets/photo.jpg", CloudRig.SMALL_JPEG)
        listOf("plan.svg", "scan.png").forEach { rig.fs.writeFileBytes("$sketch/drawings/$it", png) }

        backfill(fs)
        val state = drain(rig)

        val byPath = rows().associateBy { it.filePath }
        assertEquals(
            setOf("$sketch/drawings/plan.svg", "$sketch/drawings/scan.png", "/graph/assets/photo.jpg"),
            byPath.keys, "only drawings/ and the photo become rows",
        )
        assertEquals(AssetMediaType.DRAWING, byPath.getValue("$sketch/drawings/plan.svg").mediaType)
        assertEquals(AssetMediaType.DRAWING, byPath.getValue("$sketch/drawings/scan.png").mediaType)
        assertEquals(AssetMediaType.IMAGE, byPath.getValue("/graph/assets/photo.jpg").mediaType)
        assertEquals(1, rig.http.requests.size, "only the photo was sent; the drawings made zero provider calls")
        assertEquals(PipelineProgress.Finished::class, state::class)
    }

    @Test
    fun registerAsset_should_UpgradeIndexedImageToDrawing_When_SameHashRegisteredWithDrawingIntent() = runBlocking<Unit> {
        val rig = rig()
        val fs = TreeFileSystem()
        fs.put("/graph/assets/pic.png", png)
        fs.put("$sketch/drawings/pic.png", png)
        val service = AssetIndexService(repo)
        val photo = service.registerAsset("/graph/assets/pic.png", notes, writeActor = null, fileSystem = fs).getOrNull()!!
        assertEquals(AssetMediaType.IMAGE, repo.getAssetByUuid(photo.uuid).first().getOrNull()!!.mediaType)

        val again = service.registerAsset(
            "$sketch/drawings/pic.png", notes, writeActor = null, fileSystem = fs, intent = AssetIntent.DRAWING,
        ).getOrNull()!!

        assertEquals(photo.uuid, again.uuid, "hash dedupe returns the existing row")
        assertEquals(AssetMediaType.DRAWING, repo.getAssetByUuid(photo.uuid).first().getOrNull()!!.mediaType)
        assertEquals(1L, repo.countAssets().getOrNull())
        rig.fs.writeFileBytes("/graph/assets/pic.png", CloudRig.SMALL_JPEG)
        drain(rig)
        assertEquals(0, rig.http.requests.size, "the upgraded row makes zero provider calls")
        assertEquals(AssetMediaType.DRAWING, repo.getAssetByUuid(photo.uuid).first().getOrNull()!!.mediaType)
        service.close()
    }

    @Test
    fun registerAsset_should_NotDowngradeDrawing_When_SameBytesRegisteredAsPlainImage() = runBlocking<Unit> {
        val fs = TreeFileSystem()
        fs.put("/graph/assets/pic.png", png)
        fs.put("$sketch/drawings/pic.png", png)
        val service = AssetIndexService(repo)
        val drawing = service.registerAsset(
            "$sketch/drawings/pic.png", notes, writeActor = null, fileSystem = fs, intent = AssetIntent.DRAWING,
        ).getOrNull()!!

        service.registerAsset("/graph/assets/pic.png", notes, writeActor = null, fileSystem = fs)

        assertEquals(AssetMediaType.DRAWING, repo.getAssetByUuid(drawing.uuid).first().getOrNull()!!.mediaType)
        service.close()
    }

    @Test
    fun registerAsset_should_RefuseSketchManagedFiles() = runBlocking<Unit> {
        val fs = TreeFileSystem()
        fs.put("$sketch/project-model.json", "{}".encodeToByteArray())
        val service = AssetIndexService(repo)

        val result = service.registerAsset("$sketch/project-model.json", notes, writeActor = null, fileSystem = fs)

        assertNull(result.getOrNull())
        assertEquals(0L, repo.countAssets().getOrNull())
        service.close()
    }

    @Test
    fun fromStored_should_ReadUnknownValueAsFile_And_RoundTripKnownOnes() = runBlocking<Unit> {
        assertEquals(AssetMediaType.FILE, AssetMediaType.fromStored("HOLOGRAM"))
        AssetMediaType.entries.forEach { assertEquals(it, AssetMediaType.fromStored(it.name)) }

        val fs = TreeFileSystem()
        fs.put("/graph/assets/pic.png", png)
        val service = AssetIndexService(repo)
        val entry = service.registerAsset("/graph/assets/pic.png", notes, writeActor = null, fileSystem = fs).getOrNull()!!
        driver.execute(null, "UPDATE asset_index SET media_type = 'HOLOGRAM' WHERE uuid = ?", 1) { bindString(0, entry.uuid.value) }

        assertEquals(AssetMediaType.FILE, repo.getAssetByUuid(entry.uuid).first().getOrNull()!!.mediaType)
        assertEquals(AssetMediaType.FILE, rows().single().mediaType, "list reads decode the same way")
        service.close()
    }

    @Test
    fun registerAttachment_should_ClassifyExportedSvgAsDrawing_And_PlainSvgStaysImage() = runBlocking<Unit> {
        val rig = rig()
        val fs = TreeFileSystem()
        AssetBuildFlags.internalBuild = true
        val settings = FakeSettings().apply { putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true) }
        val controller = AssetIndexingController(settings, AssetIndexService(repo), toggleVisibleProvider = { true })
        controller.onGraphReady(notes, writeActor = null, fileSystem = fs)
        fs.put("/graph/assets/sketch.svg", svgExport)
        fs.put("/graph/assets/plain.svg", svgPlain)

        controller.registerAttachment(AttachmentResult("../assets/sketch.svg", "sketch.svg"))
        controller.registerAttachment(AttachmentResult("../assets/plain.svg", "plain.svg"))

        val byPath = rows().associateBy { it.filePath }
        assertEquals(AssetMediaType.DRAWING, byPath.getValue("/graph/assets/sketch.svg").mediaType)
        assertEquals(AssetMediaType.IMAGE, byPath.getValue("/graph/assets/plain.svg").mediaType, "the marker is the only difference")
        rig.fs.writeFileBytes("/graph/assets/sketch.svg", CloudRig.SMALL_JPEG)
        drain(rig)
        assertEquals(0, rig.http.requests.size, "exported SVG made no provider call")
        assertEquals(false, rig.plugin.canProcess(byPath.getValue("/graph/assets/sketch.svg")))
        controller.close()
    }
}
