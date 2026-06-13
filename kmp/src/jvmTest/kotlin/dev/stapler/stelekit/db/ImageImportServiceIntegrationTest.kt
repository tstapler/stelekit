package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ImageImportService.import] pipeline (Story 2.3.4).
 *
 * Verifies that after a successful import:
 * 1. The image bytes are copied from the temp path to `<graph>/assets/images/`
 * 2. A JSON sidecar exists at `<graph>/.stelekit/images/<uuid>.measure.json`
 * 3. An [ImageAnnotation] row exists in the repository
 * 4. An `image_annotation` Block with matching blockUuid exists in the block repository
 *
 * Uses [FakeFileSystem] to avoid real disk I/O and in-memory repositories to avoid
 * requiring a SQLite driver in tests.
 */
@OptIn(DirectRepositoryWrite::class)
class ImageImportServiceIntegrationTest {

    private val graphPath = "/graph"
    private val pageUuid = "page-001"

    // Fake image content — ASCII-only so FakeFileSystem's string round-trip preserves it exactly
    private val fakePngBytes = "FAKE_IMAGE_DATA_FOR_TESTING".encodeToByteArray()

    private data class Services(
        val service: ImageImportService,
        val imageRepo: InMemoryImageAnnotationRepository,
        val blockRepo: InMemoryBlockRepository,
        val fs: FakeFileSystem,
    )

    private fun build(fs: FakeFileSystem = FakeFileSystem()): Services {
        val imageRepo = InMemoryImageAnnotationRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = ImageImportService(
            fileSystem = fs,
            imageAnnotationRepository = imageRepo,
            blockRepository = blockRepo,
            sidecarManager = ImageSidecarManager(fs),
        )
        return Services(service, imageRepo, blockRepo, fs)
    }

    /** Unwrap a successful import result; fails the test if the result is Left. */
    private fun Either<*, ImageAnnotation>.requireSuccess(): ImageAnnotation {
        assertIs<Either.Right<ImageAnnotation>>(this)
        return value
    }

    @Test
    fun `import writes image bytes to assets-images directory`() = runBlocking<Unit> {
        val (service, _, _, fs) = build()
        fs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val annotation = service.import(PlatformImageFile("/tmp/photo.jpg"), graphPath, pageUuid)
            .requireSuccess()

        assertTrue(annotation.filePath.contains("assets/images/"))
        assertTrue(fs.fileExists(annotation.filePath), "Image not at ${annotation.filePath}")
        val stored = fs.readFileBytes(annotation.filePath)
        assertNotNull(stored)
        assertTrue(stored.contentEquals(fakePngBytes))
    }

    @Test
    fun `import creates image_annotation row in repository`() = runBlocking<Unit> {
        val (service, imageRepo, _, fs) = build()
        fs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val annotation = service.import(
            PlatformImageFile("/tmp/photo.jpg"), graphPath, pageUuid, ImageSource.FILE
        ).requireSuccess()

        val fromRepo = imageRepo.getImageAnnotationByUuid(annotation.uuid).first().getOrNull()
        assertNotNull(fromRepo, "ImageAnnotation not in repository")
        assertEquals(annotation.uuid, fromRepo.uuid)
        assertEquals(pageUuid, fromRepo.pageUuid)
        assertEquals(graphPath, fromRepo.graphPath)
        assertEquals(ImageSource.FILE, fromRepo.source)
    }

    @Test
    fun `import writes sidecar JSON file`() = runBlocking<Unit> {
        val (service, _, _, fs) = build()
        fs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val annotation = service.import(PlatformImageFile("/tmp/photo.jpg"), graphPath, pageUuid)
            .requireSuccess()

        val sidecarPath = "$graphPath/.stelekit/images/${annotation.uuid}.measure.json"
        assertTrue(fs.fileExists(sidecarPath), "Sidecar JSON not found at $sidecarPath")
        val content = fs.readFile(sidecarPath)
        assertNotNull(content)
        assertTrue(content.contains(annotation.uuid))
    }

    @Test
    fun `import creates image_annotation block on the target page`() = runBlocking<Unit> {
        val (service, _, blockRepo, fs) = build()
        fs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val annotation = service.import(PlatformImageFile("/tmp/photo.jpg"), graphPath, pageUuid)
            .requireSuccess()

        val blocks = blockRepo.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull()
        assertNotNull(blocks)
        val imageBlock = blocks.find { it.uuid.value == annotation.blockUuid }
        assertNotNull(imageBlock, "image_annotation block not found for page $pageUuid")
        assertEquals("image_annotation", imageBlock.blockType)
        assertTrue(imageBlock.content.contains("assets/images/"))
        assertEquals(annotation.uuid, imageBlock.properties["image-id"])
    }

    @Test
    fun `import preserves EXIF sensor metadata in annotation`() = runBlocking<Unit> {
        val (service, imageRepo, _, fs) = build()
        fs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val tempFile = PlatformImageFile(
            path = "/tmp/photo.jpg",
            focalLengthMm = 4.2,
            focalLength35mmEq = 26.0,
            cameraMake = "Google",
            cameraModel = "Pixel 8",
        )
        val annotation = service.import(tempFile, graphPath, pageUuid, ImageSource.CAMERA)
            .requireSuccess()

        val fromRepo = imageRepo.getImageAnnotationByUuid(annotation.uuid).first().getOrNull()
        assertNotNull(fromRepo)
        assertEquals(4.2, fromRepo.sensorData.focalLengthMm)
        assertEquals(26.0, fromRepo.sensorData.focalLength35mmEq)
        assertEquals("Google", fromRepo.sensorData.cameraMake)
        assertEquals("Pixel 8", fromRepo.sensorData.cameraModel)
    }

    @Test
    fun `import returns error when source file does not exist`() = runBlocking<Unit> {
        val (service) = build()

        val result = service.import(PlatformImageFile("/tmp/nonexistent.jpg"), graphPath, pageUuid)

        assertTrue(result.isLeft(), "Expected Either.Left but got $result")
    }

    @Test
    fun `import does not write DB row when sidecar write fails`() = runBlocking<Unit> {
        val failFs = object : FakeFileSystem() {
            override fun writeFile(path: String, content: String): Boolean {
                if (path.contains(".stelekit")) return false
                return super.writeFile(path, content)
            }
        }
        failFs.writeFileBytes("/tmp/photo.jpg", fakePngBytes)

        val imageRepo = InMemoryImageAnnotationRepository()
        val service = ImageImportService(
            fileSystem = failFs,
            imageAnnotationRepository = imageRepo,
            blockRepository = InMemoryBlockRepository(),
            sidecarManager = ImageSidecarManager(failFs),
        )

        val result = service.import(PlatformImageFile("/tmp/photo.jpg"), graphPath, pageUuid)

        assertIs<Either.Left<*>>(result)
        val allAnnotations = imageRepo.getAllImageAnnotations().first().getOrNull()
        assertTrue(allAnnotations.isNullOrEmpty(), "DB row must not be committed when sidecar fails")
    }
}
