// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.RetentionPolicy
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 6.1.2 (UX-62, UX-63, UX-135): whole-bundle copy through `TargetWriterRouter`, never an edit. */
class SketchBundleCopyTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val b = GraphId("bbbbbbbbbbbbbbbb")
    private val f1 = "ab12".repeat(16)
    private val f2 = "cd34".repeat(16)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Only `read` is used by the copy; the rest of the port is not reachable from it. */
    private class SourceStore(private val files: Map<String, ByteArray>?) : ProjectSidecarStore {
        override suspend fun read(slug: String): SidecarRead = files?.let { SidecarRead.Present(it) } ?: SidecarRead.Absent
        override suspend fun write(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome = unused()
        override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome = unused()
        override suspend fun writeConflict(slug: String, files: Map<String, ByteArray>): Either<DomainError, String> = unused()
        override suspend fun writeSourceSnapshot(slug: String, hash8: String, bytes: ByteArray): SidecarWriteOutcome = unused()
        override suspend fun readSourceSnapshot(slug: String, hash8: String): ByteArray? = unused()
        override suspend fun writeBackup(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = unused()
        override suspend fun readBackup(slug: String, sheetUuid: String): ByteArray? = unused()
        override suspend fun writeV2BeforeRestore(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = unused()
        override suspend fun deleteBackups(slug: String): Either<DomainError, Int> = unused()
        override suspend fun writeSheetConflictCopy(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = unused()
        override suspend fun pruneConflictCopies(slug: String, sheetUuid: String, unresolved: Set<String>): Either<DomainError, Int> = unused()
        override suspend fun retentionSize(slug: String): RetentionPolicy.Size = unused()
        override suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit> = unused()
        override suspend fun readV2SheetFile(slug: String, sheetUuid: String): ByteArray? = null
        override suspend fun readModel(slug: String): SidecarRead = unused()
        override suspend fun readSheetPrefix(slug: String, relPath: String, maxBytes: Int): ByteArray? = unused()
        override fun isAvailable(): Boolean = unused()
        override fun storageStatus(): dev.stapler.stelekit.platform.V2StorageStatus = unused()
        override suspend fun flushPending(): Int = unused()
        override suspend fun slugsOnDisk(): List<String> = unused()
        private fun <T> unused(): T = error("not used by SketchBundleCopy")
    }

    private val managers = mutableListOf<GraphManager>()

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(): GraphManager {
        val graphs = listOf(a, b).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = if (it == a) "A" else "B", addedAt = 0L) }
        val settings = MapSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = graphs)))
        return GraphManager(settings, DriverFactory(), StubFileSystem(), GraphBackend.IN_MEMORY).also { managers += it }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 4, 0, 0, 0, 4, 8, 6)

    private fun u(i: Int) = SceneUnit.ofInt(i)

    /** A v2 sheet referencing [f1] (present in the source) and [f2] (missing there). */
    private fun sheetBytes(): ByteArray {
        var scene = SketchSceneV2.empty()
        for ((i, f) in listOf(f1, f2).withIndex()) {
            scene = scene.add(ImageElement(ElementBase(ElementId("i$i")), u(i * 20), u(0), u(10), u(10), fileId = f)).getOrNull()!!
        }
        return SheetCodecV2.encode(SketchDocumentV2(scene, "s1")).fold({ error("$it") }, { it })
    }

    private val model = "{\"schema\":1,\"uuid\":\"u\",\"slug\":\"kitchen\",\"name\":\"Kitchen\"}\n".encodeToByteArray()
    private fun files() = mapOf("project-model.json" to model, "sheets/s1.v2.json" to sheetBytes())

    private class Fixture(val copy: SketchBundleCopy, val targetFs: FakeRelocationFileSystem, val sourceFs: FakeRelocationFileSystem)

    private fun fixture(
        manager: GraphManager,
        source: Map<String, ByteArray>? = files(),
        v2Available: Boolean = true,
        offGraphWrites: Boolean = true,
        png: ByteArray = this.png,
    ): Fixture {
        val sourceFs = FakeRelocationFileSystem().also { it.writeFileBytes("/data/a/assets/$f1.png", png) }
        val targetFs = FakeRelocationFileSystem()
        val router = TargetWriterRouter(
            graphManager = manager,
            locator = RegistryGraphLocator(manager.graphRegistry),
            capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = offGraphWrites),
            activeWriterFor = { MarkdownTargetWriter(targetFs, OffGraphTarget(a, EffectiveNotesPath("/data/a"), isActive = true), TargetWriterCapabilities(true), MarkdownTargetWriter.NoSymlinks) },
            offGraphWriterFor = { info -> MarkdownTargetWriter(targetFs, OffGraphTarget(info.id, info.effectiveNotesPath, isActive = false), TargetWriterCapabilities(true), MarkdownTargetWriter.NoSymlinks) },
        )
        val copy = SketchBundleCopy(router, SourceStore(source), sourceFs, "/data/a", { v2Available }, { if (it == a) "A" else "B" })
        return Fixture(copy, targetFs, sourceFs)
    }

    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    private fun <L, R> Either<L, R>.ok(): R = fold({ error("expected Right but was $it") }, { it })

    private fun <L, R> Either<L, R>.err(): L = fold({ it }, { error("expected Left but was $it") })

    @Test
    fun copy_should_CreateTheWholeBundleInTheInactiveGraph_And_ReportMissingImages() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val f = fixture(m)

        val result = f.copy.copy("kitchen", b).ok()

        assertEquals(SketchOpenTarget(b, "kitchen"), result.open)
        assertEquals("B", result.destinationName)
        assertEquals(listOf(f2), result.missingAssets)
        assertTrue(f.targetFs.readFileBytes("/data/${b.value}/assets/stelekit-sketch/kitchen/project-model.json")!!.contentEquals(model))
        assertTrue(f.targetFs.fileExists("/data/${b.value}/assets/stelekit-sketch/kitchen/sheets/s1.v2.json"))
        assertTrue(f.targetFs.readFileBytes("/data/${b.value}/assets/$f1.png")!!.contentEquals(png))
        assertTrue(!f.targetFs.fileExists("/data/${b.value}/assets/$f2.png"), "a missing image is reported, not invented")
    }

    @Test
    fun copy_should_NeverReadOutsideAssets_When_ASyncedSheetNamesAPathAsItsFileId() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val hostile = "../../x"
        val scene = SketchSceneV2.empty().add(ImageElement(ElementBase(ElementId("i0")), u(0), u(0), u(10), u(10), fileId = hostile)).getOrNull()!!
        val sheet = SheetCodecV2.encode(SketchDocumentV2(scene, "s1")).fold({ error("$it") }, { it })
        val f = fixture(m, source = mapOf("project-model.json" to model, "sheets/s1.v2.json" to sheet))
        f.sourceFs.writeFileBytes("/data/a/assets/$hostile.png", png)

        val result = f.copy.copy("kitchen", b).ok()

        assertEquals(listOf(hostile), result.missingAssets)
        assertTrue(!f.targetFs.fileExists("/data/${b.value}/assets/$hostile.png"))
        assertTrue(!f.targetFs.fileExists("/data/${b.value}/x.png"))
    }

    @Test
    fun copy_should_RefuseAnExistingSlug_ChangeNothing_And_OfferRename() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val f = fixture(m, source = validFiles())
        f.copy.copy("kitchen", b).ok()
        val before = f.targetFs.readFileBytes("/data/${b.value}/assets/stelekit-sketch/kitchen/project-model.json")!!

        val second = f.copy.copy("kitchen", b)

        assertEquals(SketchCopyError.SlugExists("kitchen"), second.err())
        assertTrue(f.targetFs.readFileBytes("/data/${b.value}/assets/stelekit-sketch/kitchen/project-model.json")!!.contentEquals(before))
        val dialog = SketchCopyDialogModel.of("kitchen", "B", second)
        assertEquals("\"kitchen\" already exists in B.", dialog.title)
        assertEquals(listOf(SketchCopyAction.RenameAndCopy, SketchCopyAction.Cancel), dialog.actions)

        val renamed = f.copy.copy("kitchen", b, asSlug = "kitchen-2").ok()
        assertEquals("kitchen-2", renamed.slug)
    }

    private fun validFiles(): Map<String, ByteArray> {
        val sheet = SketchTestSupport.sheet("s1").copy(schemaVersion = 2)
        val bundle = ProjectBundle(
            SketchTestSupport.project(slug = "kitchen"), listOf(sheet), emptyList(), emptyList(),
            mapOf(sheet.uuid to SheetPayload.V2Opaque.of(sheetBytes())),
        )
        return SidecarCodec.encode(bundle)
    }

    @Test
    fun renameAndCopy_should_GiveTheCopyItsOwnProjectAndSheetIdentity_SoTwoSlugsNeverCollide() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val f = fixture(m, source = validFiles())
        f.copy.copy("kitchen", b).ok()
        f.copy.copy("kitchen", b, asSlug = "kitchen-2").ok()

        fun decoded(slug: String) = SidecarCodec.decode(
            f.targetFs.allFilePaths().filter { it.contains("/stelekit-sketch/$slug/") }
                .associate { it.substringAfter("/stelekit-sketch/$slug/") to f.targetFs.readFileBytes(it)!! },
        ).getOrNull()!!
        val first = decoded("kitchen")
        val second = decoded("kitchen-2")

        assertEquals("kitchen-2", second.project.slug)
        assertTrue(first.project.uuid != second.project.uuid, "the project uuid is not reused")
        assertTrue(first.sheets.single().uuid != second.sheets.single().uuid, "the sheet uuid is not reused")
        val secondSheet = second.sheets.single().uuid
        assertTrue(f.targetFs.fileExists("/data/${b.value}/assets/stelekit-sketch/kitchen-2/sheets/${secondSheet.value}.v2.json"), "the sheet file follows the new uuid")
        val doc = SheetCodecV2.decode((second.sheetPayloads.getValue(secondSheet) as SheetPayload.V2Opaque).bytes).getOrNull()!!
        assertEquals(secondSheet.value, doc.sheetUuid, "the uuid inside the v2 file follows too")
    }

    @Test
    fun copy_should_RefuseADifferingImage_And_LeaveNoPartialBundle() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val f = fixture(m)
        f.targetFs.writeFileBytes("/data/${b.value}/assets/$f1.png", byteArrayOf(5, 5, 5))

        val result = f.copy.copy("kitchen", b)

        assertEquals(SketchCopyError.AssetConflict("$f1.png"), result.err())
        assertTrue(f.targetFs.readFileBytes("/data/${b.value}/assets/$f1.png")!!.contentEquals(byteArrayOf(5, 5, 5)))
        assertTrue(!f.targetFs.directoryExists("/data/${b.value}/assets/stelekit-sketch/kitchen"))
    }

    @Test
    fun copy_should_RefuseWhenTheSourceHasNoFileStorage_And_WriteNothing() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        val f = fixture(m, v2Available = false)

        assertEquals(SketchCopyError.V2StorageUnavailable, f.copy.copy("kitchen", b).err())
        assertTrue(!f.targetFs.directoryExists("/data/${b.value}/assets/stelekit-sketch"))
    }

    @Test
    fun copy_should_ReportAMissingSource_And_AnUnwritableTarget() = realTime {
        val m = newManager().also { it.awaitPendingMigration() }
        assertEquals(SketchCopyError.SourceMissing("kitchen"), fixture(m, source = null).copy.copy("kitchen", b).err())

        val refused = fixture(m, offGraphWrites = false).copy.copy("kitchen", b).err()
        val failed = assertIs<SketchCopyError.Failed>(refused)
        assertIs<DomainError.MergeError.WriteRefused>(failed.error)
        assertEquals(listOf(SketchCopyAction.Retry, SketchCopyAction.Cancel), SketchCopyDialogModel.of("kitchen", "B", refused.left()).actions)
    }

    @Test
    fun dialogModel_should_NameTheDestination_Offer_Open_And_NoteMissingImages() {
        val ok = SketchCopyResult("kitchen", b, "B", SketchOpenTarget(b, "kitchen"), listOf(f2))
        val dialog = SketchCopyDialogModel.of("kitchen", "B", ok.right())
        assertEquals("Copied to B.", dialog.title)
        assertEquals(listOf(SketchCopyAction.Open(SketchOpenTarget(b, "kitchen")), SketchCopyAction.Done), dialog.actions)
        assertTrue(dialog.detail!!.contains("1 image"))
        assertEquals(null, SketchCopyDialogModel.of("kitchen", "B", ok.copy(missingAssets = emptyList()).right()).detail)
    }

    @Test
    fun bundleFiles_should_AllowARetry_AfterAFailedWriteRolledBack() {
        class FlakyModelFs : FakeRelocationFileSystem() {
            var failModel = true

            /** Like `mkdirs`: creating a nested folder creates every parent too. */
            override fun createDirectory(path: String): Boolean {
                var p = path
                while (p.contains('/') && p.length > 1) { super.createDirectory(p); p = p.substringBeforeLast('/') }
                return true
            }

            override fun writeFileBytes(path: String, data: ByteArray): Boolean =
                !(failModel && path.endsWith("project-model.json")) && super.writeFileBytes(path, data)
        }
        val fs = FlakyModelFs()
        val bundle = SketchBundle("kitchen", files(), listOf(SketchAsset("$f1.png", png)), emptyList())

        assertIs<DomainError.FileSystemError.WriteFailed>(SketchBundleFiles(fs, "/data/b").write(bundle).err())
        fs.failModel = false
        val retry = SketchBundleFiles(fs, "/data/b").write(bundle).ok()

        assertIs<SketchBundleOutcome.Created>(retry)
    }

    @Test
    fun bundleFiles_should_RemoveEverythingItCreated_When_AWriteFails() {
        class FailingModelFs : FakeRelocationFileSystem() {
            override fun writeFileBytes(path: String, data: ByteArray): Boolean = !path.endsWith("project-model.json") && super.writeFileBytes(path, data)
        }
        val fs = FailingModelFs()
        val bundle = SketchBundle("kitchen", files(), listOf(SketchAsset("$f1.png", png)), emptyList())

        val result = SketchBundleFiles(fs, "/data/b").write(bundle)

        assertIs<DomainError.FileSystemError.WriteFailed>(result.err())
        assertTrue(!fs.fileExists("/data/b/assets/$f1.png"), "the image this call created is removed again")
        assertTrue(!fs.fileExists("/data/b/assets/stelekit-sketch/kitchen/sheets/s1.v2.json"))
    }

    @Test
    fun layout_constants_match_the_projectbridge_originals() {
        assertEquals(SidecarCodec.MODEL_PATH, SKETCH_BUNDLE_MODEL_PATH)
        assertEquals(dev.stapler.stelekit.projectbridge.FileProjectSidecarStore.ROOT_DIR, dev.stapler.stelekit.platform.SidecarLiveSet.ROOT_DIR)
    }
}
