package dev.stapler.stelekit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.db.ImageImportService
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException

/**
 * TC-PHOTO-001..005: Android instrumented tests for the image import pipeline.
 *
 * Verifies that [ImageImportService.import] works correctly on-device using a real
 * Java [File]-backed filesystem. This catches Android-specific failures that in-process
 * JVM tests miss: file permission boundaries, cacheDir vs externalCacheDir behavior,
 * and path resolution differences between the emulator and desktop JVM.
 *
 * Runs on a real (or emulated) Android device via connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class PhotoInsertAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graphDir: File
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        graphDir = File(context.cacheDir, "test_graph_${System.currentTimeMillis()}").also { it.mkdirs() }
        tempDir = File(context.cacheDir, "test_temp_${System.currentTimeMillis()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        graphDir.deleteRecursively()
        tempDir.deleteRecursively()
    }

    private data class TestServices(
        val service: ImageImportService,
        val imageRepo: InMemoryImageAnnotationRepository,
        val blockRepo: InMemoryBlockRepository,
    )

    private fun buildServices(): TestServices {
        val fs = JavaFileSystem()
        val imageRepo = InMemoryImageAnnotationRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = ImageImportService(
            fileSystem = fs,
            imageAnnotationRepository = imageRepo,
            blockRepository = blockRepo,
            sidecarManager = ImageSidecarManager(fs),
        )
        return TestServices(service, imageRepo, blockRepo)
    }

    // TC-PHOTO-001: Import pipeline copies image bytes to <graph>/assets/images/
    @Test
    fun importCopiesBytesToAssetsDir() = runTest {
        val (service, _, _) = buildServices()
        val photoFile = File(tempDir, "photo.jpg").also { it.writeBytes("FAKE_JPEG".encodeToByteArray()) }

        val result = service.import(
            tempFile = PlatformImageFile(photoFile.absolutePath),
            graphPath = graphDir.absolutePath,
            pageUuid = PageUuid("page-001"),
            source = ImageSource.FILE,
        )

        assertTrue("import must succeed but got: $result", result.isRight())
        val annotation = requireNotNull(result.getOrNull()) { "getOrNull() returned null for Right" }
        assertTrue("filePath must be under assets/images/", annotation.filePath.contains("assets/images/"))
        assertTrue("copied image must exist at ${annotation.filePath}", File(annotation.filePath).exists())
        assertEquals("byte content must be preserved", "FAKE_JPEG", File(annotation.filePath).readText())
    }

    // TC-PHOTO-002: Import pipeline creates annotation row in the repository
    @Test
    fun importCreatesAnnotationInRepository() = runTest {
        val (service, imageRepo, _) = buildServices()
        val photoFile = File(tempDir, "photo.jpg").also { it.writeBytes("FAKE_JPEG".encodeToByteArray()) }

        val result = service.import(
            tempFile = PlatformImageFile(photoFile.absolutePath),
            graphPath = graphDir.absolutePath,
            pageUuid = PageUuid("page-001"),
            source = ImageSource.CAMERA,
        )

        assertTrue("import must succeed but got: $result", result.isRight())
        val annotation = requireNotNull(result.getOrNull()) { "getOrNull() returned null for Right" }

        val fromRepo = imageRepo.getImageAnnotationByUuid(annotation.uuid).first().getOrNull()
        assertNotNull("ImageAnnotation must be persisted in repository", fromRepo)
        assertEquals("page-001", fromRepo!!.pageUuid)
        assertEquals(graphDir.absolutePath, fromRepo.graphPath)
        assertEquals(ImageSource.CAMERA, fromRepo.source)
    }

    // TC-PHOTO-003: Import pipeline creates an image_annotation Block on the target page
    @Test
    fun importCreatesImageAnnotationBlock() = runTest {
        val (service, _, blockRepo) = buildServices()
        val photoFile = File(tempDir, "photo.jpg").also { it.writeBytes("FAKE_JPEG".encodeToByteArray()) }

        val result = service.import(
            tempFile = PlatformImageFile(photoFile.absolutePath),
            graphPath = graphDir.absolutePath,
            pageUuid = PageUuid("page-002"),
            source = ImageSource.FILE,
        )

        assertTrue("import must succeed but got: $result", result.isRight())
        val annotation = requireNotNull(result.getOrNull()) { "getOrNull() returned null for Right" }

        val blocks = blockRepo.getBlocksForPage(PageUuid("page-002")).first().getOrNull()
        assertNotNull("Block list must be retrievable", blocks)
        val imageBlock = blocks!!.find { it.uuid.value == annotation.blockUuid }
        assertNotNull("image_annotation block must exist for page page-002", imageBlock)
        assertEquals(BlockType.ImageAnnotation, imageBlock!!.blockType)
        assertTrue("block content must reference the image path", imageBlock.content.contains("assets/images/"))
        assertEquals("image-id property must match annotation UUID", annotation.uuid, imageBlock.properties["image-id"])
    }

    // TC-PHOTO-004: Import returns Either.Left (not a crash) when source file is missing
    @Test
    fun importReturnsErrorForMissingSourceFile() = runTest {
        val (service, _, _) = buildServices()

        val result = service.import(
            tempFile = PlatformImageFile("${tempDir.absolutePath}/nonexistent.jpg"),
            graphPath = graphDir.absolutePath,
            pageUuid = PageUuid("page-001"),
        )

        assertTrue("Missing file must return Left, not throw — got: $result", result.isLeft())
    }

    // TC-PHOTO-005: AndroidMediaAttachmentService.hasClipboardImage returns false for empty clipboard
    @Test
    fun clipboardImageDetectionReturnsFalseForEmptyClipboard() {
        assumeTrue(
            "clearPrimaryClip requires API 28+",
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P,
        )
        val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
        checkNotNull(clipboardManager) { "ClipboardManager must not be null" }
        clipboardManager.clearPrimaryClip()

        val service = dev.stapler.stelekit.service.AndroidMediaAttachmentService(
            context = context,
            launchGalleryPicker = { null },
        )

        assertFalse("hasClipboardImage must be false when clipboard is empty", service.hasClipboardImage())
    }
}

/**
 * Minimal [FileSystem] backed by [java.io.File] for Android instrumented tests.
 *
 * Avoids the SAF machinery in [dev.stapler.stelekit.platform.PlatformFileSystem] and the
 * in-memory simplifications in [FakeFileSystem], giving tests real on-device file I/O.
 * Only [IOException] is suppressed; all other exceptions propagate to surface root causes.
 */
private class JavaFileSystem : FileSystem {
    override fun getDefaultGraphPath(): String = ""
    override fun expandTilde(path: String): String = path
    override fun readFile(path: String): String? = try { File(path).readText() } catch (_: IOException) { null }
    override fun writeFile(path: String, content: String): Boolean = try {
        File(path).apply { parentFile?.mkdirs() }.writeText(content); true
    } catch (_: IOException) { false }
    override fun readFileBytes(path: String): ByteArray? = try { File(path).readBytes() } catch (_: IOException) { null }
    override fun writeFileBytes(path: String, data: ByteArray): Boolean = try {
        File(path).apply { parentFile?.mkdirs() }.writeBytes(data); true
    } catch (_: IOException) { false }
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun directoryExists(path: String): Boolean = File(path).isDirectory
    override fun createDirectory(path: String): Boolean = File(path).mkdirs()
    override fun deleteFile(path: String): Boolean = File(path).delete()
    override fun listFiles(path: String): List<String> = File(path).listFiles()?.map { it.name } ?: emptyList()
    override fun listDirectories(path: String): List<String> =
        File(path).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = File(path).takeIf { it.exists() }?.lastModified()
}
